package io.github.raulgl422.cliphive.server.repository;

import io.github.raulgl422.cliphive.server.model.Clip;
import io.github.raulgl422.cliphive.server.model.Task;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("embedded")
class TaskRepositoryTest {

    @Autowired TaskRepository taskRepository;
    @Autowired VideoJobRepository videoJobRepository;
    @Autowired ClipRepository clipRepository;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        taskRepository.deleteAll();
        clipRepository.deleteAll();
        videoJobRepository.deleteAll();
    }

    @Test
    void lockNextPending_returns_tasks_ordered_by_priority_then_createdAt() {
        var job = videoJobRepository.save(new VideoJob("https://x.com/v", "es", null, false));
        var lowPriority = taskRepository.save(Task.forTranscribe(job, Map.of()));
        job.setPriority(1);
        var highPriority = taskRepository.save(Task.forTranscribe(job, Map.of()));

        var locked = transactionTemplate.execute(
                status -> taskRepository.lockNextPending(2));

        assertThat(locked).isNotNull().hasSize(2);
        // lower priority value = higher urgency (ORDER BY priority ASC)
        assertThat(locked.get(0).getId()).isEqualTo(lowPriority.getId());
        assertThat(locked.get(1).getId()).isEqualTo(highPriority.getId());
    }

    @Test
    void lockNextPending_concurrent_calls_return_different_tasks() throws InterruptedException {
        var job = videoJobRepository.save(new VideoJob("https://x.com/v", "es", null, false));
        var task1 = taskRepository.save(Task.forTranscribe(job, Map.of()));
        var task2 = taskRepository.save(Task.forTranscribe(job, Map.of()));

        var t1HasLocked  = new CountDownLatch(1);
        var t2HasFinished = new CountDownLatch(1);
        var lockedByT1 = new AtomicReference<UUID>();
        var lockedByT2 = new AtomicReference<UUID>();
        var t2Error    = new AtomicReference<Throwable>();

        // T1 locks one task and holds the transaction open until T2 finishes
        var thread1 = new Thread(() -> transactionTemplate.execute(status -> {
            var locked = taskRepository.lockNextPending(1);
            lockedByT1.set(locked.get(0).getId());
            t1HasLocked.countDown();
            try { t2HasFinished.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return null;
        }));

        // T2 starts after T1 holds its lock — SKIP LOCKED must skip T1's row
        var thread2 = new Thread(() -> {
            try {
                t1HasLocked.await();
                transactionTemplate.execute(status -> {
                    var locked = taskRepository.lockNextPending(1);
                    lockedByT2.set(locked.get(0).getId());
                    return null;
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                t2Error.set(e);
            } finally {
                t2HasFinished.countDown();
            }
        });

        thread1.start();
        thread2.start();
        thread1.join(10_000);
        thread2.join(10_000);

        assertThat(t2Error.get()).isNull();
        assertThat(lockedByT1.get()).isNotNull();
        assertThat(lockedByT2.get()).isNotNull();
        assertThat(lockedByT1.get()).isNotEqualTo(lockedByT2.get());
    }

    @Test
    void lockNextPending_holds_render_tasks_until_their_job_is_rendering() {
        // Render tasks exist from clip planning on, but their video piece is cut
        // later — dispatching them early made the worker hit a 404 (DESIGN.md §6.4).
        var job = videoJobRepository.save(new VideoJob("https://x.com/v", "es", null, false));
        var clip = clipRepository.save(new Clip(job, 0, List.of()));
        var render = taskRepository.save(Task.forRenderClip(clip, Map.of()));
        jdbcTemplate.update("UPDATE video_job SET state = 'ANALYZING' WHERE id = ?", job.getId());

        var whileAnalyzing = transactionTemplate.execute(status -> taskRepository.lockNextPending(10));
        assertThat(whileAnalyzing).extracting(Task::getId).doesNotContain(render.getId());

        jdbcTemplate.update("UPDATE video_job SET state = 'RENDERING' WHERE id = ?", job.getId());

        var whileRendering = transactionTemplate.execute(status -> taskRepository.lockNextPending(10));
        assertThat(whileRendering).extracting(Task::getId).contains(render.getId());
    }

    @Test
    void lockNextPending_does_not_hold_transcribe_tasks() {
        var job = videoJobRepository.save(new VideoJob("https://x.com/v", "es", null, false));
        jdbcTemplate.update("UPDATE video_job SET state = 'TRANSCRIBING' WHERE id = ?", job.getId());
        var transcribe = taskRepository.save(Task.forTranscribe(job, Map.of()));

        var locked = transactionTemplate.execute(status -> taskRepository.lockNextPending(10));
        assertThat(locked).extracting(Task::getId).contains(transcribe.getId());
    }

    @Test
    void chk_task_exactly_one_target_rejects_task_with_both_targets() {
        var job = videoJobRepository.save(new VideoJob("https://x.com/v", "es", null, false));
        var clip = clipRepository.save(
                new Clip(job, 0, List.of()));

        assertThatThrownBy(() ->
            jdbcTemplate.update(
                "INSERT INTO task (id, type, state, priority, attempt, failure_count, disconnect_count, parameters_json, created_at, updated_at, video_job_id, clip_id) " +
                "VALUES (gen_random_uuid(), 'TRANSCRIBE', 'PENDING', 0, 1, 0, 0, '{}', NOW(), NOW(), ?, ?)",
                job.getId(), clip.getId())
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void chk_task_exactly_one_target_rejects_task_with_no_target() {
        assertThatThrownBy(() ->
            jdbcTemplate.update(
                "INSERT INTO task (id, type, state, priority, attempt, failure_count, disconnect_count, parameters_json, created_at, updated_at) " +
                "VALUES (gen_random_uuid(), 'TRANSCRIBE', 'PENDING', 0, 1, 0, 0, '{}', NOW(), NOW())")
        ).isInstanceOf(DataIntegrityViolationException.class);
    }
}
