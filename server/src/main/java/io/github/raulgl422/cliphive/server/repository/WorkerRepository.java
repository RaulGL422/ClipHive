package io.github.raulgl422.cliphive.server.repository;

import io.github.raulgl422.cliphive.server.model.Worker;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WorkerRepository extends JpaRepository<Worker, UUID> {

    Optional<Worker> findByTokenHash(String tokenHash);
}
