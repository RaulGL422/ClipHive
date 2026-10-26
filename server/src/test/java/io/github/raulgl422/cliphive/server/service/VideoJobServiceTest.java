package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.protocol.state.VideoJobState;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoJobServiceTest {

    @Mock VideoJobRepository jobRepository;
    @Mock VideoJobValidationService validationService;

    @InjectMocks VideoJobService jobService;

    @Test
    void submit_saves_job_in_SUBMITTED_state() {
        var savedJob = new VideoJob("https://x.com/v", "es", null, false);
        when(jobRepository.saveAndFlush(any())).thenReturn(savedJob);

        var result = jobService.submit("https://x.com/v", "es", null, false);

        assertThat(result.getState()).isEqualTo(VideoJobState.SUBMITTED);
        assertThat(result.getSourceUrl()).isEqualTo("https://x.com/v");
    }

    @Test
    void submit_triggers_async_validation() {
        var savedJob = new VideoJob("https://x.com/v", "es", null, false);
        var savedId = UUID.randomUUID();
        // Simulate JPA assigning an id on saveAndFlush
        when(jobRepository.saveAndFlush(any())).thenAnswer(inv -> {
            var j = (VideoJob) inv.getArgument(0);
            // Use reflection to set id (JPA normally does this)
            try {
                var field = VideoJob.class.getDeclaredField("id");
                field.setAccessible(true);
                field.set(j, savedId);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            return j;
        });

        jobService.submit("https://x.com/v", "es", null, false);

        ArgumentCaptor<UUID> idCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(validationService).startValidation(idCaptor.capture());
        assertThat(idCaptor.getValue()).isEqualTo(savedId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"--exec=calc.exe", "file:///etc/passwd", "ftp://x.com/v", "youtu.be/abc", "https://", ""})
    void submit_refuses_anything_but_an_http_link(String url) {
        assertThatThrownBy(() -> jobService.submit(url, "es", null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("http(s)");
        verifyNoInteractions(jobRepository, validationService);
    }

    @Test
    void submit_accepts_http_and_https_and_trims() {
        when(jobRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        assertThat(jobService.submit("  HTTPS://www.youtube.com/watch?v=abc ", "es", null, false).getSourceUrl())
                .isEqualTo("HTTPS://www.youtube.com/watch?v=abc");
        assertThat(jobService.submit("http://x.com/v", "es", null, false).getSourceUrl()).isEqualTo("http://x.com/v");
    }
}
