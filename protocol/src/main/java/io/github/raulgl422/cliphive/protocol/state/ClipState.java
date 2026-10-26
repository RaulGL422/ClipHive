package io.github.raulgl422.cliphive.protocol.state;

/**
 * Lifecycle of a single planned clip, from being carved out of a
 * {@link VideoJobState video job} to publication and cleanup.
 *
 * <p>Normal path: {@code PLANNED -> RENDERING -> RENDERED -> SCHEDULED ->
 * PUBLISHED -> CLEANED}. {@code PUBLISHED} means published on <b>every</b>
 * platform it targets - a clip with pending uploads on some platforms and
 * published on others stays {@code SCHEDULED}.
 *
 * <p>{@link #CLEANED} is reached once the clip file is deleted, which happens
 * as soon as no platform has a pending upload left (published, uploaded with
 * {@code publishAt}, or permanently failed) - see docs/DESIGN.md section 15.
 *
 * <p>See docs/DESIGN.md section 5.2.
 */
public enum ClipState {
    /** Clip range decided (by Claude or the provisional split at pauses), not rendered yet. */
    PLANNED,
    /** A worker is rendering this clip (trim, 9:16, subtitles, encode). */
    RENDERING,
    /** Render finished; the clip file exists and is ready to be scheduled. */
    RENDERED,
    /** Has at least one {@code scheduled_post} entry that isn't published yet. */
    SCHEDULED,
    /** Published on every platform it targets. */
    PUBLISHED,
    /** Terminal: file deleted because no platform has a pending upload left. */
    CLEANED,
    /** Terminal: render or publication failed beyond retry. */
    FAILED,
    /** Terminal: cancelled from the superadmin. */
    CANCELLED
}
