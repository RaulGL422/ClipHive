package io.github.raulgl422.cliphive.protocol.message;

/** Worker -&gt; Server. Reply to {@link Ping}. See {@link Welcome} for timing. */
public record Pong() implements ProtocolMessage {
}
