package io.github.raulgl422.cliphive.server.service.worker;

import io.github.raulgl422.cliphive.protocol.message.Ping;
import io.github.raulgl422.cliphive.protocol.message.TaskCancel;
import io.github.raulgl422.cliphive.server.service.SettingService;
import io.github.raulgl422.cliphive.server.service.TaskLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

import java.time.Duration;
import java.time.Instant;

import static io.github.raulgl422.cliphive.server.service.worker.WorkerWebSocketHandler.MAX_MISSED_PINGS;

/**
 * Sends PING frames to all connected workers every
 * {@value WorkerWebSocketHandler#PING_INTERVAL_SECONDS} seconds and
 * disconnects any worker that has missed
 * {@value WorkerWebSocketHandler#MAX_MISSED_PINGS} consecutive PONGs
 * (docs/DESIGN.md §6.5).
 *
 * <p>Algorithm (per worker, per tick):
 * <ol>
 *   <li>If {@code waitingForPong} is still true from the previous tick,
 *       increment {@code missedPings}. If it reaches {@code MAX_MISSED_PINGS},
 *       close the session — the handler's {@code afterConnectionClosed} will
 *       update the DB and unregister.
 *   <li>Send a new PING and set {@code waitingForPong = true}.
 * </ol>
 *
 * <p>A second sweep is the progress timeout: a task with no PROGRESS (or
 * other update) for {@link SettingService#tasksStallMinutes()} is stuck even
 * if its worker answers the pings. It goes back to the queue as a failure and
 * the worker is told to drop it.
 *
 * <p>{@code @EnableScheduling} lives in {@link io.github.raulgl422.cliphive.server.config.AsyncConfig}.
 */
@Component
public class HeartbeatScheduler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatScheduler.class);

    private final WorkerSessionRegistry registry;
    private final TaskLifecycleService taskLifecycle;
    private final SettingService settings;

    public HeartbeatScheduler(WorkerSessionRegistry registry,
                              TaskLifecycleService taskLifecycle,
                              SettingService settings) {
        this.registry = registry;
        this.taskLifecycle = taskLifecycle;
        this.settings = settings;
    }

    // Every PING_INTERVAL_SECONDS (docs/DESIGN.md §6.5)
    @Scheduled(fixedRate = WorkerWebSocketHandler.PING_INTERVAL_SECONDS * 1000L)
    public void tick() {
        for (ConnectedWorker cw : registry.all()) {
            if (cw.waitingForPong.get()) {
                int missed = cw.missedPings.incrementAndGet();
                if (missed >= MAX_MISSED_PINGS) {
                    log.warn("Worker {} missed {} consecutive PONGs — disconnecting",
                            cw.workerId(), missed);
                    WorkerWebSocketHandler.closeQuietly(cw.wsSession,
                            CloseStatus.SESSION_NOT_RELIABLE.withReason("heartbeat timeout"));
                    continue;
                }
            }
            cw.waitingForPong.set(true);
            cw.send(new Ping());
        }
    }

    /** Progress timeout (docs/DESIGN.md §6.5), checked once a minute. */
    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void sweepStalled() {
        Duration timeout = Duration.ofMinutes(settings.tasksStallMinutes());
        Instant cutoff = Instant.now().minus(timeout);
        // Cancel first: once requeued, the dispatcher may hand the same task to the
        // same worker, and a TASK_CANCEL (which names no attempt) would hit the new run.
        taskLifecycle.findStalledTasks(cutoff).forEach((taskId, workerId) -> {
            ConnectedWorker cw = registry.byWorkerId(workerId);
            if (cw != null) cw.send(new TaskCancel(taskId));
        });
        int requeued = taskLifecycle.reassignStalledTasks(cutoff, timeout);
        if (requeued > 0) log.warn("{} task(s) had no progress in {} min and went back to the queue",
                requeued, timeout.toMinutes());
    }
}
