package io.github.raulgl422.cliphive.protocol.message;

import java.util.UUID;

/**
 * Server -&gt; Worker. Reply to a successful {@link Hello}: hands the worker a
 * session id and the heartbeat timing it must honor.
 *
 * @param sessionId          identifies this connection for logging/diagnostics
 * @param pingIntervalSeconds how often the server sends {@link Ping} - 20s per
 *                            docs/DESIGN.md section 6.5
 * @param pingTimeoutSeconds how long the server waits for a {@link Pong}
 *                           before counting it as a missed heartbeat - 10s
 *                           per docs/DESIGN.md section 6.5
 * @param maxMissedPings     consecutive missed heartbeats before the
 *                           connection is considered lost and the worker's
 *                           tasks go back to {@code PENDING} - 3 per
 *                           docs/DESIGN.md section 6.5
 */
public record Welcome(
        UUID sessionId,
        int pingIntervalSeconds,
        int pingTimeoutSeconds,
        int maxMissedPings
) implements ProtocolMessage {
}
