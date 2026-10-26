package io.github.raulgl422.cliphive.server.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * One daily Claude publication plan - docs/DESIGN.md section 10.4. Kept so
 * the superadmin can audit what Claude proposed ({@link #outputJson}) versus
 * what the server adjusted ({@link #validationsAppliedJson}) - future
 * publishing hours, within each platform's caps, minimum spacing, etc.
 */
@Entity
@Table(name = "publication_plan")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PublicationPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "plan_date", nullable = false, unique = true)
    private LocalDate planDate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "input_summary_json", nullable = false)
    private Map<String, Object> inputSummaryJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "output_json", nullable = false)
    private Map<String, Object> outputJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validations_applied_json", nullable = false)
    private Map<String, Object> validationsAppliedJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public PublicationPlan(
            LocalDate planDate,
            Map<String, Object> inputSummaryJson,
            Map<String, Object> outputJson,
            Map<String, Object> validationsAppliedJson
    ) {
        this.planDate = planDate;
        this.inputSummaryJson = inputSummaryJson;
        this.outputJson = outputJson;
        this.validationsAppliedJson = validationsAppliedJson;
        this.createdAt = Instant.now();
    }

    /** A re-plan the same day overwrites the day's plan (DESIGN.md §10.4). */
    public void replace(Map<String, Object> inputSummaryJson, Map<String, Object> outputJson,
                        Map<String, Object> validationsAppliedJson) {
        this.inputSummaryJson = inputSummaryJson;
        this.outputJson = outputJson;
        this.validationsAppliedJson = validationsAppliedJson;
        this.createdAt = Instant.now();
    }
}
