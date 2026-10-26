package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.protocol.state.VideoJobState;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A render setup that fails ends its job as FAILED, unless the job already
 * finished meanwhile (DESIGN.md §5.1): a cancel must not turn into a failure.
 */
@SpringBootTest
@ActiveProfiles("embedded")
class RenderSetupFailureTest {

    @Autowired RenderSetupService renderSetup;
    @Autowired VideoJobRepository jobs;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        jobs.deleteAll();
    }

    @Test
    void a_failing_setup_fails_a_job_that_is_still_analyzing() {
        VideoJob job = jobInState("ANALYZING");

        renderSetup.setup(job.getId());   // no transcript chunks: stitching fails

        VideoJob after = jobs.findById(job.getId()).orElseThrow();
        assertThat(after.getState()).isEqualTo(VideoJobState.FAILED);
        assertThat(after.getRejectionReason()).startsWith("Render setup failed");
    }

    @Test
    void a_failing_setup_leaves_a_cancelled_job_cancelled() {
        VideoJob job = jobInState("CANCELLED");

        renderSetup.setup(job.getId());

        assertThat(jobs.findById(job.getId()).orElseThrow().getState()).isEqualTo(VideoJobState.CANCELLED);
    }

    private VideoJob jobInState(String state) {
        VideoJob job = jobs.save(new VideoJob("https://example.com/v", "es", "auto", false));
        jdbc.update("UPDATE video_job SET state = ? WHERE id = ?", state, job.getId());
        return job;
    }
}
