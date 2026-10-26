package io.github.raulgl422.cliphive.worker.task;

import io.github.raulgl422.cliphive.protocol.message.Progress;
import io.github.raulgl422.cliphive.protocol.message.TaskAssign;
import io.github.raulgl422.cliphive.protocol.state.TaskType;
import io.github.raulgl422.cliphive.worker.net.ServerConnection;
import io.github.raulgl422.cliphive.worker.runtime.WorkerController;
import io.github.raulgl422.cliphive.worker.runtime.WorkerState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * The orchestrator must tell the controller (and so the window) when a task
 * starts, how it progresses and when it ends — the window used to show
 * "waiting for tasks" while whisper was running.
 */
class TaskOrchestratorTest {

    @Test
    void task_start_progress_and_end_reach_the_controller() throws Exception {
        WorkerController controller = new WorkerController();
        controller.onConnected(); // IDLE
        List<WorkerController.TaskStatus> statuses = new CopyOnWriteArrayList<>();
        controller.addTaskListener(statuses::add);

        TranscribeHandler transcribe = mock(TranscribeHandler.class);
        RenderHandler render = mock(RenderHandler.class);
        ServerConnection conn = mock(ServerConnection.class);
        TaskOrchestrator orchestrator =
                new TaskOrchestrator(transcribe, render, controller);

        UUID taskId = UUID.randomUUID();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        doAnswer(inv -> {
            running.countDown();
            // While "transcribing", the handler reports progress through the connection,
            // which forwards it to onOutgoing (wired by WorkerApplication).
            orchestrator.onOutgoing(new Progress(taskId, 1, 42, "transcribing"));
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(transcribe).handle(any(), any());

        orchestrator.onTaskAssign(new TaskAssign(taskId, 1, TaskType.TRANSCRIBE,
                Map.of(), Map.of(), Map.of()), conn);

        assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(controller.getState()).isEqualTo(WorkerState.WORKING);

        release.countDown();
        long deadline = System.currentTimeMillis() + 5_000;
        while (controller.getState() != WorkerState.IDLE && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        assertThat(controller.getState()).isEqualTo(WorkerState.IDLE);
        assertThat(statuses).contains(new WorkerController.TaskStatus("Transcribiendo · whisper", 42));
        assertThat(statuses.getLast()).isEqualTo(WorkerController.TaskStatus.NONE);
    }

    @Test
    void a_new_attempt_of_a_running_task_stops_the_old_run_and_keeps_the_new_one() throws Exception {
        WorkerController controller = new WorkerController();
        controller.onConnected();
        TranscribeHandler transcribe = mock(TranscribeHandler.class);
        ServerConnection conn = mock(ServerConnection.class);
        TaskOrchestrator orchestrator = new TaskOrchestrator(transcribe, mock(RenderHandler.class), controller);

        UUID taskId = UUID.randomUUID();
        CountDownLatch firstRunning = new CountDownLatch(1);
        CountDownLatch firstStopped = new CountDownLatch(1);
        CountDownLatch secondRunning = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        doAnswer(inv -> {
            TaskAssign assign = inv.getArgument(0);
            if (assign.attempt() == 1) {
                firstRunning.countDown();
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    firstStopped.countDown();
                }
            } else {
                secondRunning.countDown();
                releaseSecond.await(5, TimeUnit.SECONDS);
            }
            return null;
        }).when(transcribe).handle(any(), any());

        orchestrator.onTaskAssign(new TaskAssign(taskId, 1, TaskType.TRANSCRIBE, Map.of(), Map.of(), Map.of()), conn);
        assertThat(firstRunning.await(5, TimeUnit.SECONDS)).isTrue();
        orchestrator.onTaskAssign(new TaskAssign(taskId, 2, TaskType.TRANSCRIBE, Map.of(), Map.of(), Map.of()), conn);

        assertThat(firstStopped.await(5, TimeUnit.SECONDS)).as("the old run is interrupted").isTrue();
        assertThat(secondRunning.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(200);   // the old run's cleanup has finished by now
        assertThat(controller.getState()).as("the new run still counts as active").isEqualTo(WorkerState.WORKING);
        releaseSecond.countDown();
    }
}
