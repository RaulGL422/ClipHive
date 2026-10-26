package io.github.raulgl422.cliphive.server.publish;

import java.nio.file.Path;
import java.util.UUID;

/**
 * What to publish: the rendered clip file (for platforms the server uploads
 * to), its clip id (for platforms that fetch a signed public URL instead —
 * Instagram, DESIGN.md §11.1), the metadata and the platform's id of the
 * connected account.
 */
public record PublishItem(UUID clipId, Path file, PostMetadata metadata, String accountId) {
}
