package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.protocol.state.VideoJobState;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.repository.TaskRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineRecoveryServiceTest {

    private VideoJobRepository jobs;
    private TaskRepository tasks;
    private VideoJobValidationService validation;
    private VideoJobDownloadService download;
    private PipelineRecoveryService recovery;

    @BeforeEach
    void setUp() {
        jobs = mock(VideoJobRepository.class);
        tasks = mock(TaskRepository.class);
        validation = mock(VideoJobValidationService.class);
        download = mock(VideoJobDownloadService.class);
        recovery = new PipelineRecoveryService(jobs, tasks, validation, download,
                mock(LogEventService.class));
        when(jobs.findByState(any())).thenReturn(List.of());
    }

    @Test
    void steps_cut_short_by_a_restart_are_run_again() {
        VideoJob queued = job(VideoJobState.SUBMITTED);
        VideoJob validating = job(VideoJobState.VALIDATING);
        VideoJob downloading = job(VideoJobState.DOWNLOADING);
        VideoJob noTasks = job(VideoJobState.TRANSCRIBING);
        VideoJob withTasks = job(VideoJobState.TRANSCRIBING);
        when(jobs.findByState(VideoJobState.SUBMITTED)).thenReturn(List.of(queued));
        when(jobs.findByState(VideoJobState.VALIDATING)).thenReturn(List.of(validating));
        when(jobs.findByState(VideoJobState.DOWNLOADING)).thenReturn(List.of(downloading));
        when(jobs.findByState(VideoJobState.TRANSCRIBING)).thenReturn(List.of(noTasks, withTasks));
        when(tasks.countByVideoJobId(withTasks.getId())).thenReturn(4);
        Instant startedAt = Instant.now().plusSeconds(1);

        recovery.recover(startedAt);

        verify(validation).startValidation(queued.getId());
        verify(validation).startValidation(validating.getId());
        verify(download).startDownload(downloading.getId());
        verify(download).resumeTranscribeSetup(noTasks.getId());
        verify(download, never()).resumeTranscribeSetup(withTasks.getId());
    }

    @Test
    void one_job_that_fails_to_resume_does_not_stop_the_others_or_the_startup() {
        VideoJob broken = job(VideoJobState.DOWNLOADING);
        VideoJob fine = job(VideoJobState.DOWNLOADING);
        when(jobs.findByState(VideoJobState.DOWNLOADING)).thenReturn(List.of(broken, fine));
        doThrow(new IllegalStateException("pool full")).when(download).startDownload(broken.getId());

        recovery.recover(Instant.now().plusSeconds(1));

        verify(download).startDownload(fine.getId());
    }

    @Test
    void a_job_that_moved_after_the_start_is_left_alone() {
        VideoJob fresh = job(VideoJobState.DOWNLOADING);
        when(jobs.findByState(VideoJobState.DOWNLOADING)).thenReturn(List.of(fresh));

        recovery.recover(Instant.now().minus(Duration.ofMinutes(1)));

        verify(download, never()).startDownload(any());
    }

    private static VideoJob job(VideoJobState state) {
        VideoJob j = new VideoJob("https://example.com/v", "es", null, false);
        j.setState(state);
        try {
            Field id = VideoJob.class.getDeclaredField("id");
            id.setAccessible(true);
            id.set(j, UUID.randomUUID());
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return j;
    }
}
