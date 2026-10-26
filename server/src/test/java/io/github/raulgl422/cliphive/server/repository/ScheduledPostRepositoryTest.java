package io.github.raulgl422.cliphive.server.repository;

import io.github.raulgl422.cliphive.server.model.Clip;
import io.github.raulgl422.cliphive.server.model.ScheduledPost;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.model.enums.Platform;
import io.github.raulgl422.cliphive.server.model.enums.ScheduledPostOrigin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("embedded")
class ScheduledPostRepositoryTest {

    @Autowired ScheduledPostRepository scheduledPostRepository;
    @Autowired ClipRepository clipRepository;
    @Autowired VideoJobRepository videoJobRepository;

    @AfterEach
    void tearDown() {
        scheduledPostRepository.deleteAll();
        clipRepository.deleteAll();
        videoJobRepository.deleteAll();
    }

    @Test
    void unique_clip_platform_rejects_duplicate() {
        var job  = videoJobRepository.save(new VideoJob("https://x.com/v", "es", null, false));
        var clip = clipRepository.save(new Clip(job, 0, List.of()));
        scheduledPostRepository.save(post(clip, Platform.TIKTOK));

        assertThatThrownBy(() ->
                scheduledPostRepository.saveAndFlush(post(clip, Platform.TIKTOK)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void same_clip_on_different_platforms_is_allowed() {
        var job  = videoJobRepository.save(new VideoJob("https://x.com/v", "es", null, false));
        var clip = clipRepository.save(new Clip(job, 0, List.of()));

        scheduledPostRepository.save(post(clip, Platform.TIKTOK));
        scheduledPostRepository.save(post(clip, Platform.INSTAGRAM));
        scheduledPostRepository.save(post(clip, Platform.YOUTUBE));
    }

    private static ScheduledPost post(Clip clip, Platform platform) {
        return new ScheduledPost(clip, platform, Instant.now(), ScheduledPostOrigin.IMMEDIATE);
    }
}
