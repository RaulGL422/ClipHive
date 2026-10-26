package io.github.raulgl422.cliphive.protocol.message;

import java.util.UUID;

/**
 * Worker -&gt; Server. Voluntary hand-back of a task the worker isn't going to
 * finish (user hit "stop", or paused). The task returns to {@code PENDING}
 * immediately with a bumped {@code attempt}, <b>without</b> spending either
 * retry counter - docs/DESIGN.md section 6.5. This is the clean-stop path;
 * see also {@link Goodbye} for closing the whole session.
 */
public record TaskRelease(UUID taskId, int attempt) implements ProtocolMessage {
}
