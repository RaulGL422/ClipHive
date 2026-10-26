package io.github.raulgl422.cliphive.worker.net;

import io.github.raulgl422.cliphive.protocol.ProtocolVersion;
import io.github.raulgl422.cliphive.protocol.codec.ProtocolCodec;
import io.github.raulgl422.cliphive.protocol.codec.ProtocolCodecException;
import io.github.raulgl422.cliphive.protocol.message.Goodbye;
import io.github.raulgl422.cliphive.protocol.message.Hello;
import io.github.raulgl422.cliphive.protocol.message.MessageDirection;
import io.github.raulgl422.cliphive.protocol.message.Ping;
import io.github.raulgl422.cliphive.protocol.message.Pong;
import io.github.raulgl422.cliphive.protocol.message.ProtocolMessage;
import io.github.raulgl422.cliphive.protocol.message.TaskAssign;
import io.github.raulgl422.cliphive.protocol.message.TaskCancel;
import io.github.raulgl422.cliphive.protocol.message.TaskDone;
import io.github.raulgl422.cliphive.protocol.message.TaskFailed;
import io.github.raulgl422.cliphive.protocol.message.TaskReject;
import io.github.raulgl422.cliphive.protocol.message.TaskRelease;
import io.github.raulgl422.cliphive.protocol.message.Welcome;
import io.github.raulgl422.cliphive.protocol.message.WorkerCapabilities;
import io.github.raulgl422.cliphive.worker.config.WorkerProperties;
import io.github.raulgl422.cliphive.worker.task.TaskOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Manages the worker's WebSocket connection to the server
 * (docs/DESIGN.md §7, §8.2).
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #runWithReconnect} — connects (HELLO → WELCOME) and stays
 *       connected, reconnecting whenever the connection fails or drops.
 *   <li>{@link #shutdown()} — cancels the active tasks, sends GOODBYE (the
 *       server puts those tasks back in the queue) and closes the connection.
 * </ol>
 *
 * <p>Reconnect: a failed connect or an unexpected close backs off
 * exponentially (2 s → 60 s) and retries. A rejection (unknown or revoked
 * token, incompatible protocol, invalid URL) is reported through the
 * {@code onRejected} callback and retried once a minute. Stopped only by an
 * explicit {@link #shutdown()} call.
 *
 * <p>The worker also watches the connection itself: no PING from the server
 * for {@code pingInterval × (maxMissedPings + 1)} means a half-open socket
 * (after sleep, a Wi-Fi change…), which is dropped and reconnected. Task
 * results that can't be sent are kept and sent after the next WELCOME.
 */
public class ServerConnection {

    private static final Logger log = LoggerFactory.getLogger(ServerConnection.class);

    private static final long INITIAL_BACKOFF_MS = 2_000;
    private static final long MAX_BACKOFF_MS     = 60_000;
    private static final long SEND_TIMEOUT_S     = 10;
    private static final long WATCHDOG_TICK_S    = 5;
    /** RFC 6455 "policy violation": what the server closes a rejected HELLO with. */
    static final int CLOSE_POLICY_VIOLATION = 1008;

    private final WorkerProperties props;
    private final WorkerCapabilities capabilities;
    private final TaskOrchestrator orchestrator;
    private final String os;

    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private final AtomicReference<WebSocket> wsRef = new AtomicReference<>();
    private volatile CountDownLatch welcomeLatch;
    /** WELCOME received on the current socket: until then only HELLO may go out (§7). */
    private volatile boolean authenticated;
    /** Counted down when the current connection closes (or on shutdown). */
    private volatile CountDownLatch disconnected = new CountDownLatch(1);
    private volatile int lastCloseCode;
    private volatile String lastCloseReason = "";
    private final Object sendLock = new Object();
    private volatile Consumer<ProtocolMessage> outgoingListener = m -> {};
    /** Reused across reconnects: each client owns a selector thread. */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /** TASK_DONE / TASK_FAILED / TASK_RELEASE that couldn't be sent, by task: sent after the next WELCOME. */
    private final Map<UUID, ProtocolMessage> outbox = new ConcurrentHashMap<>();

    /** Last time anything arrived from the server, and how long silence may last (0 = not watching). */
    private volatile long lastHeardNanos = System.nanoTime();
    private volatile long silenceLimitNanos;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "server-watchdog");
        t.setDaemon(true);
        return t;
    });

    public ServerConnection(WorkerProperties props,
                            WorkerCapabilities capabilities,
                            TaskOrchestrator orchestrator) {
        this.props = props;
        this.capabilities = capabilities;
        this.orchestrator = orchestrator;
        this.os = System.getProperty("os.name", "unknown");
        watchdog.scheduleWithFixedDelay(this::checkSilence, WATCHDOG_TICK_S, WATCHDOG_TICK_S, TimeUnit.SECONDS);
    }

    /** Retrying won't help: the server refused the worker or the URL is unusable. */
    static final class RejectedException extends IllegalArgumentException {
        RejectedException(String message) { super(message); }
    }

    /**
     * Connects and sends HELLO. Blocks until WELCOME is received (up to 30 s),
     * then sends what was kept in the outbox.
     *
     * @throws RejectedException if the server refused the worker or the URL is invalid
     * @throws RuntimeException  if the connection or handshake fails
     */
    private void connect() {
        URI uri = serverUri(props.getServerUrl());
        welcomeLatch = new CountDownLatch(1);
        authenticated = false;
        WebSocket ws = openWebSocket(uri);
        wsRef.set(ws);
        lastHeardNanos = System.nanoTime();

        Hello hello = new Hello(
                props.getWorkerToken(),
                ProtocolVersion.CURRENT,
                workerVersion(),
                os,
                capabilities);
        sendMessage(hello);
        log.info("HELLO sent to {}", props.getServerUrl());

        try {
            if (!welcomeLatch.await(30, TimeUnit.SECONDS)) {
                throw new RuntimeException("Timed out waiting for WELCOME from server");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for WELCOME", e);
        }
        // handleClose also releases the latch, so a rejected HELLO (unknown or
        // revoked token) fails right away instead of after the 30 s timeout.
        if (disconnected.getCount() == 0) {
            if (lastCloseCode == CLOSE_POLICY_VIOLATION) {
                throw new RejectedException(explainRejection(lastCloseReason));
            }
            throw new RuntimeException("Server closed the connection: " + lastCloseCode + " " + lastCloseReason);
        }
        authenticated = true;
        flushOutbox();
    }

    /**
     * Connects and keeps the worker connected until {@link #shutdown()}. A
     * failed connect (server down, Tailscale not up yet) or a lost connection
     * is retried with exponential backoff (2 s → 60 s) instead of giving up.
     * Blocks the calling thread.
     *
     * @param onConnecting called before every connection attempt
     * @param onConnected  called after each successful handshake (WELCOME)
     * @param onRejected   called with what to fix when retrying alone won't help
     */
    public void runWithReconnect(Runnable onConnecting, Runnable onConnected, Consumer<String> onRejected) {
        long backoff = INITIAL_BACKOFF_MS;
        while (!stopping.get()) {
            // Fresh latch before connecting: the server may close during the handshake.
            disconnected = new CountDownLatch(1);
            try {
                onConnecting.run();
                connect();
                onConnected.run();
                backoff = INITIAL_BACKOFF_MS;
                disconnected.await();
                if (stopping.get()) break;
                log.warn("Connection to server lost");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RejectedException e) {
                if (stopping.get()) break;
                log.error("Server rejected the worker: {}", e.getMessage());
                onRejected.accept(e.getMessage());
                closeQuietly();
                backoff = MAX_BACKOFF_MS; // fixing the config is enough; no need to hammer the server
            } catch (RuntimeException e) {
                if (stopping.get()) break;
                log.warn("Could not connect to {}: {}", props.getServerUrl(), rootMessage(e));
                closeQuietly();
            }

            log.info("Retrying connection in {} s", backoff / 1000);
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
        }
    }

    /** Graceful shutdown: cancel active tasks, send GOODBYE, close WS. */
    public void shutdown() {
        if (!stopping.compareAndSet(false, true)) return;
        log.info("Shutting down worker connection...");
        // The server puts what this worker still held back in the queue on GOODBYE (§6.5)
        orchestrator.releaseAll();
        sendMessage(new Goodbye());
        WebSocket ws = wsRef.get();
        if (ws != null) {
            try {
                // Wait a little: from the shutdown hook the JVM may exit before the close goes out
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown").get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.debug("Close frame not confirmed: {}", rootMessage(e));
            }
        }
        watchdog.shutdownNow();
        disconnected.countDown();
    }

    /**
     * Registers a callback that sees every message the worker sends (task
     * progress, done, failed…) — used to show the current task in the UI.
     */
    public void setOutgoingListener(Consumer<ProtocolMessage> listener) {
        this.outgoingListener = listener != null ? listener : m -> {};
    }

    /**
     * Sends a protocol message to the server. Thread-safe: sends are
     * serialized because java.net.http.WebSocket rejects a send while another
     * is pending — a PONG from the listener thread colliding with a PROGRESS
     * from a task thread would be dropped, and missed PONGs get the worker
     * disconnected by the server's heartbeat check.
     *
     * <p>A task result (TASK_DONE, TASK_FAILED, TASK_RELEASE) that can't be
     * sent is kept and sent after the next WELCOME; anything else is dropped.
     */
    public void sendMessage(ProtocolMessage message) {
        try {
            outgoingListener.accept(message);
        } catch (RuntimeException e) {
            log.warn("Outgoing listener failed: {}", e.toString());
        }
        if (trySend(message)) return;
        UUID taskId = resultTaskId(message);
        if (taskId != null) {
            outbox.put(taskId, message);
            log.info("{} for task {} kept until the connection is back",
                     message.getClass().getSimpleName(), taskId);
            // The connection may have come back (and flushed) while this was being parked
            if (authenticated) flushOutbox();
        }
    }

    // ── Internal ────────────────────────────────────────────────────────────

    /** True when the message went out; a failed or stuck send drops the connection. */
    private boolean trySend(ProtocolMessage message) {
        WebSocket ws = wsRef.get();
        String name = message.getClass().getSimpleName();
        // Before WELCOME the server only accepts HELLO: anything else gets the socket closed
        if (!authenticated && !(message instanceof Hello)) {
            log.debug("Not sending {} — not connected yet", name);
            return false;
        }
        if (ws == null || ws.isOutputClosed() || ws.isInputClosed()) {
            log.warn("Cannot send {} — WebSocket not open", name);
            return false;
        }
        // A cancelled task's thread is interrupted, yet its TASK_FAILED must still go
        // out on a healthy connection: send without honouring the interrupt, then restore it.
        boolean interrupted = Thread.interrupted();
        try {
            synchronized (sendLock) {
                ws.sendText(ProtocolCodec.encode(message), true).get(SEND_TIMEOUT_S, TimeUnit.SECONDS);
                return true;
            }
        } catch (InterruptedException e) {
            interrupted = true;
            return false;
        } catch (Exception e) {
            log.warn("Failed to send {}: {} — reconnecting", name, rootMessage(e));
            drop(ws, "send failed: " + rootMessage(e));
            return false;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * The version reported in HELLO: the installer's (jpackage sets
     * {@code jpackage.app-version} from the release tag), else "dev" for a
     * worker run from the build.
     */
    static String workerVersion() {
        String version = System.getProperty("jpackage.app-version");
        return version != null && !version.isBlank() ? version : "dev";
    }

    /** The task a result message is about, or null when it isn't one worth keeping. */
    static UUID resultTaskId(ProtocolMessage message) {
        return switch (message) {
            case TaskDone d    -> d.taskId();
            case TaskFailed f  -> f.taskId();
            case TaskRelease r -> r.taskId();
            default            -> null;
        };
    }

    private void flushOutbox() {
        for (UUID taskId : outbox.keySet()) {
            ProtocolMessage kept = outbox.remove(taskId);
            if (kept == null) continue;
            log.info("Sending the kept {} for task {}", kept.getClass().getSimpleName(), taskId);
            if (!trySend(kept)) {
                outbox.putIfAbsent(taskId, kept);
                return;
            }
        }
    }

    /**
     * {@code serverUrl} as a ws:// or wss:// URI, or an IllegalArgumentException
     * (a RejectedException) saying how to fix it. Also used by the config editor.
     */
    public static URI serverUri(String serverUrl) {
        String url = serverUrl == null ? "" : serverUrl.strip();
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if ((scheme.equals("ws") || scheme.equals("wss")) && uri.getHost() != null) return uri;
        } catch (URISyntaxException ignored) {
            // falls through
        }
        throw new RejectedException("La URL del servidor no es válida: '" + url
                + "'. Debe ser ws://<ip-de-tailscale>:8082/ws/worker (o wss://). Corrígela en Configuración.");
    }

    /** What the user should do about the server's close reason for a rejected HELLO. */
    static String explainRejection(String reason) {
        String r = reason == null ? "" : reason;
        if (r.contains("Unknown token")) {
            return "El servidor no reconoce el token. Copia el token del worker desde el superadmin "
                   + "(Workers) en Configuración.";
        }
        if (r.contains("revoked")) {
            return "El token de este worker está revocado. Registra el worker de nuevo en el superadmin "
                   + "y pon el token nuevo en Configuración.";
        }
        if (r.contains("Protocol version")) {
            return "Esta versión del worker no es compatible con el servidor. Instala la última versión.";
        }
        return "El servidor rechazó la conexión" + (r.isBlank() ? "." : ": " + r);
    }

    private WebSocket openWebSocket(URI uri) {
        try {
            return httpClient.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .buildAsync(uri, new Listener())
                    .join();
        } catch (Exception e) {
            throw new RuntimeException("Could not open WebSocket to " + uri, e);
        }
    }

    private void handleMessage(ProtocolMessage msg) {
        switch (msg) {
            case Welcome w -> {
                log.info("Connected — session {}, ping every {}s", w.sessionId(), w.pingIntervalSeconds());
                if (w.pingIntervalSeconds() > 0) {
                    silenceLimitNanos = TimeUnit.SECONDS.toNanos(
                            (long) w.pingIntervalSeconds() * (Math.max(0, w.maxMissedPings()) + 1));
                }
                welcomeLatch.countDown();
            }
            case Ping p -> sendMessage(new Pong());
            case TaskAssign ta -> orchestrator.onTaskAssign(ta, this);
            case TaskCancel tc -> orchestrator.onTaskCancel(tc.taskId());
            default -> log.debug("Unhandled message: {}", msg.getClass().getSimpleName());
        }
    }

    /** Watchdog tick: a connection with no news from the server for too long is half-open. */
    private void checkSilence() {
        WebSocket ws = wsRef.get();
        long limit = silenceLimitNanos;
        if (ws == null || limit <= 0 || stopping.get()) return;
        long silentFor = System.nanoTime() - lastHeardNanos;
        if (silentFor > limit) {
            log.warn("No message from the server in {} s — connection presumed dead, reconnecting",
                     TimeUnit.NANOSECONDS.toSeconds(silentFor));
            drop(ws, "no ping from the server");
        }
    }

    /** Tears down {@code ws} if it's still the current connection and wakes the reconnect loop. */
    private void drop(WebSocket ws, String reason) {
        if (!wsRef.compareAndSet(ws, null)) return;
        ws.abort();
        handleClose(-1, reason);
    }

    /**
     * Runs on the WebSocket listener thread, so it only signals the loop in
     * {@link #runWithReconnect} — sleeping or reconnecting here would block
     * the HTTP client's callbacks.
     */
    private void handleClose(int statusCode, String reason) {
        if (!stopping.get()) {
            log.warn("WebSocket closed: {} {}", statusCode, reason);
        }
        silenceLimitNanos = 0;
        authenticated = false;
        lastCloseCode = statusCode;
        lastCloseReason = reason == null ? "" : reason;
        disconnected.countDown();
        CountDownLatch welcome = welcomeLatch;
        if (welcome != null) welcome.countDown();
    }

    private void closeQuietly() {
        WebSocket ws = wsRef.getAndSet(null);
        if (ws != null) {
            ws.abort();
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message != null ? message : root.getClass().getSimpleName();
    }

    // ── WebSocket.Listener ───────────────────────────────────────────────────

    private class Listener implements WebSocket.Listener {

        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            lastHeardNanos = System.nanoTime();
            buffer.append(data);
            String json = null;
            if (last) {
                json = buffer.toString();
                buffer.setLength(0);
            }
            ws.request(1); // only once the buffer is taken, so the next frame can't mix in
            if (json != null) dispatch(json);
            return CompletableFuture.completedFuture(null);
        }

        private void dispatch(String json) {
            ProtocolMessage msg;
            try {
                msg = ProtocolCodec.decode(json, MessageDirection.SERVER_TO_WORKER);
            } catch (ProtocolCodecException e) {
                log.warn("Malformed message from server: {}", e.getMessage());
                return;
            }
            try {
                handleMessage(msg);
            } catch (RuntimeException e) {
                log.error("Could not handle {} from the server: {}", msg.getClass().getSimpleName(), e.toString(), e);
                // A task we can't start goes straight back to the server instead of waiting for its timeout
                if (msg instanceof TaskAssign ta) {
                    sendMessage(new TaskReject(ta.taskId(), ta.attempt(), "worker error: " + e));
                }
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            // A late close from a socket we already replaced must not end the current one;
            // and once closed it stops being current, so its own late callbacks are ignored too.
            if (wsRef.compareAndSet(ws, null)) handleClose(statusCode, reason);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            if (!wsRef.compareAndSet(ws, null)) return;
            log.error("WebSocket error: {}", rootMessage(error), error);
            handleClose(-1, rootMessage(error));
        }

        @Override
        public void onOpen(WebSocket ws) {
            ws.request(1);
        }
    }
}
