package io.github.raulgl422.cliphive.server.model;

import io.github.raulgl422.cliphive.protocol.state.TaskState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TaskTest {

    private static final int MAX_FAILURES = 3;
    private static final int MAX_DISCONNECTS = 10;

    private VideoJob videoJob;
    private Clip clip;

    @BeforeEach
    void setUp() {
        videoJob = new VideoJob("https://example.com/video", "es", null, false);
        videoJob.setPriority(7);
        clip = new Clip(videoJob, 3, List.of());
    }

    // --- factory methods ---

    @Test
    void forTranscribe_inherits_priority_from_videoJob() {
        var task = Task.forTranscribe(videoJob, Map.of());
        assertThat(task.getPriority()).isEqualTo(7);
        assertThat(task.getState()).isEqualTo(TaskState.PENDING);
        assertThat(task.getAttempt()).isEqualTo(1);
    }

    @Test
    void forRenderClip_uses_clipIndex_as_priority() {
        var task = Task.forRenderClip(clip, Map.of());
        assertThat(task.getPriority()).isEqualTo(3);
        assertThat(task.getState()).isEqualTo(TaskState.PENDING);
    }

    @Test
    void a_done_task_shows_100_percent_not_the_workers_last_95() {
        var task = Task.forRenderClip(clip, Map.of());
        task.recordProgress(95, "uploading");
        task.setState(TaskState.DONE);
        assertThat(task.getLastProgressPercent()).isEqualTo(100);
        assertThat(task.getLastProgressPhase()).isEqualTo("done");
    }

    // --- assignTo / reassign ---

    @Test
    void assignTo_transitions_to_ASSIGNED() {
        var worker = new Worker();
        var task = Task.forTranscribe(videoJob, Map.of());

        task.assignTo(worker);

        assertThat(task.getState()).isEqualTo(TaskState.ASSIGNED);
        assertThat(task.getAssignedWorker()).isSameAs(worker);
    }

    @Test
    void reassign_returns_to_PENDING_and_bumps_attempt() {
        var worker = new Worker();
        var task = Task.forTranscribe(videoJob, Map.of());
        task.assignTo(worker);

        task.reassign();

        assertThat(task.getState()).isEqualTo(TaskState.PENDING);
        assertThat(task.getAssignedWorker()).isNull();
        assertThat(task.getAttempt()).isEqualTo(2);
    }

    @Test
    void release_bumps_attempt_but_not_failure_count() {
        var task = Task.forTranscribe(videoJob, Map.of());
        task.assignTo(new Worker());

        task.release();

        assertThat(task.getState()).isEqualTo(TaskState.PENDING);
        assertThat(task.getAttempt()).isEqualTo(2);
        assertThat(task.getFailureCount()).isZero();
        assertThat(task.getDisconnectCount()).isZero();
    }

    // --- recordFailure ---

    @Test
    void recordFailure_below_max_reassigns_and_increments_count() {
        var task = Task.forTranscribe(videoJob, Map.of());
        task.assignTo(new Worker());

        task.recordFailure(MAX_FAILURES);

        assertThat(task.getState()).isEqualTo(TaskState.PENDING);
        assertThat(task.getFailureCount()).isEqualTo(1);
        assertThat(task.getAttempt()).isEqualTo(2);
    }

    @Test
    void recordFailure_at_max_transitions_to_FAILED() {
        var task = Task.forTranscribe(videoJob, Map.of());
        task.assignTo(new Worker());
        task.recordFailure(MAX_FAILURES);
        task.assignTo(new Worker());
        task.recordFailure(MAX_FAILURES);
        task.assignTo(new Worker());

        task.recordFailure(MAX_FAILURES);

        assertThat(task.getState()).isEqualTo(TaskState.FAILED);
        assertThat(task.getFailureCount()).isEqualTo(MAX_FAILURES);
    }

    // --- recordDisconnect ---

    @Test
    void recordDisconnect_below_max_reassigns_and_increments_count() {
        var task = Task.forTranscribe(videoJob, Map.of());
        task.assignTo(new Worker());

        task.recordDisconnect(MAX_DISCONNECTS);

        assertThat(task.getState()).isEqualTo(TaskState.PENDING);
        assertThat(task.getDisconnectCount()).isEqualTo(1);
        assertThat(task.getAttempt()).isEqualTo(2);
    }

    @Test
    void recordDisconnect_at_max_transitions_to_FAILED() {
        var task = Task.forTranscribe(videoJob, Map.of());
        for (int i = 0; i < MAX_DISCONNECTS - 1; i++) {
            task.assignTo(new Worker());
            task.recordDisconnect(MAX_DISCONNECTS);
        }
        task.assignTo(new Worker());

        task.recordDisconnect(MAX_DISCONNECTS);

        assertThat(task.getState()).isEqualTo(TaskState.FAILED);
        assertThat(task.getDisconnectCount()).isEqualTo(MAX_DISCONNECTS);
    }

    @Test
    void disconnect_and_failure_counters_are_independent() {
        var task = Task.forTranscribe(videoJob, Map.of());
        task.assignTo(new Worker());
        task.recordFailure(MAX_FAILURES);  // failure_count = 1
        task.assignTo(new Worker());
        task.recordDisconnect(MAX_DISCONNECTS);  // disconnect_count = 1

        assertThat(task.getFailureCount()).isEqualTo(1);
        assertThat(task.getDisconnectCount()).isEqualTo(1);
        assertThat(task.getState()).isEqualTo(TaskState.PENDING);
    }
}
