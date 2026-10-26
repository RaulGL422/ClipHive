package io.github.raulgl422.cliphive.server.repository;

import io.github.raulgl422.cliphive.server.model.Transcript;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TranscriptRepository extends JpaRepository<Transcript, UUID> {

    Optional<Transcript> findByVideoJobId(UUID videoJobId);
}
