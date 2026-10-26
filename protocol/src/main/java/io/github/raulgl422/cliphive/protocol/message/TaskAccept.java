package io.github.raulgl422.cliphive.protocol.message;

import java.util.UUID;

/**
 * Worker -&gt; Server. Confirms the worker received {@link TaskAssign} and is
 * starting work on it. See {@link TaskAssign} for what {@code attempt} is for.
 */
public record TaskAccept(UUID taskId, int attempt) implements ProtocolMessage {
}
