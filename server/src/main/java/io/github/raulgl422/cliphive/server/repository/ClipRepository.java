package io.github.raulgl422.cliphive.server.repository;

import io.github.raulgl422.cliphive.protocol.state.ClipState;
import io.github.raulgl422.cliphive.protocol.state.VideoJobState;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import io.github.raulgl422.cliphive.server.model.Clip;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ClipRepository extends JpaRepository<Clip, UUID> {

    List<Clip> findByVideoJobIdOrderByClipIndex(UUID videoJobId);

    int countByVideoJobId(UUID videoJobId);

    int countByVideoJobIdAndState(UUID videoJobId, ClipState state);

    int countByVideoJobIdAndStateIn(UUID videoJobId, Collection<ClipState> states);

    /** The publishing queue (DESIGN.md §10.4): oldest video first, then clip order. Job loaded. */
    @Query("""
            SELECT c FROM Clip c JOIN FETCH c.videoJob j
            WHERE c.state = :state AND j.state IN :jobStates
            ORDER BY j.createdAt, c.clipIndex
            """)
    List<Clip> findQueue(@Param("state") ClipState state, @Param("jobStates") Collection<VideoJobState> jobStates);

    /** Clips across every job in these states — the low-queue alert (DESIGN.md §12.3). */
    long countByStateIn(Collection<ClipState> states);
}
