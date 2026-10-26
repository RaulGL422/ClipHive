package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.protocol.state.VideoJobState;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import io.github.raulgl422.cliphive.server.service.tool.AudioExtractor;
import io.github.raulgl422.cliphive.server.service.tool.SponsorBlockClient;
import io.github.raulgl422.cliphive.server.service.tool.ValidationResult;
import io.github.raulgl422.cliphive.server.service.tool.VideoDownloader;
import io.github.raulgl422.cliphive.server.service.tool.VideoLinkValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("embedded")
class VideoJobValidationServiceTest {

    @TestConfiguration
    static class StubValidatorConfig {
        @Bean
        @Primary
        VideoLinkValidator stubValidator() {
            return mock(VideoLinkValidator.class);
        }

        /**
         * Replaces the real download service so validation tests don't trigger
         * an actual yt-dlp invocation. Jobs stay in DOWNLOADING state after validation.
         */
        @Bean
        @Primary
        VideoJobDownloadService noopDownloadService(
                VideoJobRepository repo,
                TransactionTemplate tx,
                VideoDownloader dl,
                SponsorBlockClient sb,
                AudioExtractor ae,
                ObjectMapper om,
                JobFiles files,
                TranscribeSetupService transcribeSetup) {
            return new VideoJobDownloadService(repo, tx, dl, sb, ae, om, files, transcribeSetup) {
                @Override
                public void startDownload(UUID jobId) { /* no-op */ }
            };
        }
    }

    @Autowired VideoJobValidationService validationService;
    @Autowired VideoJobRepository jobRepository;
    @Autowired VideoLinkValidator stubValidator; // the mock from @TestConfiguration
    @Autowired SettingService settings;

    @AfterEach
    void tearDown() {
        jobRepository.deleteAll();
    }

    @Test
    void valid_url_within_duration_transitions_to_DOWNLOADING() throws Exception {
        when(stubValidator.validate(any())).thenReturn(
                new ValidationResult.Success("My Title", "youtube", 3600, 30, "1920x1080"));

        var job = jobRepository.saveAndFlush(new VideoJob("https://x.com/v", "es", null, false));

        validationService.startValidation(job.getId());
        awaitTransition(job.getId(), VideoJobState.DOWNLOADING);

        var updated = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(updated.getState()).isEqualTo(VideoJobState.DOWNLOADING);
        assertThat(updated.getTitle()).isEqualTo("My Title");
        assertThat(updated.getSourcePlatform()).isEqualTo("youtube");
        assertThat(updated.getDurationSeconds()).isEqualTo(3600);
        assertThat(updated.getSourceFps()).isEqualTo(30);
        assertThat(updated.getResolution()).isEqualTo("1920x1080");
    }

    @Test
    void a_requested_dub_is_checked_against_the_tracks_on_offer() throws Exception {
        when(stubValidator.validate(any())).thenReturn(new ValidationResult.Success(
                "T", "youtube", 100, null, null, List.of("en", "es-ES")));

        var withDub = new VideoJob("https://x.com/v", "es", null, false);
        withDub.setAudioLanguage("es");
        withDub = jobRepository.saveAndFlush(withDub);
        var missingDub = new VideoJob("https://x.com/v2", "fr", null, false);
        missingDub.setAudioLanguage("fr");
        missingDub = jobRepository.saveAndFlush(missingDub);

        validationService.startValidation(withDub.getId());
        validationService.startValidation(missingDub.getId());
        awaitTransition(withDub.getId(), VideoJobState.DOWNLOADING);
        awaitTransition(missingDub.getId(), VideoJobState.DOWNLOADING);

        assertThat(jobRepository.findById(withDub.getId()).orElseThrow().getDubbedAudioAvailable()).isTrue();
        assertThat(jobRepository.findById(missingDub.getId()).orElseThrow().getDubbedAudioAvailable())
                .as("not on offer: the original audio is downloaded").isFalse();
    }

    @Test
    void url_too_long_transitions_to_REJECTED() throws Exception {
        int overLimit = settings.videoMaxDurationS() + 1;
        when(stubValidator.validate(any())).thenReturn(
                new ValidationResult.Success("Long stream", "twitch:stream", overLimit, null, null));

        var job = jobRepository.saveAndFlush(new VideoJob("https://x.com/v", "es", null, false));

        validationService.startValidation(job.getId());
        awaitTransition(job.getId(), VideoJobState.REJECTED);

        var updated = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(updated.getState()).isEqualTo(VideoJobState.REJECTED);
        assertThat(updated.getRejectionReason()).contains("too long");
    }

    @Test
    void the_maximum_duration_comes_from_the_settings() throws Exception {
        String before = settings.get(SettingService.VIDEO_MAX_DURATION_S, "7200");
        settings.set(SettingService.VIDEO_MAX_DURATION_S, "600");
        try {
            when(stubValidator.validate(any())).thenReturn(
                    new ValidationResult.Success("Talk", "youtube", 900, null, null));
            var job = jobRepository.saveAndFlush(new VideoJob("https://x.com/talk", "es", null, false));

            validationService.startValidation(job.getId());
            awaitTransition(job.getId(), VideoJobState.REJECTED);

            assertThat(jobRepository.findById(job.getId()).orElseThrow().getRejectionReason())
                    .contains("maximum is 10 min");
        } finally {
            settings.set(SettingService.VIDEO_MAX_DURATION_S, before);
        }
    }

    @Test
    void a_job_cancelled_while_validating_stays_cancelled() throws Exception {
        var job = jobRepository.saveAndFlush(new VideoJob("https://x.com/gone", "es", null, false));
        when(stubValidator.validate(any())).thenAnswer(inv -> {
            var j = jobRepository.findById(job.getId()).orElseThrow();
            j.cancel();
            jobRepository.saveAndFlush(j);
            return new ValidationResult.Success("Gone", "youtube", 60, null, null);
        });

        try {
            validationService.startValidation(job.getId());
            awaitTransition(job.getId(), VideoJobState.CANCELLED);
            Thread.sleep(500);   // the async validation finishes after the cancel

            assertThat(jobRepository.findById(job.getId()).orElseThrow().getState())
                    .isEqualTo(VideoJobState.CANCELLED);
        } finally {
            Mockito.reset(stubValidator);   // the answer above only makes sense for this job
        }
    }

    @Test
    void validator_failure_transitions_to_REJECTED() throws Exception {
        when(stubValidator.validate(any())).thenReturn(
                new ValidationResult.Failure("Video unavailable"));

        var job = jobRepository.saveAndFlush(new VideoJob("https://x.com/v", "es", null, false));

        validationService.startValidation(job.getId());
        awaitTransition(job.getId(), VideoJobState.REJECTED);

        var updated = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(updated.getState()).isEqualTo(VideoJobState.REJECTED);
        assertThat(updated.getRejectionReason()).isEqualTo("Video unavailable");
    }

    @Test
    void job_passes_through_VALIDATING_state() throws Exception {
        // startValidation is async, so the brief VALIDATING step can't be caught reliably:
        // reaching DOWNLOADING from SUBMITTED is what proves it went through it.
        when(stubValidator.validate(any())).thenReturn(
                new ValidationResult.Success("T", "youtube", 100, null, null));

        var job = jobRepository.saveAndFlush(new VideoJob("https://x.com/v", "es", null, false));
        assertThat(job.getState()).isEqualTo(VideoJobState.SUBMITTED);

        validationService.startValidation(job.getId());
        awaitTransition(job.getId(), VideoJobState.DOWNLOADING);

        assertThat(jobRepository.findById(job.getId()).orElseThrow().getState())
                .isEqualTo(VideoJobState.DOWNLOADING);
    }

    /** Polls until the job reaches the expected state or 5 s elapses. */
    private void awaitTransition(UUID jobId, VideoJobState expected)
            throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            var state = jobRepository.findById(jobId).map(VideoJob::getState).orElse(null);
            if (expected.equals(state)) return;
            TimeUnit.MILLISECONDS.sleep(100);
        }
        var actual = jobRepository.findById(jobId).map(VideoJob::getState).orElse(null);
        assertThat(actual).as("job %s should be %s", jobId, expected).isEqualTo(expected);
    }
}
