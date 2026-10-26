package io.github.raulgl422.cliphive.server.model;

import io.github.raulgl422.cliphive.server.model.enums.NotificationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * An alert already sent - docs/DESIGN.md section 12.3 (low queue, TikTok
 * draft ready, manual upload, test email; see AlertService). Recorded so the
 * "send once on threshold cross" rule doesn't repeat and the superadmin can
 * list past notifications.
 */
@Entity
@Table(name = "notification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String recipient;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json")
    private Map<String, Object> metadataJson;

    public Notification(String type, String recipient, NotificationStatus status, Map<String, Object> metadataJson) {
        this.type = type;
        this.recipient = recipient;
        this.status = status;
        this.metadataJson = metadataJson;
        this.sentAt = Instant.now();
    }
}
