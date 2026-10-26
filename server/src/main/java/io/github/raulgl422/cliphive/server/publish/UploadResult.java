package io.github.raulgl422.cliphive.server.publish;

/**
 * What the platform returned for an upload.
 *
 * @param externalId the platform's id — the idempotency key (DESIGN.md §11.3)
 * @param url        link to the publication when it can't be derived from the id, or null
 */
public record UploadResult(String externalId, String url) {
}
