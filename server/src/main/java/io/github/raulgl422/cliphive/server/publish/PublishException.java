package io.github.raulgl422.cliphive.server.publish;

import java.time.Duration;

/**
 * A publication or OAuth call failed. {@link #isRetryable()} tells the
 * scheduler whether to try again later (network, 5xx, quota) or give up
 * (the platform rejected the request) — DESIGN.md §11.3.
 */
public class PublishException extends Exception {

    private final boolean retryable;
    /** Set when a platform limit was hit: try again after this, without counting an attempt. */
    private Duration retryAfter;

    public PublishException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public PublishException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    /**
     * A platform limit was reached (daily quota, too many pending drafts): the
     * publication is postponed by {@code retryAfter} instead of failing (§11.3).
     */
    public static PublishException limitReached(String message, Duration retryAfter) {
        PublishException e = new PublishException(message, true);
        e.retryAfter = retryAfter;
        return e;
    }

    public boolean isRetryable() {
        return retryable;
    }

    /** Non-null when the publication should simply wait — see {@link #limitReached}. */
    public Duration retryAfter() {
        return retryAfter;
    }
}
