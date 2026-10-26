package io.github.raulgl422.cliphive.server.service.worker;

import io.github.raulgl422.cliphive.protocol.ProtocolVersion;
import io.github.raulgl422.cliphive.protocol.codec.ProtocolCodec;
import io.github.raulgl422.cliphive.protocol.message.Goodbye;
import io.github.raulgl422.cliphive.protocol.message.Hello;
import io.github.raulgl422.cliphive.protocol.message.Pong;
import io.github.raulgl422.cliphive.protocol.message.WorkerCapabilities;
import io.github.raulgl422.cliphive.server.model.Worker;
import io.github.raulgl422.cliphive.server.model.WorkerSession;
import io.github.raulgl422.cliphive.server.repository.WorkerRepository;
import io.github.raulgl422.cliphive.server.repository.WorkerSessionRepository;
import io.github.raulgl422.cliphive.server.service.LogEventService;
import io.github.raulgl422.cliphive.server.service.TaskLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.github.raulgl422.cliphive.server.service.WorkerAdminService.sha256Hex;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerWebSocketHandlerTest {

    private WorkerRepository workerRepo;
    private WorkerSessionRepository sessionRepo;
    private WorkerSessionRegistry registry;
    private TransactionTemplate txTemplate;
    private WorkerWebSocketHandler handler;
    private TaskLifecycleService taskLifecycle;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        workerRepo = mock(WorkerRepository.class);
        sessionRepo = mock(WorkerSessionRepository.class);
        registry = new WorkerSessionRegistry();
        txTemplate = mock(TransactionTemplate.class);

        // Default: txTemplate.execute() calls the callback with null tx.
        when(txTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });

        taskLifecycle = mock(TaskLifecycleService.class);
        handler = new WorkerWebSocketHandler(workerRepo, sessionRepo, registry, txTemplate,
                taskLifecycle, mock(LogEventService.class));
    }

    @Test
    void hello_with_valid_token_registers_worker_and_sends_welcome() throws Exception {
        String token = "secret-worker-token";
        String hash = sha256Hex(token);

        Worker worker = new Worker("my-worker", hash);
        setId(worker, UUID.randomUUID());

        WorkerSession dbSession = new WorkerSession(worker);
        setId(dbSession, UUID.randomUUID());

        when(workerRepo.findByTokenHash(hash)).thenReturn(Optional.of(worker));
        when(workerRepo.findById(worker.getId())).thenReturn(Optional.of(worker));
        when(sessionRepo.save(any())).thenReturn(dbSession);

        WebSocketSession ws = mockWsSession("ws-1");

        Hello hello = new Hello(token, ProtocolVersion.CURRENT, "1.0", "linux",
                new WorkerCapabilities(false, List.of("libx264"), "cpu", List.of("medium"), 2));
        handler.handleTextMessage(ws, new TextMessage(ProtocolCodec.encode(hello)));

        ConnectedWorker cw = registry.byWsSessionId("ws-1");
        assertThat(cw).isNotNull();
        assertThat(cw.workerId()).isEqualTo(worker.getId());

        // Verify WELCOME was sent.
        verify(ws, times(1)).sendMessage(any());
    }

    @Test
    void hello_with_unknown_token_closes_session() throws Exception {
        when(workerRepo.findByTokenHash(any())).thenReturn(Optional.empty());
        WebSocketSession ws = mockWsSession("ws-2");

        Hello hello = new Hello("bad-token", ProtocolVersion.CURRENT, "1.0", "linux",
                new WorkerCapabilities(false, List.of(), "cpu", List.of(), 1));
        handler.handleTextMessage(ws, new TextMessage(ProtocolCodec.encode(hello)));

        assertThat(registry.byWsSessionId("ws-2")).isNull();
        verify(ws).close(any(CloseStatus.class));
    }

    @Test
    void hello_with_incompatible_protocol_closes_session() throws Exception {
        String token = "token";
        Worker worker = new Worker("w", sha256Hex(token));
        setId(worker, UUID.randomUUID());
        when(workerRepo.findByTokenHash(any())).thenReturn(Optional.of(worker));

        WebSocketSession ws = mockWsSession("ws-3");

        Hello hello = new Hello(token, 999, "old-worker", "linux",
                new WorkerCapabilities(false, List.of(), "cpu", List.of(), 1));
        handler.handleTextMessage(ws, new TextMessage(ProtocolCodec.encode(hello)));

        assertThat(registry.byWsSessionId("ws-3")).isNull();
        verify(ws).close(any(CloseStatus.class));
    }

    @Test
    void hello_with_revoked_token_closes_session() throws Exception {
        String token = "revoked-token";
        Worker worker = new Worker("w", sha256Hex(token));
        setId(worker, UUID.randomUUID());
        worker.revoke();
        when(workerRepo.findByTokenHash(any())).thenReturn(Optional.of(worker));

        WebSocketSession ws = mockWsSession("ws-4");

        Hello hello = new Hello(token, ProtocolVersion.CURRENT, "1.0", "linux",
                new WorkerCapabilities(false, List.of(), "cpu", List.of(), 1));
        handler.handleTextMessage(ws, new TextMessage(ProtocolCodec.encode(hello)));

        assertThat(registry.byWsSessionId("ws-4")).isNull();
        verify(ws).close(any(CloseStatus.class));
    }

    @Test
    void non_hello_message_before_auth_closes_session() throws Exception {
        WebSocketSession ws = mockWsSession("ws-5");

        handler.handleTextMessage(ws, new TextMessage(ProtocolCodec.encode(new Pong())));

        assertThat(registry.byWsSessionId("ws-5")).isNull();
        verify(ws).close(any(CloseStatus.class));
    }

    @Test
    void pong_resets_missed_pings() throws Exception {
        ConnectedWorker cw = mockConnectedWorker("ws-6");
        cw.missedPings.set(2);
        cw.waitingForPong.set(true);

        handler.handleTextMessage(cw.wsSession, new TextMessage(ProtocolCodec.encode(new Pong())));

        assertThat(cw.missedPings.get()).isEqualTo(0);
        assertThat(cw.waitingForPong.get()).isFalse();
    }

    @Test
    void sha256_hex_is_stable() {
        // Verify that the same token always produces the same hash — critical for
        // token auth to work across server restarts.
        String hash1 = sha256Hex("my-token");
        String hash2 = sha256Hex("my-token");
        assertThat(hash1).isEqualTo(hash2).hasSize(64).matches("[0-9a-f]+");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    // ── Disconnects: who gets the tasks back (DESIGN.md §6.5) ────────────────

    @Test
    void a_lost_connection_puts_the_workers_tasks_back_in_the_queue() throws Exception {
        Worker worker = registeredWorker();
        WebSocketSession ws = hello("ws-10", worker);

        handler.afterConnectionClosed(ws, CloseStatus.SESSION_NOT_RELIABLE);

        verify(taskLifecycle).reassignWorkerTasks(worker.getId());
    }

    @Test
    void goodbye_hands_the_tasks_back_without_counting_a_disconnect() throws Exception {
        Worker worker = registeredWorker();
        WebSocketSession ws = hello("ws-11", worker);

        handler.handleTextMessage(ws, new TextMessage(ProtocolCodec.encode(new Goodbye())));
        handler.afterConnectionClosed(ws, CloseStatus.NORMAL);

        verify(taskLifecycle).releaseWorkerTasks(worker.getId());
        verify(taskLifecycle, never()).reassignWorkerTasks(any());
    }

    @Test
    void a_reconnect_leaves_the_tasks_with_the_worker() throws Exception {
        Worker worker = registeredWorker();
        WebSocketSession old = hello("ws-12", worker);
        hello("ws-13", worker);
        handler.afterConnectionClosed(old, CloseStatus.NORMAL);

        verify(taskLifecycle, never()).reassignWorkerTasks(any());
        verify(taskLifecycle, never()).releaseWorkerTasks(any());
        assertThat(registry.byWorkerId(worker.getId()).wsSession.getId()).isEqualTo("ws-13");
    }

    private Worker registeredWorker() {
        Worker worker = new Worker("w", sha256Hex("token"));
        setId(worker, UUID.randomUUID());
        when(workerRepo.findByTokenHash(any())).thenReturn(Optional.of(worker));
        when(workerRepo.findById(worker.getId())).thenReturn(Optional.of(worker));
        when(sessionRepo.save(any())).thenAnswer(inv -> {
            WorkerSession s = inv.getArgument(0);
            setId(s, UUID.randomUUID());
            return s;
        });
        return worker;
    }

    private WebSocketSession hello(String wsId, Worker worker) throws Exception {
        WebSocketSession ws = mockWsSession(wsId);
        handler.handleTextMessage(ws, new TextMessage(ProtocolCodec.encode(new Hello("token", ProtocolVersion.CURRENT,
                "1.0", "linux", new WorkerCapabilities(false, List.of(), "cpu", List.of(), 1)))));
        assertThat(registry.byWsSessionId(wsId)).isNotNull();
        return ws;
    }

    private static WebSocketSession mockWsSession(String id) throws Exception {
        WebSocketSession ws = mock(WebSocketSession.class);
        when(ws.getId()).thenReturn(id);
        when(ws.isOpen()).thenReturn(true);
        return ws;
    }

    private ConnectedWorker mockConnectedWorker(String wsId) throws Exception {
        Worker worker = new Worker("w", sha256Hex("t"));
        setId(worker, UUID.randomUUID());
        WebSocketSession ws = mockWsSession(wsId);
        ConnectedWorker cw = new ConnectedWorker(UUID.randomUUID(), worker, ws);
        registry.register(cw);
        return cw;
    }

    private static void setId(Object entity, UUID id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
