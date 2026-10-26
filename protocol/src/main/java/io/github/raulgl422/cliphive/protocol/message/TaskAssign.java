package io.github.raulgl422.cliphive.protocol.message;

import io.github.raulgl422.cliphive.protocol.state.TaskType;

import java.util.Map;
import java.util.UUID;

/**
 * Server -&gt; Worker. Hands one task to a worker that has free capacity.
 *
 * <p>{@code attempt} is the fencing token described in docs/DESIGN.md
 * section 6.3: it increases on every reassignment of this {@code taskId}.
 * The worker must echo both {@code taskId} and {@code attempt} back on every
 * message about this task ({@link TaskAccept}, {@link Progress},
 * {@link TaskDone}, ...); if the server sees a stale {@code attempt} (e.g.
 * from a worker that was presumed dead and reconnects late) it rejects the
 * message and any files it carries.
 *
 * <p>{@code parameters}, {@code downloadUrls} and {@code uploadUrls} are
 * intentionally untyped maps instead of a {@code TRANSCRIBE}/{@code
 * RENDER_CLIP}-specific record: the two task types need different keys (see
 * docs/DESIGN.md section 9), and keeping that shape out of {@code protocol}
 * avoids this module changing every time a task payload gains a field. The
 * worker-side task handler for each {@link TaskType} is the place to define
 * and validate the exact keys it expects.
 *
 * @param taskId       identifies the task row in the database
 * @param attempt      fencing token - see above
 * @param type         {@link TaskType#TRANSCRIBE} or {@link TaskType#RENDER_CLIP}
 * @param parameters   task-type-specific parameters (e.g. language, fps mode)
 * @param downloadUrls signed URLs the worker must fetch before starting, keyed
 *                     by a logical name (e.g. {@code "audioChunk"}, {@code "sourceClip"})
 * @param uploadUrls   signed URLs the worker must upload its output to, keyed
 *                     the same way
 */
public record TaskAssign(
        UUID taskId,
        int attempt,
        TaskType type,
        Map<String, Object> parameters,
        Map<String, String> downloadUrls,
        Map<String, String> uploadUrls
) implements ProtocolMessage {
}
