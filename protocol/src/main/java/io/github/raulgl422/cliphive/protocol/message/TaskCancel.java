package io.github.raulgl422.cliphive.protocol.message;

import java.util.UUID;

/**
 * Server -&gt; Worker. Cancels a task from the superadmin. Unlike the
 * worker-originated messages this carries no {@code attempt}: the server is
 * telling the worker to stop whatever attempt is currently running,
 * regardless of its number.
 */
public record TaskCancel(UUID taskId) implements ProtocolMessage {
}
