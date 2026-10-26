package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.model.enums.ClipMode;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Entry point for video job lifecycle operations.
 * {@link #submit} persists the new job and hands validation off to
 * {@link VideoJobValidationService} so it runs asynchronously.
 */
@Service
public class VideoJobService {

    private final VideoJobRepository jobRepository;
    private final VideoJobValidationService validationService;

    public VideoJobService(VideoJobRepository jobRepository,
                           VideoJobValidationService validationService) {
        this.jobRepository = jobRepository;
        this.validationService = validationService;
    }

    /**
     * Creates a SUBMITTED job and kicks off async validation.
     * Uses {@code saveAndFlush} to commit before handing the id to the async thread,
     * which avoids a race where the async thread tries to load an uncommitted row.
     *
     * @param url        the video URL (any yt-dlp-supported site)
     * @param language   transcription language code (e.g. "es"), or null to inherit the default
     * @param fpsMode    fps handling mode, or null to inherit the default
     * @param reviewMode whether to pause before rendering for manual clip review
     * @return the persisted job (state = SUBMITTED; will transition to VALIDATING shortly)
     */
    public VideoJob submit(String url, String language, String fpsMode, boolean reviewMode) {
        return submit(url, language, fpsMode, reviewMode, ClipMode.AUTO);
    }

    /** @param clipMode who picks the clips: Claude ({@code AUTO}) or the superadmin ({@code MANUAL}, §4.1) */
    public VideoJob submit(String url, String language, String fpsMode, boolean reviewMode, ClipMode clipMode) {
        return submit(url, language, fpsMode, reviewMode, clipMode, null);
    }

    /**
     * @param audioLanguage a dubbed audio track to prefer ("es", "en"…), or null for the
     *                      original. When set it is also the job's language: transcription,
     *                      subtitles and titles follow the audio actually used.
     */
    public VideoJob submit(String url, String language, String fpsMode, boolean reviewMode,
                           ClipMode clipMode, String audioLanguage) {
        url = requireWebUrl(url);
        boolean dubbed = audioLanguage != null && !audioLanguage.isBlank();
        // An unknown fps mode (a typo through the JSON endpoint) would reach the worker as is
        fpsMode = fpsMode == null ? null : SettingService.fpsModeOr(fpsMode, "auto");
        var job = new VideoJob(url, dubbed ? audioLanguage : language, fpsMode, reviewMode);
        job.setClipMode(clipMode);
        job.setAudioLanguage(dubbed ? audioLanguage : null);
        job = jobRepository.saveAndFlush(job);
        validationService.startValidation(job.getId());
        return job;
    }

    /**
     * Only http(s) links with a host reach yt-dlp: anything else (a file:// path,
     * a string starting with "--" that yt-dlp would read as an option) is refused.
     *
     * @throws IllegalArgumentException with a message for the superadmin
     */
    static String requireWebUrl(String url) {
        String trimmed = url == null ? "" : url.strip();
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null) return trimmed;
        } catch (URISyntaxException ignored) {
            // falls through to the error below
        }
        throw new IllegalArgumentException("Solo se admiten enlaces http(s) a un vídeo: " + trimmed);
    }
}
