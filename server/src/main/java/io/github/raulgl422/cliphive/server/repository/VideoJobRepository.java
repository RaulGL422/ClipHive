package io.github.raulgl422.cliphive.server.repository;

import io.github.raulgl422.cliphive.protocol.state.VideoJobState;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface VideoJobRepository extends JpaRepository<VideoJob, UUID> {

    List<VideoJob> findByState(VideoJobState state);

    /**
     * Sets only the playlist ids (DESIGN.md §11.2), so it can't overwrite a state
     * change another thread made to the job meanwhile.
     */
    @Modifying
    @Query("UPDATE VideoJob j SET j.playlistIdsJson = :ids WHERE j.id = :id")
    int updatePlaylistIds(@Param("id") UUID id, @Param("ids") Map<String, String> ids);
}
