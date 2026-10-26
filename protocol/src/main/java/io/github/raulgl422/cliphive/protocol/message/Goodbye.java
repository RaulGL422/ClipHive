package io.github.raulgl422.cliphive.protocol.message;

/**
 * Worker -&gt; Server. Voluntary close of the whole session (clean shutdown,
 * {@code Ctrl+C}/{@code SIGTERM} in headless mode - docs/DESIGN.md section
 * 8.2). The server should close the connection without waiting for the
 * heartbeat to time out. Any tasks still running on this worker are released
 * the same way as {@link TaskRelease}.
 */
public record Goodbye() implements ProtocolMessage {
}
