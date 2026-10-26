package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.protocol.state.TaskState;
import io.github.raulgl422.cliphive.protocol.state.TaskType;
import io.github.raulgl422.cliphive.protocol.state.VideoJobState;
import io.github.raulgl422.cliphive.server.model.Task;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.model.Worker;
import io.github.raulgl422.cliphive.server.repository.ClipRepository;
import io.github.raulgl422.cliphive.server.repository.TaskRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskLifecycleServiceTest {

    private TaskRepository taskRepository;
    private VideoJobRepository jobRepository;
    private TaskLifecycleService service;
    private LogEventService logEvents;

    @BeforeEach
    void setUp() {
        taskRepository = mock(TaskRepository.class);
        jobRepository = mock(VideoJobRepository.class);
        ClipRepository clipRepository = mock(ClipRepository.class);
        logEvents = mock(LogEventService.class);
        service = new TaskLifecycleService(taskRepository, jobRepository, clipRepository, logEvents);
    }

    // ── accept ───────────────────────────────────────────────────────────────

    @Test
    void accept_transitions_assigned_to_running() {
        Task task = pendingTask(TaskType.TRANSCRIBE);
        assignTask(task, new Worker("w", "h"));
        UUID taskId = task.getId();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        service.accept(taskId, task.getAttempt(), workerOf(task));

        assertThat(task.getState()).isEqualTo(TaskState.RUNNING);
        verify(taskRepository).save(task);
    }

    @Test
    void accept_is_recorded_in_the_superadmin_log() {
        Task task = pendingTask(TaskType.TRANSCRIBE);
        assignTask(task, new Worker("portatil", "h"));
        when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));

        service.accept(task.getId(), task.getAttempt(), workerOf(task));

        verify(logEvents).info(eq("tareas"), contains("portatil"), any());
    }

    @Test
    void progress_logs_phase_changes_and_quarter_milestones_only() {
        Task task = pendingTask(TaskType.TRANSCRIBE);
        assignTask(task, new Worker("w", "h"));
        when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));
        int attempt = task.getAttempt();

        service.progress(task.getId(), attempt, 5, "transcribing", workerOf(task));   // first report → logged
        service.progress(task.getId(), attempt, 10, "transcribing", workerOf(task));  // same quarter → silent
        service.progress(task.getId(), attempt, 20, "transcribing", workerOf(task));  // same quarter → silent
        service.progress(task.getId(), attempt, 26, "transcribing", workerOf(task));  // crosses 25 % → logged
        service.progress(task.getId(), attempt, 95, "uploading", workerOf(task));     // phase change → logged

        verify(logEvents, times(3)).info(eq("tareas"), anyString(), any());
        assertThat(task.getLastProgressPercent()).isEqualTo(95);
        assertThat(task.getLastProgressPhase()).isEqualTo("uploading");
    }

    @Test
    void accept_with_stale_attempt_is_ignored() {
        Task task = pendingTask(TaskType.TRANSCRIBE);
        UUID taskId = task.getId();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        service.accept(taskId, task.getAttempt() + 99, workerOf(task));

        assertThat(task.getState()).isEqualTo(TaskState.PENDING);
        verify(taskRepository, never()).save(any());
    }

    // ── reject ───────────────────────────────────────────────────────────────

    @Test
    void reject_increments_failure_count_and_reassigns() {
        Task task = pendingTask(TaskType.TRANSCRIBE);
        UUID taskId = task.getId();
        int originalAttempt = task.getAttempt();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        service.reject(taskId, originalAttempt, "model not found", workerOf(task));

        assertThat(task.getFailureCount()).isEqualTo(1);
        assertThat(task.getState()).isEqualTo(TaskState.PENDING);
        assertThat(task.getAttempt()).isEqualTo(originalAttempt + 1);
    }

    @Test
    void reject_three_times_permanently_fails() {
        Task task = pendingTask(TaskType.TRANSCRIBE);
        UUID taskId = task.getId();

        for (int i = 0; i < TaskLifecycleService.MAX_FAILURES; i++) {
            when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
            service.reject(taskId, task.getAttempt(), "oops", workerOf(task));
        }

        assertThat(task.getState()).isEqualTo(TaskState.FAILED);
        assertThat(task.getFailureCount()).isEqualTo(TaskLifecycleService.MAX_FAILURES);
    }

    // ── progress ─────────────────────────────────────────────────────────────

    @Test
    void progress_updates_percent_and_phase() {
        Task task = pendingTask(TaskType.TRANSCRIBE);
        UUID taskId = task.getId();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        service.progress(taskId, task.getAttempt(), 50, "transcribing", workerOf(task));

        assertThat(task.getLastProgressPercent()).isEqualTo(50);
        assertThat(task.getLastProgressPhase()).isEqualTo("transcribing");
    }

    // ── done ─────────────────────────────────────────────────────────────────

    @Test
    void done_marks_task_done() {
        Task task = pendingTask(TaskType.TRANSCRIBE);
        UUID taskId = task.getId();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.countByVideoJobId(any())).thenReturn(1);
        when(taskRepository.countByVideoJobIdAndState(any(), any())).thenReturn(1);
        when(jobRepository.findById(any())).thenReturn(Optional.empty());

        service.done(taskId, task.getAttempt(), workerOf(task));

        assertThat(task.getState()).isEqualTo(TaskState.DONE);
    }

    @Test
    void done_all_transcribe_chunks_advances_job_to_analyzing() {
        VideoJob job = new VideoJob("http://x", "es", null, false);
        setField(job, "id", UUID.randomUUID());
        setField(job, "state", VideoJobState.TRANSCRIBING);

        Task task = Task.forTranscribe(job, Map.of("chunkIndex", 0));
        setField(task, "id", UUID.randomUUID());

        when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));
        when(taskRepository.countByVideoJobId(job.getId())).thenReturn(1);
        when(taskRepository.countByVideoJobIdAndState(job.getId(), TaskState.DONE)).thenReturn(1);
        when(jobRepository.findById(job.getId())).thenReturn(Optional.of(job));

        service.done(task.getId(), task.getAttempt(), workerOf(task));

        assertThat(job.getState()).isEqualTo(VideoJobState.ANALYZING);
        verify(jobRepository).save(job);
    }

    @Test
    void done_partial_chunks_does_not_advance_job() {
        VideoJob job = new VideoJob("http://x", "es", null, false);
        setField(job, "id", UUID.randomUUID());
        setField(job, "state", VideoJobState.TRANSCRIBING);

        Task task = Task.forTranscribe(job, Map.of("chunkIndex", 0));
        setField(task, "id", UUID.randomUUID());

        when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));
        when(taskRepository.countByVideoJobId(job.getId())).thenReturn(3);
        when(taskRepository.countByVideoJobIdAndState(job.getId(), TaskState.DONE)).thenReturn(1);

        service.done(task.getId(), task.getAttempt(), workerOf(task));

        assertThat(job.getState()).isEqualTo(VideoJobState.TRANSCRIBING);
        verify(jobRepository, never()).save(any());
    }

    // ── fail ─────────────────────────────────────────────────────────────────

    @Test
    void fail_records_failure_and_reassigns_when_under_limit() {
        Task task = pendingTask(TaskType.TRANSCRIBE);
        UUID taskId = task.getId();
        int originalAttempt = task.getAttempt();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        service.fail(taskId, originalAttempt, "OOM", workerOf(task));

        assertThat(task.getFailureCount()).isEqualTo(1);
        assertThat(task.getState()).isEqualTo(TaskState.PENDING);
    }

    // ── release ──────────────────────────────────────────────────────────────

    @Test
    void release_reassigns_without_incrementing_failure_count() {
        Task task = pendingTask(TaskType.TRANSCRIBE);
        UUID taskId = task.getId();
        int originalAttempt = task.getAttempt();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        service.release(taskId, originalAttempt, workerOf(task));

        assertThat(task.getFailureCount()).isEqualTo(0);
        assertThat(task.getDisconnectCount()).isEqualTo(0);
        assertThat(task.getState()).isEqualTo(TaskState.PENDING);
        assertThat(task.getAttempt()).isEqualTo(originalAttempt + 1);
    }

    // ── reassignWorkerTasks ───────────────────────────────────────────────────

    @Test
    void reassign_worker_tasks_bumps_disconnect_count() {
        Task task = pendingTask(TaskType.TRANSCRIBE);
        UUID workerId = UUID.randomUUID();
        when(taskRepository.findByAssignedWorkerIdAndStateIn(
                workerId, List.of(TaskState.ASSIGNED, TaskState.RUNNING)))
                .thenReturn(List.of(task));

        service.reassignWorkerTasks(workerId);

        assertThat(task.getDisconnectCount()).isEqualTo(1);
        assertThat(task.getState()).isEqualTo(TaskState.PENDING);
    }

    @Test
    void goodbye_puts_held_tasks_back_without_spending_a_counter() {
        Task task = pendingTask(TaskType.TRANSCRIBE);
        assignTask(task, new Worker("w", "h"));
        int attempt = task.getAttempt();
        UUID workerId = UUID.randomUUID();
        when(taskRepository.findByAssignedWorkerIdAndStateIn(
                workerId, List.of(TaskState.ASSIGNED, TaskState.RUNNING)))
                .thenReturn(List.of(task));

        service.releaseWorkerTasks(workerId);

        assertThat(task.getState()).isEqualTo(TaskState.PENDING);
        assertThat(task.getAttempt()).isEqualTo(attempt + 1);
        assertThat(task.getDisconnectCount()).isZero();
        assertThat(task.getFailureCount()).isZero();
    }

    @Test
    void a_task_with_no_progress_counts_as_a_failure_and_goes_back() {
        Task task = pendingTask(TaskType.TRANSCRIBE);
        assignTask(task, new Worker("w", "h"));
        Instant cutoff = Instant.now();
        when(taskRepository.findByStateInAndUpdatedAtBefore(
                List.of(TaskState.ASSIGNED, TaskState.RUNNING), cutoff))
                .thenReturn(List.of(task));

        service.reassignStalledTasks(cutoff, Duration.ofMinutes(30));

        assertThat(task.getState()).isEqualTo(TaskState.PENDING);
        assertThat(task.getFailureCount()).isEqualTo(1);
        verify(logEvents).warn(eq("tareas"), contains("sin progreso en 30 min"), any());
    }

    @Test
    void reassign_ten_disconnects_permanently_fails() {
        Task task = pendingTask(TaskType.TRANSCRIBE);
        UUID workerId = UUID.randomUUID();

        for (int i = 0; i < TaskLifecycleService.MAX_DISCONNECTS; i++) {
            when(taskRepository.findByAssignedWorkerIdAndStateIn(
                    workerId, List.of(TaskState.ASSIGNED, TaskState.RUNNING)))
                    .thenReturn(List.of(task));
            service.reassignWorkerTasks(workerId);
        }

        assertThat(task.getDisconnectCount()).isEqualTo(TaskLifecycleService.MAX_DISCONNECTS);
        assertThat(task.getState()).isEqualTo(TaskState.FAILED);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Task pendingTask(TaskType type) {
        VideoJob job = new VideoJob("http://test", "es", null, false);
        setField(job, "id", UUID.randomUUID());
        Task task = Task.forTranscribe(job, Map.of("chunkIndex", 0));
        setField(task, "id", UUID.randomUUID());
        return task;
    }

    private static void assignTask(Task task, Worker worker) {
        task.assignTo(worker);
    }

    private static void setField(Object obj, String name, Object value) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /** The worker a message must come from: the one the task is assigned to. */
    private static UUID workerOf(Task task) {
        return task.getAssignedWorker() != null ? task.getAssignedWorker().getId() : null;
    }

    @Test
    void a_message_from_a_worker_that_doesnt_hold_the_task_is_ignored() {
        Task task = pendingTask(TaskType.TRANSCRIBE);
        Worker holder = new Worker("holder", "h");
        setField(holder, "id", UUID.randomUUID());
        assignTask(task, holder);
        when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));

        service.done(task.getId(), task.getAttempt(), UUID.randomUUID());

        assertThat(task.getState()).isNotEqualTo(TaskState.DONE);
    }
}
