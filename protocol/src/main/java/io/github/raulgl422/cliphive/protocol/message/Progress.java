package io.github.raulgl422.cliphive.protocol.message;

import java.util.UUID;

/**
 * Worker -&gt; Server. Periodic progress update while a task runs. The server
 * uses the time between {@code PROGRESS} messages (not just the heartbeat)
 * to detect a <b>stalled</b> task - one where the connection is alive but
 * the work itself is stuck - and reassigns it after the configured timeout,
 * per docs/DESIGN.md section 6.5.
 *
 * @param percent 0-100
 * @param phase   short machine-readable phase name specific to the task type,
 *                e.g. {@code "downloading"}, {@code "transcribing"},
 *                {@code "encoding"} - shown as-is in the superadmin
 */
public record Progress(UUID taskId, int attempt, int percent, String phase) implements ProtocolMessage {
}
