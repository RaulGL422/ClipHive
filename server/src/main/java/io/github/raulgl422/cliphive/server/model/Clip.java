package io.github.raulgl422.cliphive.server.model;

import io.github.raulgl422.cliphive.protocol.state.ClipState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A planned clip - docs/DESIGN.md sections 5.2 and 9. {@link #clipIndex}
 * fixes the priority order between clips of the same video job, lower index
 * first (section 6.2). {@link #state} is {@code protocol}'s own
 * {@link ClipState}.
 */
@Entity
@Table(name = "clip")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Clip {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "video_job_id", nullable = false)
    private VideoJob videoJob;

    @Column(name = "clip_index", nullable = false)
    private int clipIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClipState state;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "kept_ranges_json", nullable = false)
    private List<Map<String, Object>> keptRangesJson;

    private Integer fps;

    private String title;

    private String description;

    /** Short title burned on top of the video (DESIGN.md §10.3); null when there's none. */
    @Column(name = "overlay_title")
    private String overlayTitle;

    /** Section of the editorial plan this clip belongs to (DESIGN.md §10.2); null if none. */
    @Column(name = "section_title")
    private String sectionTitle;

    /** Position in its section's series (1-based) and the series length; null if not planned that way. */
    @Column(name = "part_index")
    private Integer partIndex;

    @Column(name = "part_count")
    private Integer partCount;

    /** "2/3" when the clip is part of a series, else null. */
    public String partLabel() {
        return partIndex != null && partCount != null && partCount > 1 ? partIndex + "/" + partCount : null;
    }

    @Column(name = "file_reference")
    private String fileReference;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Clip(VideoJob videoJob, int clipIndex, List<Map<String, Object>> keptRangesJson) {
        this.videoJob = videoJob;
        this.clipIndex = clipIndex;
        this.keptRangesJson = keptRangesJson;
        this.state = ClipState.PLANNED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void setState(ClipState state) {
        this.state = state;
        this.updatedAt = Instant.now();
    }

    /** Rendered: its video can be watched and downloaded. */
    public boolean hasVideo() {
        return state == ClipState.RENDERED || state == ClipState.SCHEDULED || state == ClipState.PUBLISHED;
    }
}
