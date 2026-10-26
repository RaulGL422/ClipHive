package io.github.raulgl422.cliphive.server.model.enums;

/**
 * Review state of a {@code content_analysis} row - docs/DESIGN.md section
 * 10.2. Only relevant when review mode is on for the job; {@link #NOT_NEEDED}
 * covers the (default) automatic path.
 */
public enum ReviewStatus {
    NOT_NEEDED,
    PENDING_REVIEW,
    APPROVED
}
