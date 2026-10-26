package io.github.raulgl422.cliphive.server.model;

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
import org.slf4j.event.Level;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A structured log entry, filterable by level/component/job/worker in the
 * superadmin - docs/DESIGN.md section 12.2. Retention is limited (e.g. 30
 * days), coherent with the "no trace left" principle (R15): FileCleanupService
 * deletes older entries, and the {@code created_at} index added in the V15
 * migration is what makes that cheap.
 *
 * <p>{@link #level} reuses SLF4J's own {@link Level} instead of a bespoke
 * enum - no reason to duplicate a scale this codebase already depends on for
 * actual logging.
 */
@Entity
@Table(name = "log_event")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LogEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Level level;

    @Column(nullable = false)
    private String component;

    @Column(nullable = false)
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_json")
    private Map<String, Object> contextJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public LogEvent(Level level, String component, String message, Map<String, Object> contextJson) {
        this.level = level;
        this.component = component;
        this.message = message;
        this.contextJson = contextJson;
        this.createdAt = Instant.now();
    }
}
