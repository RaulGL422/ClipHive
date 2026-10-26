package io.github.raulgl422.cliphive.protocol.message;

import java.util.Map;
import java.util.UUID;

/**
 * Worker -&gt; Server. The task finished successfully; the worker has already
 * uploaded its output files to the {@code uploadUrls} it was given in
 * {@link TaskAssign} and reports back what it uploaded.
 *
 * @param uploadedFiles references to the uploaded files, keyed the same way
 *                      {@link TaskAssign#uploadUrls()} was (e.g.
 *                      {@code "clip" -> "<file reference or checksum>"}) so
 *                      the server can match outputs to the request without
 *                      guessing
 */
public record TaskDone(UUID taskId, int attempt, Map<String, String> uploadedFiles) implements ProtocolMessage {
}
