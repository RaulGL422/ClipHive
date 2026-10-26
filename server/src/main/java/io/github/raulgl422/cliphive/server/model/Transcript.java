package io.github.raulgl422.cliphive.server.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One row per video job: the whisper.cpp word-level transcript, already
 * stitched together by the server from the worker's per-chunk results,
 * discarding the overlap between chunks - docs/DESIGN.md section 4 step 5.
 *
 * <p>{@link #wordsJson} is kept as a raw {@code Map} ({@code segments},
 * {@code language}…) rather than a typed structure: several services read
 * the parts they need from it, and it's stored as {@code jsonb} as is.
 */
@Entity
@Table(name = "transcript")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transcript {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "video_job_id", nullable = false, unique = true)
    private VideoJob videoJob;

    private String language;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "words_json", nullable = false)
    private Map<String, Object> wordsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Transcript(VideoJob videoJob, String language, Map<String, Object> wordsJson) {
        this.videoJob = videoJob;
        this.language = language;
        this.wordsJson = wordsJson;
        this.createdAt = Instant.now();
    }

    /** Replaces the words after Claude's correction (DESIGN.md §10.5) — same words, same times. */
    public void correctWords(Map<String, Object> correctedWordsJson) {
        this.wordsJson = correctedWordsJson;
    }
}
