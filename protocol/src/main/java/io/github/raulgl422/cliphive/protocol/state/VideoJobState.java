package io.github.raulgl422.cliphive.protocol.state;

/**
 * Lifecycle of a submitted video link, from the moment it's pasted into the
 * superadmin to either completion or a terminal failure.
 *
 * <p>Normal path: {@code SUBMITTED -> VALIDATING -> DOWNLOADING -> TRANSCRIBING
 * -> ANALYZING -> [AWAITING_REVIEW | AWAITING_SELECTION] -> RENDERING -> RENDERED -> SCHEDULED -> PUBLISHING
 * -> COMPLETED}. {@link #AWAITING_REVIEW} only happens when review mode is on
 * for that job. {@link #REJECTED}, {@link #FAILED} and {@link #CANCELLED} are
 * terminal states reachable from most points in the path.
 *
 * <p>This state is a <b>derived summary</b> of the job's clips, not something
 * updated by hand (e.g. {@code PUBLISHING} while any clip still has a pending
 * upload) - see docs/DESIGN.md section 22.3.
 *
 * <p>See docs/DESIGN.md section 5.1 (state table) and section 4 (pipeline steps).
 */
public enum VideoJobState {
    /** Link just submitted from the superadmin, not yet processed. */
    SUBMITTED,
    /** Running {@code yt-dlp --dump-json --simulate} to check it can be downloaded. */
    VALIDATING,
    /** Terminal: validation failed (unsupported site, private, live, DRM, over 2h...). */
    REJECTED,
    /** Downloading the source video with {@code yt-dlp}. */
    DOWNLOADING,
    /** Worker is transcribing the audio chunks with whisper.cpp. */
    TRANSCRIBING,
    /** Server is calling Claude to find useful content and propose clips. */
    ANALYZING,
    /** Review mode only: proposed cuts are waiting for approval/editing in the superadmin. */
    AWAITING_REVIEW,
    /**
     * Manual clip mode only: transcription is done but the clip ranges marked
     * in the superadmin haven't been confirmed yet.
     */
    AWAITING_SELECTION,
    /** Workers are rendering the planned clips. */
    RENDERING,
    /**
     * Every clip reached a final render state (rendered, failed or cancelled);
     * waiting for the scheduler to create a publication plan.
     */
    RENDERED,
    /** Clips are rendered and have a publication plan (immediate or Claude-scheduled). */
    SCHEDULED,
    /** At least one clip has a publication in flight. */
    PUBLISHING,
    /** Terminal: every clip reached a final state (published, cleaned, or permanently failed). */
    COMPLETED,
    /** Terminal: unrecoverable error outside the normal flow. */
    FAILED,
    /** Terminal: cancelled from the superadmin. */
    CANCELLED
}
