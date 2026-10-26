package io.github.raulgl422.cliphive.server.model;

import io.github.raulgl422.cliphive.protocol.state.VideoJobState;
import io.github.raulgl422.cliphive.server.model.enums.ClipMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A submitted video link and everything learned about it - docs/DESIGN.md
 * sections 4 and 5.1. {@link #state} is {@code protocol}'s own
 * {@link VideoJobState} - the same enum the state machine is specified with,
 * not a server-local copy of it.
 *
 * <p>{@link #sourcePlatform} (YouTube/Twitch/Kick...) is the origin site -
 * unrelated to {@link io.github.raulgl422.cliphive.server.model.enums.Platform}
 * (TikTok/Instagram/YouTube), which is about where clips get <i>published</i>,
 * despite the overlap on YouTube.
 */
@Entity
@Table(name = "video_job")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VideoJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "source_url", nullable = false)
    private String sourceUrl;

    @Column(name = "source_platform")
    private String sourcePlatform;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private VideoJobState state;

    private String language;

    @Column(name = "fps_mode")
    private String fpsMode;

    @Column(name = "review_mode", nullable = false)
    private boolean reviewMode;

    /** Who picks the clips (DESIGN.md §4.1). */
    @Enumerated(EnumType.STRING)
    @Column(name = "clip_mode", nullable = false)
    private ClipMode clipMode = ClipMode.AUTO;

    /** Manual mode: ranges marked in the superadmin, {@code [{startMs, endMs}, …]}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "manual_ranges_json")
    private List<Map<String, Object>> manualRangesJson;

    /** Channel or author of the original video, from yt-dlp; credited in descriptions (DESIGN.md §10.3). */
    @Column(name = "source_channel")
    private String sourceChannel;

    /** The playlist holding this video's clips, by platform name (DESIGN.md §11.2); null if none yet. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "playlist_ids_json")
    private Map<String, String> playlistIdsJson;

    /** Language of the dubbed audio track to use, or null for the original audio (DESIGN.md §4). */
    @Column(name = "audio_language")
    private String audioLanguage;

    /** Whether the source offers audio in {@link #audioLanguage}; null until validated. */
    @Column(name = "dubbed_audio_available")
    private Boolean dubbedAudioAvailable;

    /** Manual mode: the ranges are final and can become clips. */
    @Column(name = "selection_confirmed", nullable = false)
    private boolean selectionConfirmed;

    @Column(nullable = false)
    private int priority;

    private String title;

    private String category;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "source_fps")
    private Integer sourceFps;

    private String resolution;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "file_hash")
    private String fileHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public VideoJob(String sourceUrl, String language, String fpsMode, boolean reviewMode) {
        this.sourceUrl = sourceUrl;
        this.language = language;
        this.fpsMode = fpsMode;
        this.reviewMode = reviewMode;
        this.state = VideoJobState.SUBMITTED;
        this.priority = 0;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    /** Waiting for the manual clip ranges to be confirmed (DESIGN.md §4.1). */
    public void awaitingSelection() {
        setState(VideoJobState.AWAITING_SELECTION);
    }

    public boolean isManual() {
        return clipMode == ClipMode.MANUAL;
    }

    public void setState(VideoJobState state) {
        this.state = state;
        this.updatedAt = Instant.now();
    }

    public void startValidating() {
        setState(VideoJobState.VALIDATING);
    }

    public void reject(String reason) {
        this.rejectionReason = reason;
        setState(VideoJobState.REJECTED);
    }

    /**
     * Transitions to DOWNLOADING and stores the metadata learned from yt-dlp validation
     * (docs/DESIGN.md section 4, step 2 → step 3).
     */
    public void startDownloading(String title, String sourcePlatform,
                                  int durationSeconds, Integer sourceFps, String resolution) {
        this.title = title;
        this.sourcePlatform = sourcePlatform;
        this.durationSeconds = durationSeconds;
        this.sourceFps = sourceFps;
        this.resolution = resolution;
        setState(VideoJobState.DOWNLOADING);
    }

    /**
     * Transitions to TRANSCRIBING after a successful download + audio extraction
     * (docs/DESIGN.md section 4, steps 3-4).
     */
    public void finishDownloading(String fileHash) {
        this.fileHash = fileHash;
        setState(VideoJobState.TRANSCRIBING);
    }

    /**
     * Pauses in AWAITING_REVIEW when review mode is on and Claude has proposed
     * cuts (docs/DESIGN.md §5.1). The admin approves via the superadmin review
     * screen, which transitions back to ANALYZING so RenderTriggerScheduler
     * picks the job up and RenderSetupService carries on from cutting the clips.
     */
    public void awaitingReview() {
        setState(VideoJobState.AWAITING_REVIEW);
    }

    /**
     * Transitions to RENDERING once the clips are planned, cut and titled
     * (docs/DESIGN.md §4 steps 8-10).
     */
    public void startRendering() {
        setState(VideoJobState.RENDERING);
    }

    /** Every clip reached a final render state (DESIGN.md §5.1). */
    public void finishRendering() {
        setState(VideoJobState.RENDERED);
    }

    /**
     * Transitions to FAILED. Reuses {@link #rejectionReason} to store the cause —
     * both REJECTED and FAILED are terminal states where the field means "why it stopped".
     */
    public void fail(String reason) {
        this.rejectionReason = reason;
        setState(VideoJobState.FAILED);
    }

    /** Cancelled from the superadmin (terminal). */
    public void cancel() {
        this.rejectionReason = "Cancelado desde el superadmin";
        setState(VideoJobState.CANCELLED);
    }

    /** FAILED, CANCELLED, REJECTED or COMPLETED — nothing more will happen to it. */
    public boolean isTerminal() {
        return switch (state) {
            case FAILED, CANCELLED, REJECTED, COMPLETED -> true;
            default -> false;
        };
    }
}
