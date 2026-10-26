package io.github.raulgl422.cliphive.server.service.worker;

import io.github.raulgl422.cliphive.protocol.message.ProtocolMessage;
import io.github.raulgl422.cliphive.server.model.Worker;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static io.github.raulgl422.cliphive.protocol.codec.ProtocolCodec.encode;

/**
 * In-memory state for one active worker WebSocket connection.
 *
 * <p>One instance lives in {@link WorkerSessionRegistry} for as long as the
 * worker is connected. It holds the live {@link WebSocketSession}, the
 * corresponding DB worker row, the session UUID written to {@code worker_session},
 * and the heartbeat counters the {@link HeartbeatScheduler} mutates.
 *
 * <p>Thread-safety: {@link #missedPings} and {@link #waitingForPong} are atomic.
 * {@link #send} synchronises on the session to prevent interleaved frames.
 */
public class ConnectedWorker {

    final UUID sessionId;
    final Worker worker;
    final WebSocketSession wsSession;

    final AtomicBoolean waitingForPong = new AtomicBoolean(false);
    final AtomicInteger missedPings = new AtomicInteger(0);
    volatile Instant lastHeartbeatAt = Instant.now();

    public ConnectedWorker(UUID sessionId, Worker worker, WebSocketSession wsSession) {
        this.sessionId = sessionId;
        this.worker = worker;
        this.wsSession = wsSession;
    }

    /**
     * Sends a protocol message. False when it couldn't go out (the session is
     * closed or the write failed): the handler notices the close by itself,
     * but a caller that just handed over a task needs to know.
     */
    public boolean send(ProtocolMessage message) {
        if (!wsSession.isOpen()) return false;
        try {
            synchronized (wsSession) {
                wsSession.sendMessage(new TextMessage(encode(message)));
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void close() {
        if (wsSession.isOpen()) {
            try { wsSession.close(); } catch (IOException ignored) {}
        }
    }

    public UUID workerId() {
        return worker.getId();
    }

    public Worker worker() {
        return worker;
    }

    /** Read by the workers admin page ({@code cw.lastHeartbeatAt}). */
    public Instant getLastHeartbeatAt() {
        return lastHeartbeatAt;
    }
}
