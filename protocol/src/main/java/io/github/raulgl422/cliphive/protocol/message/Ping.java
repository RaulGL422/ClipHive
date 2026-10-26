package io.github.raulgl422.cliphive.protocol.message;

/**
 * Server -&gt; Worker. Heartbeat, sent every {@code pingIntervalSeconds} (see
 * {@link Welcome}). Expects a {@link Pong} within {@code pingTimeoutSeconds};
 * {@code maxMissedPings} consecutive misses mean the connection is
 * considered lost - docs/DESIGN.md section 6.5.
 */
public record Ping() implements ProtocolMessage {
}
