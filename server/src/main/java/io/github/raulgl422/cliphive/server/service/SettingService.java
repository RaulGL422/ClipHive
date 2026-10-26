package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.server.model.Setting;
import io.github.raulgl422.cliphive.server.model.enums.PublishMode;
import io.github.raulgl422.cliphive.server.repository.SettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Typed read/write access to the {@code setting} table — hot-editable runtime
 * config for the superadmin (docs/DESIGN.md §12.2). Secrets never live here;
 * they stay in environment variables ({@link io.github.raulgl422.cliphive.server.properties.ClipHiveProperties}).
 */
@Service
public class SettingService {

    // ── Known setting keys ────────────────────────────────────────────────────

    public static final String DEFAULT_LANGUAGE        = "default.language";
    public static final String DEFAULT_FPS_MODE        = "default.fps-mode";
    public static final String DEFAULT_REVIEW_MODE     = "default.review-mode";
    public static final String PUBLISH_MODE            = "publish.mode";
    public static final String TRANSCRIPT_CORRECT      = "transcript.correct";
    public static final String CLIP_MIN_MS             = "clip.min-ms";
    public static final String CLIP_MAX_MS             = "clip.max-ms";
    public static final String VIDEO_MAX_DURATION_S    = "video.max-duration-s";
    public static final String PUBLISH_MIN_GAP_MINUTES = "publish.min-gap-minutes";
    public static final String YOUTUBE_AUDITED         = "youtube.audited";
    public static final String SUBTITLES_BOTTOM_PERCENT = "subtitles.bottom-percent";
    public static final String OVERLAY_ENABLED         = "overlay.enabled";
    public static final String CLIPS_COVERAGE          = "clips.coverage";
    public static final String FILES_RETENTION_DAYS    = "files.retention-days";
    public static final String LOGS_RETENTION_DAYS     = "logs.retention-days";
    public static final String PUBLISH_DEFAULT_SLOTS   = "publish.default-slots";
    public static final String PUBLISH_CLIPS_PER_DAY   = "publish.clips-per-day";
    public static final String PUBLISH_MAX_CLIPS_PER_DAY = "publish.max-clips-per-day";
    public static final String PUBLISH_BACKLOG_DAYS    = "publish.backlog-days";
    public static final String PUBLISH_AUDIENCE        = "publish.audience";
    public static final String PUBLISH_PLAYLISTS       = "publish.playlists";
    public static final String ALERT_QUEUE_THRESHOLD   = "alert.queue-threshold";
    public static final String ALERT_EMAIL             = "alert.email";
    public static final String ALERT_REMINDER_HOURS    = "alert.reminder-hours";
    public static final String TASKS_STALL_MINUTES     = "tasks.stall-minutes";

    private final SettingRepository settingRepository;

    public SettingService(SettingRepository settingRepository) {
        this.settingRepository = settingRepository;
    }

    // ── Generic accessors ─────────────────────────────────────────────────────

    public String get(String key, String defaultValue) {
        return settingRepository.findById(key)
                .map(Setting::getValue)
                .orElse(defaultValue);
    }

    public Optional<String> get(String key) {
        return settingRepository.findById(key).map(Setting::getValue);
    }

    @Transactional
    public void set(String key, String value) {
        settingRepository.findById(key)
                .ifPresentOrElse(
                        s -> { s.setValue(value); settingRepository.save(s); },
                        () -> settingRepository.save(new Setting(key, value)));
    }

    /** The fps modes the worker understands (§9.4). */
    public static final Set<String> FPS_MODES = Set.of("auto", "30", "60");

    /** {@code raw} when it's an fps mode, else {@code fallback}. */
    public static String fpsModeOr(String raw, String fallback) {
        String v = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        return FPS_MODES.contains(v) ? v : fallback;
    }

    // ── Typed convenience getters ─────────────────────────────────────────────

    public String defaultLanguage()    { return get(DEFAULT_LANGUAGE, "es"); }
    public String defaultFpsMode()     { return fpsModeOr(get(DEFAULT_FPS_MODE, "auto"), "auto"); }
    public boolean defaultReviewMode() { return Boolean.parseBoolean(get(DEFAULT_REVIEW_MODE, "false")); }
    /**
     * A value that isn't a mode (an old typo, stray spaces) falls back to
     * immediate: matching it as a string, it was neither and nothing got scheduled.
     */
    public PublishMode publishMode()   { return PublishMode.parse(get(PUBLISH_MODE, "")).orElse(PublishMode.IMMEDIATE); }
    /** Claude fixes misheard words in the transcript (DESIGN.md §10.5). On by default. */
    public boolean correctTranscript() { return Boolean.parseBoolean(get(TRANSCRIPT_CORRECT, "true")); }
    public long clipMinMs()            { return Long.parseLong(get(CLIP_MIN_MS, "15000")); }
    public long clipMaxMs()            { return Long.parseLong(get(CLIP_MAX_MS, "180000")); }
    public int videoMaxDurationS()     { return Integer.parseInt(get(VIDEO_MAX_DURATION_S, "7200")); }
    /** Subtitle height: distance from the bottom, % of the frame (DESIGN.md §9.2), 5–60. */
    public int subtitlesBottomPercent() {
        try {
            return Math.clamp(Integer.parseInt(get(SUBTITLES_BOTTOM_PERCENT, "25").trim()), 5, 60);
        } catch (NumberFormatException e) {
            return 25;
        }
    }
    /** "completa" (every part of the video) or "destacados" (only the best sections) — DESIGN.md §10.2. */
    public String clipsCoverage()      { return get(CLIPS_COVERAGE, "completa"); }
    /** Days a failed publication's file (or an orphan folder) is kept — DESIGN.md §15. */
    public int filesRetentionDays()    { return positive(FILES_RETENTION_DAYS, 7); }
    /** Days logs are kept — DESIGN.md §15, R15. */
    public int logsRetentionDays()     { return positive(LOGS_RETENTION_DAYS, 30); }

    private int positive(String key, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(get(key, String.valueOf(fallback)).trim()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
    /** Burn the on-screen title on top of each clip (DESIGN.md §9.2). */
    public boolean overlayEnabled()    { return Boolean.parseBoolean(get(OVERLAY_ENABLED, "true")); }
    public boolean youtubeAudited()    { return Boolean.parseBoolean(get(YOUTUBE_AUDITED, "false")); }
    public int publishMinGapMinutes()  { return Integer.parseInt(get(PUBLISH_MIN_GAP_MINUTES, "90")); }
    public String publishDefaultSlots() { return get(PUBLISH_DEFAULT_SLOTS, "13:00,20:00"); }
    /** Normal pace: clips a day when the queue isn't long (DESIGN.md §10.4). */
    public int publishClipsPerDay()    { return positive(PUBLISH_CLIPS_PER_DAY, 3); }
    /** Accelerated pace, when the queue lasts more than {@link #publishBacklogDays()} days. */
    public int publishMaxClipsPerDay() { return Math.max(publishClipsPerDay(), positive(PUBLISH_MAX_CLIPS_PER_DAY, 6)); }
    public int publishBacklogDays()    { return positive(PUBLISH_BACKLOG_DAYS, 5); }
    /** Who the channel is for, so Claude picks the right hours (DESIGN.md §10.4). */
    public String publishAudience() {
        String a = get(PUBLISH_AUDIENCE, "").strip();
        return a.isEmpty() ? DEFAULT_AUDIENCE : a;
    }
    public static final String DEFAULT_AUDIENCE = "España (hora peninsular), público hispanohablante general";
    /** A playlist per source video on platforms that have them (DESIGN.md §11.2). On by default. */
    public boolean publishPlaylists()  { return Boolean.parseBoolean(get(PUBLISH_PLAYLISTS, "true")); }
    public int alertQueueThreshold()   { return Integer.parseInt(get(ALERT_QUEUE_THRESHOLD, "5")); }
    public String alertEmail()         { return get(ALERT_EMAIL, ""); }
    public int alertReminderHours()    { return Integer.parseInt(get(ALERT_REMINDER_HOURS, "0")); }
    /** Minutes without PROGRESS before a task counts as stuck (§6.5). A render sends none while ffmpeg runs. */
    public int tasksStallMinutes()     { return positive(TASKS_STALL_MINUTES, 30); }
}
