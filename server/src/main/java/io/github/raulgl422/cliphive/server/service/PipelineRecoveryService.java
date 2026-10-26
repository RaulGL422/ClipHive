package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.protocol.state.VideoJobState;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.repository.TaskRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Picks the pipeline up where a restart left it (docs/DESIGN.md §5.1). The
 * steps that run inside the server — validating, downloading, creating the
 * transcription tasks — live only in memory, so after a restart (a deploy, the
 * Pi rebooting) their jobs would wait forever.
 *
 * <p>Only what was already in that state before this process started is
 * touched, so a job that moved in the first seconds is left alone. The later
 * steps need nothing here: ANALYZING is polled by {@link RenderTriggerScheduler},
 * publications recover on their own, and the workers keep their tasks across
 * the restart (a task nobody finishes is caught by the progress timeout).
 */
@Service
public class PipelineRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(PipelineRecoveryService.class);

    private final VideoJobRepository jobs;
    private final TaskRepository tasks;
    private final VideoJobValidationService validation;
    private final VideoJobDownloadService download;
    private final LogEventService logEvents;

    public PipelineRecoveryService(VideoJobRepository jobs,
                                   TaskRepository tasks,
                                   VideoJobValidationService validation,
                                   VideoJobDownloadService download,
                                   LogEventService logEvents) {
        this.jobs = jobs;
        this.tasks = tasks;
        this.validation = validation;
        this.download = download;
        this.logEvents = logEvents;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        recover(Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()));
    }

    /**
     * Resumes every job left half-way before {@code startedAt}. Each job is
     * handled on its own and any error is only logged: this runs on
     * ApplicationReadyEvent, where an exception would stop the server.
     */
    void recover(Instant startedAt) {
        // The async calls run them again from the start of their step: validating
        // re-asks yt-dlp, downloading overwrites the partial files. SUBMITTED jobs
        // were still queued for validation in memory.
        resume(VideoJobState.SUBMITTED, startedAt, "la cola de validación", any -> true, validation::startValidation);
        resume(VideoJobState.VALIDATING, startedAt, "la validación", any -> true, validation::startValidation);
        resume(VideoJobState.DOWNLOADING, startedAt, "la descarga", any -> true, download::startDownload);
        // With tasks the workers carry on (the progress timeout covers lost ones)
        resume(VideoJobState.TRANSCRIBING, startedAt, "la preparación de la transcripción",
                job -> tasks.countByVideoJobId(job.getId()) == 0, download::resumeTranscribeSetup);
    }

    private void resume(VideoJobState state, Instant startedAt, String step,
                        Predicate<VideoJob> needsIt, Consumer<UUID> action) {
        List<VideoJob> left;
        try {
            left = interrupted(state, startedAt);
        } catch (RuntimeException e) {
            log.error("Startup: could not look for {} jobs to resume", state, e);
            return;
        }
        for (VideoJob job : left) {
            try {
                if (!needsIt.test(job)) continue;
                log.info("Startup: resuming job {} ({})", job.getId(), state);
                logEvents.warn("trabajos", "El servidor se reinició durante " + step + ": se retoma",
                        job.getId().toString());
                action.accept(job.getId());
            } catch (RuntimeException e) {
                log.error("Startup: could not resume job {} ({})", job.getId(), state, e);
            }
        }
    }

    private List<VideoJob> interrupted(VideoJobState state, Instant startedAt) {
        return jobs.findByState(state).stream()
                .filter(j -> j.getUpdatedAt().isBefore(startedAt))
                .toList();
    }
}
