package io.github.raulgl422.cliphive.server.service.tool;

import java.util.List;

/**
 * Result of validating a video URL with yt-dlp (docs/DESIGN.md section 4, step 2).
 * The caller pattern-matches on the two variants; the compiler enforces exhaustiveness.
 */
public sealed interface ValidationResult {

    /**
     * The URL is valid and can be downloaded.
     *
     * @param title           video title from yt-dlp metadata
     * @param sourcePlatform  yt-dlp extractor id (e.g. "youtube", "twitch:stream")
     * @param durationSeconds video duration in seconds (0 if yt-dlp did not report one)
     * @param sourceFps       frames per second, null if not reported
     * @param resolution      "WxH" string (e.g. "1920x1080"), null if not reported
     * @param audioLanguages  languages of the audio tracks on offer (e.g. "en", "es-419"),
     *                        so a dubbed track can be picked (DESIGN.md §4 steps 1–3)
     * @param channel         channel or author of the video, null if not reported (§10.3)
     */
    record Success(
            String title,
            String sourcePlatform,
            int durationSeconds,
            Integer sourceFps,
            String resolution,
            List<String> audioLanguages,
            String channel
    ) implements ValidationResult {

        public Success(String title, String sourcePlatform, int durationSeconds,
                       Integer sourceFps, String resolution) {
            this(title, sourcePlatform, durationSeconds, sourceFps, resolution, List.of(), null);
        }

        public Success(String title, String sourcePlatform, int durationSeconds,
                       Integer sourceFps, String resolution, List<String> audioLanguages) {
            this(title, sourcePlatform, durationSeconds, sourceFps, resolution, audioLanguages, null);
        }

        /** True when a track is in {@code language} ("es" matches "es", "es-ES", "es-419"). */
        public boolean hasAudioIn(String language) {
            if (language == null || language.isBlank()) return false;
            String wanted = language.toLowerCase();
            return audioLanguages.stream().anyMatch(l -> {
                String lang = l.toLowerCase();
                return lang.equals(wanted) || lang.startsWith(wanted + "-");
            });
        }
    }

    /**
     * The URL cannot be downloaded or does not meet the constraints.
     *
     * @param reason human-readable reason shown in the superadmin UI
     */
    record Failure(String reason) implements ValidationResult {}
}
