package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.protocol.state.VideoJobState;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import io.github.raulgl422.cliphive.server.service.tool.ValidationResult;
import io.github.raulgl422.cliphive.server.service.tool.VideoLinkValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Runs yt-dlp validation in the background (docs/DESIGN.md section 4, step 2).
 * Separated from {@link VideoJobService} so the {@link Async} proxy works correctly
 * — Spring cannot intercept self-calls within the same bean.
 */
@Service
public class VideoJobValidationService {

    private static final Logger log = LoggerFactory.getLogger(VideoJobValidationService.class);

    private final VideoJobRepository jobRepository;
    private final VideoLinkValidator validator;
    private final TransactionTemplate txTemplate;
    private final VideoJobDownloadService downloadService;
    private final SettingService settings;

    public VideoJobValidationService(VideoJobRepository jobRepository,
                                     VideoLinkValidator validator,
                                     TransactionTemplate txTemplate,
                                     VideoJobDownloadService downloadService,
                                     SettingService settings) {
        this.jobRepository = jobRepository;
        this.validator = validator;
        this.txTemplate = txTemplate;
        this.downloadService = downloadService;
        this.settings = settings;
    }

    /**
     * Validates {@code jobId} asynchronously: transitions the job through
     * VALIDATING → REJECTED (too long / not downloadable) or DOWNLOADING (success).
     * Two short transactions bracket the external yt-dlp call so the DB connection
     * is not held open for the duration of the network request.
     */
    @Async
    public void startValidation(UUID jobId) {
        try {
            validate(jobId);
        } catch (RuntimeException e) {
            // @Async would only log it and leave the job in VALIDATING forever
            log.error("Job {}: validation crashed", jobId, e);
            txTemplate.executeWithoutResult(status -> jobRepository.findById(jobId)
                    .filter(j -> !j.isTerminal())
                    .ifPresent(j -> {
                        j.fail("Unexpected error during validation: " + e);
                        jobRepository.save(j);
                    }));
        }
    }

    private void validate(UUID jobId) {
        log.debug("Starting validation for job {}", jobId);

        VideoJob job = txTemplate.execute(status -> {
            VideoJob j = jobRepository.findById(jobId)
                    .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));
            if (j.isTerminal()) return null;   // cancelled before validation started
            j.startValidating();
            return jobRepository.save(j);
        });

        if (job == null) return;

        ValidationResult result;
        try {
            result = validator.validate(job.getSourceUrl());
        } catch (Exception e) {
            log.error("Unexpected error during validation of job {}", jobId, e);
            result = new ValidationResult.Failure("Internal error: " + e.getMessage());
        }

        final ValidationResult finalResult = result;
        final int maxDuration = settings.videoMaxDurationS();   // "Duración máxima" in Ajustes
        VideoJob saved = txTemplate.execute(status -> {
            VideoJob j = jobRepository.findById(jobId)
                    .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));
            if (j.isTerminal()) {
                // Cancelled while yt-dlp was checking it: don't bring it back to life
                log.info("Job {} was {} during validation; leaving it", jobId, j.getState());
                return j;
            }
            switch (finalResult) {
                case ValidationResult.Success s -> {
                    if (s.durationSeconds() > maxDuration) {
                        int maxMin = maxDuration / 60;
                        int actualMin = s.durationSeconds() / 60;
                        j.reject("Video is too long (%d min; maximum is %d min)".formatted(actualMin, maxMin));
                        log.info("Job {} rejected: duration {} min exceeds limit {} min",
                                jobId, actualMin, maxMin);
                    } else {
                        j.startDownloading(s.title(), s.sourcePlatform(),
                                s.durationSeconds(), s.sourceFps(), s.resolution());
                        j.setSourceChannel(s.channel());
                        if (j.getAudioLanguage() != null) {
                            boolean dub = s.hasAudioIn(j.getAudioLanguage());
                            j.setDubbedAudioAvailable(dub);
                            log.info("Job {}: {} audio track {} — audio languages on offer: {}",
                                    jobId, j.getAudioLanguage(), dub ? "found" : "not found, using the original",
                                    s.audioLanguages());
                        }
                        log.info("Job {} validated: '{}' ({} s, {})",
                                jobId, s.title(), s.durationSeconds(), s.sourcePlatform());
                    }
                }
                case ValidationResult.Failure f -> {
                    j.reject(f.reason());
                    log.info("Job {} rejected: {}", jobId, f.reason());
                }
            }
            return jobRepository.save(j);
        });

        // If the job transitioned to DOWNLOADING, kick off the async download pipeline.
        if (saved != null && saved.getState() == VideoJobState.DOWNLOADING) {
            downloadService.startDownload(jobId);
        }
    }
}
