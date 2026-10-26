package io.github.raulgl422.cliphive.server.repository;

import io.github.raulgl422.cliphive.server.model.LogEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LogEventRepository extends JpaRepository<LogEvent, UUID> {

    @Query("SELECT e FROM LogEvent e ORDER BY e.createdAt DESC")
    List<LogEvent> findTopN(Pageable pageable);

    /**
     * Filtered log history with optional level, component substring and jobId —
     * docs/DESIGN.md §12.2. Null/blank params are ignored (match-all).
     * Uses a native query so the JSONB jobId extraction works on PostgreSQL.
     */
    @Query(value = """
            SELECT * FROM log_event
            WHERE (:level     IS NULL OR level     = :level)
            AND   (:component IS NULL OR LOWER(component) LIKE LOWER(:component))
            AND   (:jobId     IS NULL OR context_json->>'jobId' = :jobId)
            ORDER BY created_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<LogEvent> findFiltered(@Param("level") String level,
                                @Param("component") String component,
                                @Param("jobId") String jobId,
                                @Param("limit") int limit);

    /** Deletes events older than {@code cutoff} - called by FileCleanupService's sweep (DESIGN.md §15, R15). */
    @Modifying
    @Query("DELETE FROM LogEvent e WHERE e.createdAt < :cutoff")
    int deleteByCreatedAtBefore(Instant cutoff);
}
