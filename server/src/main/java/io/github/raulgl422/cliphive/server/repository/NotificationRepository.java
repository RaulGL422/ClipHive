package io.github.raulgl422.cliphive.server.repository;

import io.github.raulgl422.cliphive.server.model.Notification;
import io.github.raulgl422.cliphive.server.model.enums.NotificationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** Most recent notification of this type - to check if one already fired for the current threshold cross (DESIGN.md §12.3). */
    Optional<Notification> findTopByTypeOrderBySentAtDesc(String type);

    /** Most recent one of this type that actually went out — for the reminder interval. */
    Optional<Notification> findTopByTypeAndStatusOrderBySentAtDesc(String type, NotificationStatus status);
}
