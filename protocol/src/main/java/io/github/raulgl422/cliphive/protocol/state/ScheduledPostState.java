package io.github.raulgl422.cliphive.protocol.state;

/**
 * Lifecycle of one publication: a single (clip, platform) pair - a clip
 * targeting three platforms has three {@code scheduled_post} rows, each with
 * its own state and its own time.
 *
 * <p>Normal path: {@code SCHEDULED -> UPLOADING -> PROCESSING -> PUBLISHED}.
 * {@code UPLOADING} is persisted <b>before</b> calling the platform API, and
 * the platform's returned id (TikTok {@code publish_id}, Instagram container
 * id, YouTube video id) is saved as soon as it comes back - this is what
 * makes publication idempotent after a server restart mid-upload: on restart
 * the server checks the external id's status instead of re-uploading. See
 * docs/DESIGN.md section 11.3.
 *
 * <p>See docs/DESIGN.md section 5.4.
 */
public enum ScheduledPostState {
    /** Has a planned time (immediate mode or the daily Claude plan), not uploaded yet. */
    SCHEDULED,
    /** Upload in flight - persisted before the platform call, for idempotency on restart. */
    UPLOADING,
    /** Platform is processing the upload (e.g. Instagram container not yet {@code FINISHED}). */
    PROCESSING,
    /** Terminal: live on the platform. */
    PUBLISHED,
    /** Retryable: upload or processing failed; the scheduler may retry with backoff. */
    FAILED,
    /** Terminal: cancelled from the superadmin. */
    CANCELLED
}
