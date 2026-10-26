package io.github.raulgl422.cliphive.protocol.message;

import java.util.UUID;

/**
 * Worker -&gt; Server. The worker was assigned a task it can't run right now
 * (e.g. lost the capability it advertised, or is already full) and declines
 * it. The server should return the task to {@code PENDING} without
 * incrementing either retry counter - the task itself isn't at fault.
 *
 * @param reason human-readable explanation, surfaced in superadmin logs
 */
public record TaskReject(UUID taskId, int attempt, String reason) implements ProtocolMessage {
}
