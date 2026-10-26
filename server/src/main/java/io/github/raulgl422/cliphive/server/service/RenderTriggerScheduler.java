package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.protocol.state.VideoJobState;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Polls for jobs in ANALYZING state and kicks off render setup for each
 * (docs/DESIGN.md §4, steps 5-10).
 *
 * <p>Runs every 5 s. Each eligible job goes through
 * {@link RenderSetupService#setup} one after another, on this thread: the job
 * stays ANALYZING while its setup runs, so running it in the background would
 * let the next scan start it a second time. {@code setup} handles its own state
 * transitions (ANALYZING → RENDERING or FAILED).
 *
 * <p>The {@code busy} flag prevents overlapping scans.
 */
@Component
public class RenderTriggerScheduler {

    private static final Logger log = LoggerFactory.getLogger(RenderTriggerScheduler.class);

    private final VideoJobRepository jobRepository;
    private final RenderSetupService renderSetupService;
    private final AtomicBoolean busy = new AtomicBoolean(false);

    public RenderTriggerScheduler(VideoJobRepository jobRepository,
                                  RenderSetupService renderSetupService) {
        this.jobRepository = jobRepository;
        this.renderSetupService = renderSetupService;
    }

    @Scheduled(fixedDelay = 5_000)
    public void scan() {
        if (!busy.compareAndSet(false, true)) return;
        try {
            List<VideoJob> jobs = jobRepository.findByState(VideoJobState.ANALYZING);
            for (VideoJob job : jobs) {
                log.info("Triggering render setup for job {} (ANALYZING)", job.getId());
                renderSetupService.setup(job.getId());
            }
        } finally {
            busy.set(false);
        }
    }
}
