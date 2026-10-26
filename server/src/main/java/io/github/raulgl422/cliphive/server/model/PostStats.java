package io.github.raulgl422.cliphive.server.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
 * A periodic metrics snapshot for one publication - docs/DESIGN.md section
 * 12.2. {@link #metricsJson} has no fixed schema: each platform's
 * StatsCollector stores the counters its API exposes.
 */
@Entity
@Table(name = "post_stats")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostStats {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scheduled_post_id", nullable = false)
    private ScheduledPost scheduledPost;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metrics_json", nullable = false)
    private Map<String, Object> metricsJson;

    public PostStats(ScheduledPost scheduledPost, Map<String, Object> metricsJson) {
        this.scheduledPost = scheduledPost;
        this.metricsJson = metricsJson;
        this.capturedAt = Instant.now();
    }
}
