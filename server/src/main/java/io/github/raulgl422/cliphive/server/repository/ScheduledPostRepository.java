package io.github.raulgl422.cliphive.server.repository;

import io.github.raulgl422.cliphive.protocol.state.ScheduledPostState;
import io.github.raulgl422.cliphive.server.model.ScheduledPost;
import io.github.raulgl422.cliphive.server.model.enums.Platform;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ScheduledPostRepository extends JpaRepository<ScheduledPost, UUID> {

    List<ScheduledPost> findByClipId(UUID clipId);

    List<ScheduledPost> findByClipVideoJobId(UUID videoJobId);

    List<ScheduledPost> findByState(ScheduledPostState state);

    /** Publications due now, oldest first. */
    List<ScheduledPost> findByStateAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
            ScheduledPostState state, Instant now);

    /** An earlier clip of the job has a publication on {@code platform} in {@code states} (DESIGN.md §11.3). */
    @Query("""
            SELECT COUNT(p) > 0 FROM ScheduledPost p
            WHERE p.clip.videoJob.id = :jobId AND p.platform = :platform
              AND p.clip.clipIndex < :clipIndex AND p.state IN :states
            """)
    boolean existsEarlierInStates(@Param("jobId") UUID jobId, @Param("platform") Platform platform,
                                  @Param("clipIndex") int clipIndex,
                                  @Param("states") Collection<ScheduledPostState> states);

    /**
     * Publications in {@code states} scheduled or published in {@code [from, to)} —
     * what's already taken today for the planner (DESIGN.md §10.4).
     */
    @Query("""
            SELECT p FROM ScheduledPost p
            WHERE p.state IN :states
              AND ((p.scheduledAt >= :from AND p.scheduledAt < :to)
                OR (p.publishedAt >= :from AND p.publishedAt < :to))
            """)
    List<ScheduledPost> findInStatesBetween(@Param("states") Collection<ScheduledPostState> states,
                                            @Param("from") Instant from, @Param("to") Instant to);

    /** Publications published since {@code since}, for the planner's history. */
    List<ScheduledPost> findByStateAndPublishedAtAfter(ScheduledPostState state, Instant since);

    /** Distinct clips with a publication live since {@code since} — the publishing rate (DESIGN.md §12.3). */
    @Query("""
            SELECT COUNT(DISTINCT p.clip.id) FROM ScheduledPost p
            WHERE p.state = io.github.raulgl422.cliphive.protocol.state.ScheduledPostState.PUBLISHED
              AND p.publishedAt >= :since
            """)
    long countClipsPublishedSince(@Param("since") Instant since);

    /** Publications in the given states with their clip and job loaded, for the superadmin pages. */
    @Query("""
            SELECT p FROM ScheduledPost p JOIN FETCH p.clip c JOIN FETCH c.videoJob
            WHERE p.state IN :states ORDER BY p.scheduledAt ASC
            """)
    List<ScheduledPost> findWithClipByStateIn(@Param("states") Collection<ScheduledPostState> states);

    /** Most recently updated publications in the given states, clip and job loaded. */
    @Query("""
            SELECT p FROM ScheduledPost p JOIN FETCH p.clip c JOIN FETCH c.videoJob
            WHERE p.state IN :states ORDER BY p.updatedAt DESC
            """)
    List<ScheduledPost> findRecentWithClipByStateIn(@Param("states") Collection<ScheduledPostState> states,
                                                    Pageable page);

    /** Uploaded as a draft or private and not confirmed yet, oldest first (DESIGN.md §11.3). */
    @Query("""
            SELECT p FROM ScheduledPost p JOIN FETCH p.clip c JOIN FETCH c.videoJob
            WHERE p.state = io.github.raulgl422.cliphive.protocol.state.ScheduledPostState.PUBLISHED
              AND p.awaitingConfirmation = true
            ORDER BY p.publishedAt ASC
            """)
    List<ScheduledPost> findAwaitingConfirmation();

    /** Every publication of these jobs, clip loaded (the planner's queue, DESIGN.md §10.4). */
    @Query("""
            SELECT p FROM ScheduledPost p JOIN FETCH p.clip c
            WHERE c.videoJob.id IN :jobIds
            """)
    List<ScheduledPost> findWithClipByJobIdIn(@Param("jobIds") Collection<UUID> jobIds);

    /** Every publication of a job, clip loaded. */
    @Query("""
            SELECT p FROM ScheduledPost p JOIN FETCH p.clip c
            WHERE c.videoJob.id = :jobId ORDER BY c.clipIndex, p.platform
            """)
    List<ScheduledPost> findWithClipByJobId(@Param("jobId") UUID jobId);
}
