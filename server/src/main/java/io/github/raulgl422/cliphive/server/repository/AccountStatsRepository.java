package io.github.raulgl422.cliphive.server.repository;

import io.github.raulgl422.cliphive.server.model.AccountStats;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountStatsRepository extends JpaRepository<AccountStats, UUID> {

    List<AccountStats> findBySocialAccountIdAndCapturedAtAfterOrderByCapturedAtAsc(UUID accountId, Instant after);

    Optional<AccountStats> findTopBySocialAccountIdOrderByCapturedAtDesc(UUID accountId);

    /** The last snapshot taken before {@code before} — the base for "change over 7 days". */
    Optional<AccountStats> findTopBySocialAccountIdAndCapturedAtBeforeOrderByCapturedAtDesc(UUID accountId, Instant before);
}
