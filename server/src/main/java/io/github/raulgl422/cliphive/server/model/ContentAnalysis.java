package io.github.raulgl422.cliphive.server.model;

import io.github.raulgl422.cliphive.server.model.enums.ReviewStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Claude's content analysis output - docs/DESIGN.md section 10.2. Claude
 * responds with segment ids, never timestamps; the server translates ids to
 * real times and validates them elsewhere. The nullable analysis columns can
 * all be empty at once: if Claude fails after retries, the job proceeds
 * without cuts and goes to review instead of getting stuck (R12).
 */
@Entity
@Table(name = "content_analysis")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ContentAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "video_job_id", nullable = false, unique = true)
    private VideoJob videoJob;

    @Column(name = "content_start_segment_id")
    private String contentStartSegmentId;

    @Column(name = "content_end_segment_id")
    private String contentEndSegmentId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "removed_segments_json")
    private List<Map<String, Object>> removedSegmentsJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposed_clips_json")
    private List<Map<String, Object>> proposedClipsJson;

    /** What the server changed in Claude's plan, for the job page (DESIGN.md §10.2). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "plan_notes_json")
    private List<String> planNotesJson;

    @Column(name = "content_type")
    private String contentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false)
    private ReviewStatus reviewStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public ContentAnalysis(VideoJob videoJob, ReviewStatus reviewStatus) {
        this.videoJob = videoJob;
        this.reviewStatus = reviewStatus;
        this.createdAt = Instant.now();
    }
}
