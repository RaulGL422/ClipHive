package io.github.raulgl422.cliphive.server.model;

import io.github.raulgl422.cliphive.protocol.state.ScheduledPostState;
import io.github.raulgl422.cliphive.server.model.enums.Platform;
import io.github.raulgl422.cliphive.server.model.enums.ScheduledPostOrigin;
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

import java.time.Instant;
import java.util.UUID;

/**
 * One (clip, platform) publication - docs/DESIGN.md section 5.4.
 * {@link #scheduledAt} is stored in UTC, shown as Europe/Madrid in the
 * superadmin (section 10.4). {@link #externalId} is the idempotency key
 * after a server restart mid-upload (section 11.3): once the platform
 * returns an id it's saved immediately, so a restart checks that id's status
 * instead of re-uploading.
 *
 * <p>State, external id and confirmation flags only change through the
 * methods below: the external id is what keeps a restart from uploading
 * twice (§11.3).
 */
@Entity
@Table(name = "scheduled_post")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScheduledPost {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "clip_id", nullable = false)
    private Clip clip;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Platform platform;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScheduledPostState state;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScheduledPostOrigin origin;

    @Column(name = "external_id")
    private String externalId;

    @Column(name = "error_message")
    private String errorMessage;

    /** When it went out — drives stats collection (DESIGN.md §12.4). */
    @Column(name = "published_at")
    private Instant publishedAt;

    /** Link to the publication when the platform can't derive it from the id (Instagram). */
    @Column(name = "external_url")
    private String externalUrl;

    /** Failed attempts so far, for the growing retry delay (DESIGN.md §11.3). */
    @Column(nullable = false)
    private int attempts;

    /** Waiting out a platform limit (§11.3): shown as "Aplazada" and the planner gives that platform nothing more today. */
    @Column(name = "postponed", nullable = false)
    private boolean postponed;

    /** Uploaded by hand and marked from the superadmin (§11.3). */
    @Column(name = "published_manually", nullable = false)
    private boolean publishedManually;

    /** The "upload it by hand" email already went out for this publication (§11.3, §12.3). */
    @Setter
    @Column(name = "manual_alert_sent", nullable = false)
    private boolean manualAlertSent;

    /** Uploaded as a draft or private: you still have to publish it in the app and confirm it (§11.3). */
    @Column(name = "awaiting_confirmation", nullable = false)
    private boolean awaitingConfirmation;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public ScheduledPost(Clip clip, Platform platform, Instant scheduledAt, ScheduledPostOrigin origin) {
        this.clip = clip;
        this.platform = platform;
        this.scheduledAt = scheduledAt;
        this.origin = origin;
        this.state = ScheduledPostState.SCHEDULED;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void setState(ScheduledPostState state) {
        this.state = state;
        this.updatedAt = Instant.now();
    }

    /** Published or cancelled: nothing more will be sent to the platform. */
    public boolean isDone() {
        return state == ScheduledPostState.PUBLISHED || state == ScheduledPostState.CANCELLED;
    }

    /** Done and, if it went out as a draft or private, confirmed too (§11.3). */
    public boolean isFinished() {
        return isDone() && !awaitingConfirmation;
    }

    /** Uploaded as a draft or private, waiting for you to confirm it's public (§11.3). */
    public boolean isPendingConfirmation() {
        return state == ScheduledPostState.PUBLISHED && awaitingConfirmation;
    }

    /** Not sent yet, or given up on: it can still be moved, cancelled or uploaded by hand. */
    public boolean isNotStarted() {
        return state == ScheduledPostState.SCHEDULED || state == ScheduledPostState.FAILED;
    }

    /** The upload is about to start — saved before calling the platform (§11.3). */
    public void startUploading() {
        setState(ScheduledPostState.UPLOADING);
    }

    /**
     * An upload cut short (restart or lost thread, §11.3): with the platform's id
     * it only needs its status checked; without one it can't be known whether it
     * arrived, so it's FAILED with {@code error} rather than risk publishing twice.
     */
    public void recoverInterruptedUpload(String error) {
        if (externalId != null) setState(ScheduledPostState.PROCESSING);
        else fail(error);
    }

    /** The platform accepted the file and returned its id (saved at once, see idempotency note above). */
    public void uploaded(String externalId, String externalUrl) {
        this.externalId = externalId;
        this.externalUrl = externalUrl;
        this.errorMessage = null;
        setState(ScheduledPostState.PROCESSING);
    }

    /** Done on the platform; {@code awaitingConfirmation} when it's only a draft or private (§11.3). */
    public void published(boolean awaitingConfirmation) {
        this.postponed = false;
        this.errorMessage = null;
        this.awaitingConfirmation = awaitingConfirmation;
        this.publishedAt = Instant.now();
        setState(ScheduledPostState.PUBLISHED);
    }

    /** "Ya está publicado": you finished it in the app; {@code url} is its link, if given. */
    public void confirmPublished(String url) {
        this.awaitingConfirmation = false;
        if (url != null) this.externalUrl = url;
        this.updatedAt = Instant.now();
    }

    /** Temporary error: back to SCHEDULED at {@code nextAttemptAt}, one more attempt counted. */
    public void retryLater(String error, Instant nextAttemptAt) {
        this.attempts++;
        this.errorMessage = error;
        this.scheduledAt = nextAttemptAt;
        setState(ScheduledPostState.SCHEDULED);
    }

    /** New time from the superadmin (edit or "publish now"); marked MANUAL so a re-plan leaves it alone. */
    public void reschedule(Instant at) {
        this.postponed = false;
        this.scheduledAt = at;
        this.origin = ScheduledPostOrigin.MANUAL;
        setState(ScheduledPostState.SCHEDULED);
    }

    /** A platform limit was hit: back to SCHEDULED at {@code nextAttemptAt}, no attempt counted (§11.3). */
    public void postpone(String reason, Instant nextAttemptAt) {
        this.postponed = true;
        this.errorMessage = reason;
        this.scheduledAt = nextAttemptAt;
        setState(ScheduledPostState.SCHEDULED);
    }

    public void fail(String error) {
        this.postponed = false;
        this.errorMessage = error;
        setState(ScheduledPostState.FAILED);
    }

    /**
     * "Ya lo subí a mano" (§11.3): the platform refused it, so you posted it
     * yourself. {@code url} and {@code externalId} are optional — an id lets
     * stats be collected.
     */
    public void publishedByHand(String url, String externalId) {
        this.publishedManually = true;
        this.awaitingConfirmation = false;
        this.postponed = false;
        this.errorMessage = null;
        this.externalUrl = url;
        if (externalId != null) this.externalId = externalId;
        this.publishedAt = Instant.now();
        setState(ScheduledPostState.PUBLISHED);
    }

    /** Superadmin retry of a FAILED publication, or a resent draft: fresh attempts, due now, external id cleared. */
    public void retryNow() {
        this.postponed = false;
        this.awaitingConfirmation = false;
        this.attempts = 0;
        this.errorMessage = null;
        this.externalId = null;
        // A resent draft was PUBLISHED: it isn't any more, by hand or otherwise
        this.publishedAt = null;
        this.publishedManually = false;
        this.scheduledAt = Instant.now();
        setState(ScheduledPostState.SCHEDULED);
    }
}
