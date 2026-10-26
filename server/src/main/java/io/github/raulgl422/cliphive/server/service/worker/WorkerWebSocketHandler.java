package io.github.raulgl422.cliphive.server.service.worker;

import io.github.raulgl422.cliphive.protocol.ProtocolVersion;
import io.github.raulgl422.cliphive.protocol.codec.ProtocolCodec;
import io.github.raulgl422.cliphive.protocol.codec.ProtocolCodecException;
import io.github.raulgl422.cliphive.protocol.message.Hello;
import io.github.raulgl422.cliphive.protocol.message.MessageDirection;
import io.github.raulgl422.cliphive.protocol.message.Pong;
import io.github.raulgl422.cliphive.protocol.message.Progress;
import io.github.raulgl422.cliphive.protocol.message.ProtocolMessage;
import io.github.raulgl422.cliphive.protocol.message.Goodbye;
import io.github.raulgl422.cliphive.protocol.message.TaskAccept;
import io.github.raulgl422.cliphive.protocol.message.TaskDone;
import io.github.raulgl422.cliphive.protocol.message.TaskFailed;
import io.github.raulgl422.cliphive.protocol.message.TaskReject;
import io.github.raulgl422.cliphive.protocol.message.TaskRelease;
import io.github.raulgl422.cliphive.protocol.message.Welcome;
import io.github.raulgl422.cliphive.server.model.Worker;
import io.github.raulgl422.cliphive.server.model.WorkerSession;
import io.github.raulgl422.cliphive.server.repository.WorkerRepository;
import io.github.raulgl422.cliphive.server.repository.WorkerSessionRepository;
import io.github.raulgl422.cliphive.server.service.LogEventService;
import io.github.raulgl422.cliphive.server.service.WorkerAdminService;
import io.github.raulgl422.cliphive.server.service.TaskLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;
import java.util.UUID;

/**
 * Handles the server side of the worker WebSocket protocol
 * (docs/DESIGN.md §7).
 *
 * <p><b>Connection lifecycle:</b>
 * <ol>
 *   <li>Worker connects → server waits for {@code HELLO}.
 *   <li>Server verifies token hash, protocol major version and revocation.
 *       On failure it closes the session with a descriptive status.
 *   <li>On success: persists a {@link WorkerSession} row, sends {@code WELCOME},
 *       registers the worker in {@link WorkerSessionRegistry}.
 *   <li>Heartbeat loop handled by {@link HeartbeatScheduler} (PING every 20 s;
 *       3 missed PONGs → disconnect).
 *   <li>Worker sends task messages; server dispatches them.
 *   <li>On disconnect (voluntary {@code GOODBYE} or error): closes the
 *       {@code worker_session} row and unregisters.
 * </ol>
 *
 * <p>If the very first message is not {@code HELLO}, the connection is closed.
 * A worker that reconnects after being disconnected sends a fresh {@code HELLO}
 * — any prior in-memory session for that worker is evicted first.
 */
@Component
public class WorkerWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkerWebSocketHandler.class);

    // Heartbeat config sent in WELCOME (docs/DESIGN.md §6.5). The server itself counts a
    // missed PONG per ping interval (HeartbeatScheduler), so a dead link shows after ~60 s.
    static final int PING_INTERVAL_SECONDS = 20;
    static final int PING_TIMEOUT_SECONDS = 10;
    static final int MAX_MISSED_PINGS = 3;

    private final WorkerRepository workerRepo;
    private final WorkerSessionRepository sessionRepo;
    private final WorkerSessionRegistry registry;
    private final TransactionTemplate txTemplate;
    private final TaskLifecycleService taskLifecycle;
    private final LogEventService logEvents;

    public WorkerWebSocketHandler(WorkerRepository workerRepo,
                                  WorkerSessionRepository sessionRepo,
                                  WorkerSessionRegistry registry,
                                  TransactionTemplate txTemplate,
                                  TaskLifecycleService taskLifecycle,
                                  LogEventService logEvents) {
        this.workerRepo = workerRepo;
        this.sessionRepo = sessionRepo;
        this.registry = registry;
        this.txTemplate = txTemplate;
        this.taskLifecycle = taskLifecycle;
        this.logEvents = logEvents;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.debug("Worker WebSocket connected: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ProtocolMessage msg;
        try {
            msg = ProtocolCodec.decode(message.getPayload(), MessageDirection.WORKER_TO_SERVER);
        } catch (ProtocolCodecException e) {
            log.warn("Unparseable message from {}: {}", session.getId(), e.getMessage());
            closeQuietly(session, CloseStatus.BAD_DATA);
            return;
        }

        ConnectedWorker cw = registry.byWsSessionId(session.getId());

        if (cw == null) {
            // Not yet authenticated — only HELLO is acceptable.
            if (msg instanceof Hello hello) {
                handleHello(session, hello);
            } else {
                log.warn("Expected HELLO from {}, got {}", session.getId(),
                        msg.getClass().getSimpleName());
                closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            }
            return;
        }

        switch (msg) {
            case Pong ignored -> handlePong(cw);
            case Goodbye ignored -> handleGoodbye(session, cw);
            case TaskAccept accept ->
                taskLifecycle.accept(accept.taskId(), accept.attempt(), cw.workerId());
            case TaskReject reject ->
                taskLifecycle.reject(reject.taskId(), reject.attempt(), reject.reason(), cw.workerId());
            case Progress progress ->
                taskLifecycle.progress(progress.taskId(), progress.attempt(),
                        progress.percent(), progress.phase(), cw.workerId());
            case TaskDone done ->
                taskLifecycle.done(done.taskId(), done.attempt(), cw.workerId());
            case TaskFailed failed ->
                taskLifecycle.fail(failed.taskId(), failed.attempt(), failed.error(), cw.workerId());
            case TaskRelease release ->
                taskLifecycle.release(release.taskId(), release.attempt(), cw.workerId());
            // HELLO after auth, or server-only messages (WELCOME, TASK_ASSIGN, etc.) — reject.
            default -> {
                log.warn("Unexpected message {} from worker {}", msg.getClass().getSimpleName(), cw.workerId());
                closeQuietly(session, CloseStatus.POLICY_VIOLATION);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ConnectedWorker cw = registry.byWsSessionId(session.getId());
        if (cw == null) return; // Closed before HELLO completed.
        registry.unregister(session.getId());
        closeDbSession(cw.sessionId, status.getReason() != null ? status.getReason() : status.toString());
        taskLifecycle.reassignWorkerTasks(cw.workerId());
        log.info("Worker {} disconnected (session {}): {}", cw.workerId(), cw.sessionId, status);
        logEvents.warn("workers", "Worker " + cw.worker().getName() + " desconectado ("
                + (status.getReason() != null ? status.getReason() : "código " + status.getCode()) + ")", null);
    }

    // ── Message handlers ────────────────────────────────────────────────────

    private void handleHello(WebSocketSession wsSession, Hello hello) {
        String tokenHash = WorkerAdminService.sha256Hex(hello.workerToken());

        Worker worker = txTemplate.execute(tx -> {
            Worker w = workerRepo.findByTokenHash(tokenHash).orElse(null);
            if (w == null) {
                log.warn("Unknown worker token from {}", wsSession.getId());
                closeQuietly(wsSession, CloseStatus.POLICY_VIOLATION.withReason("Unknown token"));
                return null;
            }
            if (w.isRevoked()) {
                log.warn("Revoked worker {} tried to connect", w.getId());
                closeQuietly(wsSession, CloseStatus.POLICY_VIOLATION.withReason("Token revoked"));
                return null;
            }
            return w;
        });
        if (worker == null) return;

        if (!ProtocolVersion.isCompatible(hello.protocolMajorVersion())) {
            log.warn("Worker {} sent incompatible protocol version {} (server is {})",
                    worker.getId(), hello.protocolMajorVersion(), ProtocolVersion.CURRENT);
            closeQuietly(wsSession, CloseStatus.POLICY_VIOLATION
                    .withReason("Protocol version mismatch: update your worker"));
            return;
        }

        // Evict any previous session from the same worker (reconnect scenario).
        ConnectedWorker existing = registry.byWorkerId(worker.getId());
        if (existing != null) {
            log.info("Worker {} reconnected — closing previous session {}", worker.getId(), existing.sessionId);
            registry.unregister(existing.wsSession.getId());
            closeDbSession(existing.sessionId, "reconnected");
            closeQuietly(existing.wsSession, CloseStatus.NORMAL.withReason("reconnected"));
            // Its tasks stay with it: a worker keeps running them across a
            // reconnect and reports them under the same attempt. If it really
            // restarted and lost them, the progress timeout takes them back.
        }

        UUID sessionId = txTemplate.execute(tx -> {
            Worker managed = workerRepo.findById(worker.getId()).orElseThrow();
            managed.setLastCapabilities(hello.capabilities());
            managed.setLastSeenAt(Instant.now());
            WorkerSession dbSession = sessionRepo.save(new WorkerSession(managed));
            return dbSession.getId();
        });

        ConnectedWorker cw = new ConnectedWorker(sessionId, worker, wsSession);
        registry.register(cw);

        cw.send(new Welcome(sessionId, PING_INTERVAL_SECONDS, PING_TIMEOUT_SECONDS, MAX_MISSED_PINGS));
        log.info("Worker {} connected (session {}, protocol v{}, os={})",
                worker.getId(), sessionId, hello.protocolMajorVersion(), hello.operatingSystem());
        logEvents.info("workers", "Worker " + worker.getName() + " conectado (" + hello.operatingSystem() + ")");
    }

    private void handlePong(ConnectedWorker cw) {
        cw.waitingForPong.set(false);
        cw.missedPings.set(0);
        cw.lastHeartbeatAt = Instant.now();
        txTemplate.executeWithoutResult(tx ->
                sessionRepo.findById(cw.sessionId).ifPresent(s -> {
                    s.setLastHeartbeatAt(cw.lastHeartbeatAt);
                    sessionRepo.save(s);
                }));
    }

    private void handleGoodbye(WebSocketSession wsSession, ConnectedWorker cw) {
        log.info("Worker {} sent GOODBYE", cw.workerId());
        registry.unregister(wsSession.getId());
        closeDbSession(cw.sessionId, "goodbye");
        // A clean stop should have released its tasks first; whatever it
        // still holds goes back to the queue without spending a counter.
        taskLifecycle.releaseWorkerTasks(cw.workerId());
        closeQuietly(wsSession, CloseStatus.NORMAL);
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    /** Closes the {@code worker_session} DB row. */
    private void closeDbSession(UUID sessionId, String reason) {
        txTemplate.executeWithoutResult(tx ->
                sessionRepo.findById(sessionId).ifPresent(s -> {
                    s.close(reason);
                    sessionRepo.save(s);
                }));
    }

    /** Closes the WebSocket session without propagating the exception. */
    static void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) session.close(status);
        } catch (Exception ignored) {
        }
    }
}
