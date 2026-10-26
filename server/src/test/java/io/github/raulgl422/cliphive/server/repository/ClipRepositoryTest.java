package io.github.raulgl422.cliphive.server.repository;

import io.github.raulgl422.cliphive.server.model.Clip;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("embedded")
class ClipRepositoryTest {

    @Autowired ClipRepository clipRepository;
    @Autowired VideoJobRepository videoJobRepository;

    @AfterEach
    void tearDown() {
        clipRepository.deleteAll();
        videoJobRepository.deleteAll();
    }

    @Test
    void unique_video_job_clip_index_rejects_duplicate() {
        var job = videoJobRepository.save(new VideoJob("https://x.com/v", "es", null, false));
        clipRepository.save(new Clip(job, 0, List.of()));

        assertThatThrownBy(() -> clipRepository.saveAndFlush(new Clip(job, 0, List.of())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void clips_with_same_index_on_different_jobs_are_allowed() {
        var job1 = videoJobRepository.save(new VideoJob("https://x.com/v1", "es", null, false));
        var job2 = videoJobRepository.save(new VideoJob("https://x.com/v2", "es", null, false));

        clipRepository.save(new Clip(job1, 0, List.of()));
        clipRepository.save(new Clip(job2, 0, List.of())); // same index, different job — OK
    }
}
