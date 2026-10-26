package io.github.raulgl422.cliphive.server.repository;

import io.github.raulgl422.cliphive.server.model.ContentAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ContentAnalysisRepository extends JpaRepository<ContentAnalysis, UUID> {

    Optional<ContentAnalysis> findByVideoJobId(UUID videoJobId);
}
