package io.github.raulgl422.cliphive.server.repository;

import io.github.raulgl422.cliphive.server.model.PostStats;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PostStatsRepository extends JpaRepository<PostStats, UUID> {

    Optional<PostStats> findTopByScheduledPostIdOrderByCapturedAtDesc(UUID scheduledPostId);

    /** The most recent snapshot of every publication. */
    @Query("""
            SELECT s FROM PostStats s
            WHERE s.capturedAt = (SELECT MAX(s2.capturedAt) FROM PostStats s2
                                  WHERE s2.scheduledPost = s.scheduledPost)
            """)
    List<PostStats> findLatestPerPost();
}
