package io.github.raulgl422.cliphive.server.repository;

import io.github.raulgl422.cliphive.server.model.PublicationPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface PublicationPlanRepository extends JpaRepository<PublicationPlan, UUID> {

    Optional<PublicationPlan> findByPlanDate(LocalDate planDate);
}
