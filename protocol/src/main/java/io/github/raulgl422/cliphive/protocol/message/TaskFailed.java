package io.github.raulgl422.cliphive.protocol.message;

import java.util.UUID;

/**
 * Worker -&gt; Server. The task failed. This counts against the task's
 * <b>failure</b> counter (max 3, docs/DESIGN.md section 6.3) - as opposed to
 * a dropped connection, which counts against the separate disconnection
 * counter (max 10) and shouldn't be reported this way.
 *
 * @param error human-readable error, surfaced in superadmin logs
 */
public record TaskFailed(UUID taskId, int attempt, String error) implements ProtocolMessage {
}
