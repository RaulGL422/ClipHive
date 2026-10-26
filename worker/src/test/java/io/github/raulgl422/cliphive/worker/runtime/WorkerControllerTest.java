package io.github.raulgl422.cliphive.worker.runtime;

import io.github.raulgl422.cliphive.worker.task.TaskOrchestrator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REJECTED (docs/DESIGN.md §8.2): the server refused the worker or the URL is
 * unusable, so the window must keep showing why while the connection retries.
 */
class WorkerControllerTest {

    @Test
    void on_rejected_goes_rejected_and_keeps_the_reason() {
        WorkerController controller = new WorkerController();
        List<WorkerState> seen = new CopyOnWriteArrayList<>();
        controller.addStateListener(seen::add);

        controller.onRejected("token desconocido");

        assertThat(controller.getState()).isEqualTo(WorkerState.REJECTED);
        assertThat(controller.getProblem()).isEqualTo("token desconocido");
        assertThat(seen).containsExactly(WorkerState.REJECTED);
    }

    @Test
    void a_retry_after_a_rejection_goes_connecting_so_a_network_failure_isnt_shown_as_the_old_rejection() {
        WorkerController controller = new WorkerController();
        controller.onRejected("token desconocido");

        controller.onConnecting();

        assertThat(controller.getState()).isEqualTo(WorkerState.CONNECTING);
    }

    @Test
    void a_pause_survives_a_reconnect() {
        WorkerController controller = new WorkerController();
        controller.wire(null, new TaskOrchestrator(null, null, controller));
        controller.onConnected();
        controller.pause();

        controller.onConnecting();   // the connection dropped
        controller.onConnected();    // and came back

        assertThat(controller.getState()).isEqualTo(WorkerState.PAUSED);
        controller.resume();
        assertThat(controller.getState()).isEqualTo(WorkerState.IDLE);
    }

    @Test
    void connecting_without_a_rejection_goes_connecting() {
        WorkerController controller = new WorkerController();

        controller.onConnecting();

        assertThat(controller.getState()).isEqualTo(WorkerState.CONNECTING);
        assertThat(controller.getProblem()).isNull();
    }

    @Test
    void a_welcome_after_a_rejection_clears_the_problem_and_goes_idle() {
        WorkerController controller = new WorkerController();
        controller.onRejected("token desconocido");
        controller.onConnecting();

        controller.onConnected();

        assertThat(controller.getState()).isEqualTo(WorkerState.IDLE);
        assertThat(controller.getProblem()).isNull();
    }

    @Test
    void a_rejection_after_stop_is_ignored() {
        WorkerController controller = new WorkerController(); // not wired: stop() has no connection to close
        controller.stop();
        List<WorkerState> seen = new CopyOnWriteArrayList<>();
        controller.addStateListener(seen::add);

        controller.onRejected("token desconocido");

        assertThat(controller.getState()).isEqualTo(WorkerState.STOPPED);
        assertThat(controller.getProblem()).isNull();
        assertThat(seen).isEmpty();
    }

    @Test
    void a_new_reason_while_rejected_notifies_the_listeners_again() {
        WorkerController controller = new WorkerController();
        List<WorkerState> seen = new CopyOnWriteArrayList<>();
        controller.addStateListener(seen::add);

        controller.onRejected("token desconocido");
        controller.onRejected("token desconocido"); // same text: nothing to redraw
        controller.onRejected("token revocado");

        assertThat(controller.getProblem()).isEqualTo("token revocado");
        assertThat(seen).containsExactly(WorkerState.REJECTED, WorkerState.REJECTED);
    }

    @Test
    void an_unusable_worker_shows_why_once_connected_instead_of_waiting_for_tasks() {
        WorkerController controller = new WorkerController();
        controller.setUnusable("no se encuentra ffmpeg");

        controller.onConnected();

        assertThat(controller.getState()).isEqualTo(WorkerState.NOT_READY);
        assertThat(controller.getProblem()).isEqualTo("no se encuentra ffmpeg");
    }
}
