package io.github.raulgl422.cliphive.server.repository;

import io.github.raulgl422.cliphive.server.model.WorkerSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WorkerSessionRepository extends JpaRepository<WorkerSession, UUID> {
}
