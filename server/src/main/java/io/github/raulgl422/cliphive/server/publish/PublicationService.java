package io.github.raulgl422.cliphive.server.publish;

import io.github.raulgl422.cliphive.protocol.state.ClipState;
import io.github.raulgl422.cliphive.protocol.state.ScheduledPostState;
import io.github.raulgl422.cliphive.protocol.state.VideoJobState;
import io.github.raulgl422.cliphive.server.model.Clip;
import io.github.raulgl422.cliphive.server.model.ScheduledPost;
import io.github.raulgl422.cliphive.server.model.SocialAccount;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.model.enums.Platform;
import io.github.raulgl422.cliphive.server.model.enums.PublishMode;
import io.github.raulgl422.cliphive.server.model.enums.ScheduledPostOrigin;
import io.github.raulgl422.cliphive.server.repository.ClipRepository;
import io.github.raulgl422.cliphive.server.repository.ScheduledPostRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import io.github.raulgl422.cliphive.server.service.AlertService;
import io.github.raulgl422.cliphive.server.service.ClipMetadataService;
import io.github.raulgl422.cliphive.server.service.JobFiles;
import io.github.raulgl422.cliphive.server.service.LogEventService;
import io.github.raulgl422.cliphive.server.service.SettingService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Publishes rendered clips (DESIGN.md §4 step 11, §11). In the
 * <b>immediate</b> publish mode (§10.4) a RENDERED job gets one publication
 * per clip and connected platform, due right away ({@link #planImmediate});
 * in the <b>claude</b> mode {@link PublicationPlanner} sets the times.
 *
 * <p>Uploads and status checks run on one thread per platform (see
 * {@code executors}): an upload can take minutes, and a slow platform must
 * not hold back the others.
 */
@Service
public class PublicationService {

    private static final Logger log = LoggerFactory.getLogger(PublicationService.class);
    private static final String COMPONENT = "publicaciones";

    /** Attempts before a publication is FAILED (DESIGN.md §11.3). */
    static final int MAX_ATTEMPTS = 5;

    /** A publication still PROCESSING after this fails: the platform won't finish it (§11.3). */
    static final Duration MAX_PROCESSING = Duration.ofHours(24);

    /** Clips not scheduled yet: their job isn't finished. */
    private static final Set<ClipState> WAITING_CLIPS =
            EnumSet.of(ClipState.PLANNED, ClipState.RENDERING, ClipState.RENDERED);

    private final ScheduledPostRepository posts;
    private final VideoJobRepository jobs;
    private final ClipRepository clips;
    private final SocialAccountService accounts;
    private final SettingService settings;
    private final JobFiles files;
    private final LogEventService logEvents;
    private final TransactionTemplate tx;
    private final AlertService alerts;
    private final Map<Platform, Publisher> publishers = new EnumMap<>(Platform.class);
    /**
     * One upload thread per platform (DESIGN.md §11.3): a slow one (Instagram waits
     * for its processing) doesn't hold the others up, and each platform still
     * takes a video's parts in order.
     */
    private final Map<Platform, ExecutorService> executors = new ConcurrentHashMap<>();
    /** Publications with an upload or a status check queued or running in this process. */
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    /** An UPLOADING publication nothing here is working on, older than this, is recovered. */
    static final Duration ORPHAN_AFTER = Duration.ofMinutes(2);

    /** Off only in tests that drive the steps by hand. */
    @Value("${cliphive.publishing.scheduler-enabled:true}")
    private boolean schedulerEnabled = true;

    public PublicationService(ScheduledPostRepository posts,
                              VideoJobRepository jobs,
                              ClipRepository clips,
                              SocialAccountService accounts,
                              SettingService settings,
                              JobFiles files,
                              LogEventService logEvents,
                              TransactionTemplate tx,
                              AlertService alerts,
                              List<Publisher> publishers) {
        this.posts = posts;
        this.jobs = jobs;
        this.clips = clips;
        this.accounts = accounts;
        this.settings = settings;
        this.files = files;
        this.logEvents = logEvents;
        this.tx = tx;
        this.alerts = alerts;
        publishers.forEach(p -> this.publishers.put(p.platform(), p));
    }

    @PreDestroy
    void shutdown() {
        executors.values().forEach(ExecutorService::shutdownNow);
    }

    /**
     * What the superadmin shows as a publication's state: a TikTok draft or a
     * private YouTube video you haven't confirmed yet is "… FALTA PUBLICAR"
     * (DESIGN.md §11.3), and one waiting out a limit is "APLAZADA".
     */
    public String stateLabel(ScheduledPost post) {
        if (post.getState() == ScheduledPostState.PUBLISHED && post.isPublishedManually()) return "SUBIDA A MANO";
        if (post.isPendingConfirmation()) {
            return deliversAsDraft(post.getPlatform()) ? "EN BORRADORES · FALTA PUBLICAR" : "SUBIDO · FALTA PUBLICAR";
        }
        if (post.getState() == ScheduledPostState.SCHEDULED && post.isPostponed()) return "APLAZADA";
        if (post.getState() == ScheduledPostState.SCHEDULED
                && post.getScheduledAt().isBefore(Instant.now().minus(WAITING_LABEL_AFTER))
                && tx.execute(s -> waitsForEarlier(post))) {
            return "ESPERA A LA ANTERIOR";
        }
        return post.getState().name();
    }

    /** A due publication still SCHEDULED after this is shown as waiting for the previous part. */
    private static final Duration WAITING_LABEL_AFTER = Duration.ofMinutes(1);

    /** Earlier publications that hold a later one back on the same platform (§11.3). */
    private static final Set<ScheduledPostState> PENDING_STATES = EnumSet.of(
            ScheduledPostState.SCHEDULED, ScheduledPostState.UPLOADING, ScheduledPostState.PROCESSING);

    /**
     * True while an earlier clip of the same video still has its publication on
     * this platform pending (DESIGN.md §10.4, §11.3): parts go out in order even
     * when the earlier one is postponed. A FAILED one doesn't hold the rest back.
     */
    boolean waitsForEarlier(ScheduledPost post) {
        Clip clip = post.getClip();
        return posts.existsEarlierInStates(clip.getVideoJob().getId(), post.getPlatform(), clip.getClipIndex(),
                PENDING_STATES);
    }

    /** True for platforms that receive drafts to finish by hand (TikTok until audited). */
    public boolean deliversAsDraft(Platform platform) {
        Publisher p = publishers.get(platform);
        return p != null && p.deliversAsDraft();
    }

    /**
     * Runs {@code work} on the platform's thread, once per publication at a time.
     * Anything it throws is logged — an executor would otherwise keep it in a
     * Future nobody reads, and the publication would hang without a trace.
     */
    private void submit(Platform platform, UUID postId, Runnable work) {
        if (!inFlight.add(postId)) return; // already queued or running
        try {
            executors.computeIfAbsent(platform, p -> Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().name("publisher-" + p.name().toLowerCase()).daemon().factory()))
                    .execute(() -> {
                        try {
                            work.run();
                        } catch (Throwable t) {
                            log.error("Publication {} on {} crashed: {}", postId, platform, t.toString(), t);
                            try {
                                handleError(postId, "Error inesperado: " + t, true);
                            } catch (RuntimeException e) {
                                log.error("Could not record the failure of publication {}", postId, e);
                            }
                        } finally {
                            inFlight.remove(postId);
                        }
                    });
        } catch (RuntimeException e) {
            inFlight.remove(postId);
            throw e;
        }
    }

    /** Platforms we can publish to right now: an implementation and a connected account. */
    public List<Platform> connectedPlatforms() {
        List<Platform> result = new ArrayList<>();
        for (Platform p : publishers.keySet()) {
            if (accounts.accountFor(p).isPresent()) result.add(p);
        }
        return result;
    }

    private String publicUrl(Platform platform, String externalId) {
        Publisher p = publishers.get(platform);
        return (p == null || externalId == null) ? null : p.publicUrl(externalId);
    }

    /** The stored link if the platform gave one (Instagram), else the one derived from the id. */
    public String publicUrl(ScheduledPost post) {
        return post.getExternalUrl() != null ? post.getExternalUrl()
                : publicUrl(post.getPlatform(), post.getExternalId());
    }

    /** Link to each publication on its platform, by publication id (those without one are left out). */
    @SafeVarargs
    public final Map<UUID, String> publicUrls(Collection<ScheduledPost>... lists) {
        Map<UUID, String> links = new HashMap<>();
        for (Collection<ScheduledPost> list : lists) {
            for (ScheduledPost p : list) {
                String url = publicUrl(p);
                if (url != null) links.put(p.getId(), url);
            }
        }
        return links;
    }

    // ── Superadmin actions ─────────────────────────────────────────────────

    /**
     * Applies {@code action} to the publication and saves it, if it exists and
     * {@code when} holds; false otherwise. One transaction.
     */
    private boolean act(UUID postId, Predicate<ScheduledPost> when, Consumer<ScheduledPost> action) {
        Boolean ok = tx.execute(s -> posts.findById(postId)
                .filter(when)
                .map(p -> {
                    action.accept(p);
                    posts.save(p);
                    return true;
                }).orElse(false));
        return Boolean.TRUE.equals(ok);
    }

    /** FAILED → SCHEDULED, due now, with fresh attempts. */
    public boolean retry(UUID postId) {
        return act(postId, p -> p.getState() == ScheduledPostState.FAILED, p -> {
            p.retryNow();
            logEvents.info(COMPONENT, p.getPlatform() + ": publicación reintentada", jobId(p));
        });
    }

    /** Retries every FAILED publication of a job; returns how many. */
    public int retryFailedForJob(UUID jobId) {
        List<UUID> failed = tx.execute(s -> posts.findByClipVideoJobId(jobId).stream()
                .filter(p -> p.getState() == ScheduledPostState.FAILED)
                .map(ScheduledPost::getId).toList());
        int count = 0;
        if (failed != null) for (UUID id : failed) if (retry(id)) count++;
        return count;
    }

    /** Most publications a day for a platform (DESIGN.md §10.4). */
    public int dailyLimit(Platform platform) {
        Publisher p = publishers.get(platform);
        return p == null ? 0 : p.dailyLimit();
    }

    /**
     * Moves a publication that hasn't started to {@code at} ("now" to publish it
     * right away) — and with it the clip's other publications that haven't
     * started either, so the platforms stay together (DESIGN.md §10.4).
     */
    public boolean reschedule(UUID postId, Instant at) {
        return act(postId, p -> p.getState() == ScheduledPostState.SCHEDULED, p -> {
            for (ScheduledPost sibling : posts.findByClipId(p.getClip().getId())) {
                if (sibling.getState() != ScheduledPostState.SCHEDULED) continue;
                sibling.reschedule(at);
                posts.save(sibling);
            }
        });
    }

    /**
     * "Ya lo subí a mano" (DESIGN.md §11.3): a SCHEDULED or FAILED publication
     * you posted yourself becomes PUBLISHED and stops being tried. {@code url}
     * is optional; a YouTube link gives the video id, so its stats are collected.
     */
    public boolean markUploadedByHand(UUID postId, String url) {
        String link = optionalLink(url);
        return act(postId, ScheduledPost::isNotStarted, p -> {
            p.publishedByHand(link, p.getPlatform() == Platform.YOUTUBE ? youtubeId(link) : null);
            rollUp(p);
            if (p.getExternalId() != null) {
                Platform platform = p.getPlatform();
                UUID jobId = p.getClip().getVideoJob().getId();
                String externalId = p.getExternalId();
                // On the platform's thread: the one that creates playlists, so never twice
                submit(platform, UUID.randomUUID(), () -> addToPlaylist(platform, jobId, externalId));
            }
            logEvents.info(COMPONENT, p.getPlatform().label() + ": marcada como subida a mano" + withLink(link), jobId(p));
        });
    }

    /**
     * "Ya está publicado" (DESIGN.md §11.3): you finished the draft or made the
     * private video public. {@code url} is optional; on YouTube it's not needed
     * (the link follows from the id).
     */
    public boolean confirmPublished(UUID postId, String url) {
        String link = optionalLink(url);
        return act(postId, ScheduledPost::isPendingConfirmation, p -> {
            p.confirmPublished(link);
            rollUp(p);
            logEvents.info(COMPONENT, p.getPlatform().label() + ": confirmado como publicado" + withLink(link), jobId(p));
        });
    }

    /** True when a draft can be sent again: not confirmed yet and its file still there (§11.3). */
    public boolean canResend(ScheduledPost post) {
        return post.isPendingConfirmation() && deliversAsDraft(post.getPlatform()) && clipFileExists(post);
    }

    /** The clip's rendered file is still on disk (the cleanup hasn't taken it, §15). */
    public boolean clipFileExists(ScheduledPost post) {
        return files.renderedClipExists(post.getClip().getVideoJob().getId(), post.getClip().getClipIndex());
    }

    /**
     * "Reenviar borrador" (DESIGN.md §11.3): the draft got lost in the app, so
     * the same clip goes to the inbox again, now, as a new draft.
     */
    public boolean resendDraft(UUID postId) {
        return act(postId, this::canResend, p -> {
            p.retryNow();
            Clip clip = p.getClip();
            if (clip.getState() == ClipState.PUBLISHED) {
                clip.setState(ClipState.SCHEDULED);
                clips.save(clip);
            }
            logEvents.info(COMPONENT, p.getPlatform().label() + ": borrador reenviado", jobId(p));
        });
    }

    private static final Pattern YOUTUBE_ID = Pattern.compile(
            "(?:youtu\\.be/|/shorts/|/live/|[?&]v=)([A-Za-z0-9_-]{11})");

    /** The video id in a YouTube link ({@code /shorts/ID}, {@code watch?v=ID}, {@code youtu.be/ID}), or null. */
    static String youtubeId(String url) {
        if (url == null) return null;
        var m = YOUTUBE_ID.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /** Cancels a publication that hasn't started uploading. */
    public boolean cancel(UUID postId) {
        return act(postId, ScheduledPost::isNotStarted, p -> {
            p.setState(ScheduledPostState.CANCELLED);
            rollUp(p);
        });
    }

    /** A link typed in the superadmin, or null when left blank. */
    private static String optionalLink(String url) {
        return url == null || url.isBlank() ? null : url.strip();
    }

    /** " (link)" to append to a log line, or nothing. */
    private static String withLink(String link) {
        return link != null ? " (" + link + ")" : "";
    }

    private static String jobId(ScheduledPost post) {
        return post.getClip().getVideoJob().getId().toString();
    }

    // ── Restart recovery (DESIGN.md §11.3) ─────────────────────────────────

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedUploads() {
        tx.executeWithoutResult(s -> {
            for (ScheduledPost post : posts.findByState(ScheduledPostState.UPLOADING)) {
                post.recoverInterruptedUpload("Subida interrumpida por un reinicio del servidor: reintenta a mano");
                if (post.getState() == ScheduledPostState.FAILED) {
                    log.warn("Publication {} was UPLOADING without an external id at startup — FAILED", post.getId());
                }
                posts.save(post);
            }
        });
    }

    // ── Scheduler tick ─────────────────────────────────────────────────────

    @Scheduled(initialDelay = 20_000, fixedDelay = 30_000)
    public void tick() {
        if (!schedulerEnabled) return;
        try {
            planImmediate();
            recoverOrphanUploads();
            startDueUploads();
            checkProcessing();
        } catch (RuntimeException e) {
            log.error("Publication tick failed: {}", e.getMessage(), e);
        }
    }

    /** Immediate mode: one publication per rendered clip and connected platform, due now. */
    void planImmediate() {
        if (settings.publishMode() != PublishMode.IMMEDIATE) return;
        List<Platform> platforms = connectedPlatforms();
        if (platforms.isEmpty()) return; // nowhere to publish: jobs wait in RENDERED

        tx.executeWithoutResult(s -> {
            for (VideoJob job : jobs.findByState(VideoJobState.RENDERED)) {
                // (clip, platform) pairs that already have a publication
                Set<String> planned = new HashSet<>();
                for (ScheduledPost p : posts.findByClipVideoJobId(job.getId())) {
                    planned.add(p.getClip().getId() + ":" + p.getPlatform());
                }
                int created = 0;
                for (Clip clip : clips.findByVideoJobIdOrderByClipIndex(job.getId())) {
                    if (clip.getState() != ClipState.RENDERED) continue;
                    for (Platform platform : platforms) {
                        if (!planned.add(clip.getId() + ":" + platform)) continue;
                        // Due now, but a millisecond apart by clip order so the uploader (oldest first)
                        // takes a video's parts in order (§10.4)
                        posts.save(new ScheduledPost(clip, platform,
                                Instant.now().minusSeconds(10).plusMillis(clip.getClipIndex()),
                                ScheduledPostOrigin.IMMEDIATE));
                        created++;
                    }
                    clip.setState(ClipState.SCHEDULED);
                    clips.save(clip);
                }
                if (created == 0) continue; // e.g. every clip failed to render
                job.setState(VideoJobState.SCHEDULED);
                jobs.save(job);
                log.info("Job {} → SCHEDULED ({} publication(s), immediate mode)", job.getId(), created);
                logEvents.info(COMPONENT, created + " publicación(es) programadas (modo inmediato)",
                        job.getId().toString());
            }
        });
    }

    /**
     * An UPLOADING publication that no thread here is working on (DESIGN.md
     * §11.3): handled like one interrupted by a restart, so it can't stay
     * frozen.
     */
    void recoverOrphanUploads() {
        Instant cutoff = Instant.now().minus(ORPHAN_AFTER);
        tx.executeWithoutResult(s -> {
            for (ScheduledPost post : posts.findByState(ScheduledPostState.UPLOADING)) {
                if (inFlight.contains(post.getId()) || post.getUpdatedAt().isAfter(cutoff)) continue;
                post.recoverInterruptedUpload("La subida se quedó a medias sin respuesta: reintenta a mano");
                if (post.getState() == ScheduledPostState.FAILED) {
                    logEvents.error(COMPONENT, post.getPlatform() + ": la subida se quedó a medias sin respuesta",
                            jobId(post));
                }
                log.warn("Publication {} was UPLOADING with no upload running — recovered as {}", post.getId(), post.getState());
                posts.save(post);
            }
        });
    }

    /** Marks due publications UPLOADING and hands them to their platform's upload thread. */
    void startDueUploads() {
        record Due(UUID id, Platform platform) {}
        List<Due> due = tx.execute(s -> {
            List<Due> ids = new ArrayList<>();
            for (ScheduledPost post : posts.findByStateAndScheduledAtLessThanEqualOrderByScheduledAtAsc(
                    ScheduledPostState.SCHEDULED, Instant.now())) {
                if (waitsForEarlier(post)) continue; // its previous part goes first (§11.3)
                post.startUploading(); // saved before calling the platform (§11.3)
                posts.save(post);
                VideoJob job = post.getClip().getVideoJob();
                if (job.getState() == VideoJobState.SCHEDULED) {
                    job.setState(VideoJobState.PUBLISHING);
                    jobs.save(job);
                }
                ids.add(new Due(post.getId(), post.getPlatform()));
            }
            return ids;
        });
        if (due != null) due.forEach(d -> submit(d.platform(), d.id(), () -> upload(d.id())));
    }

    /** Asks the platform how its processing is going for every PROCESSING publication. */
    void checkProcessing() {
        List<ScheduledPost> processing = tx.execute(s -> posts.findByState(ScheduledPostState.PROCESSING));
        if (processing != null) processing.forEach(p -> submit(p.getPlatform(), p.getId(), () -> checkStatus(p.getId())));
    }

    // ── Upload thread ──────────────────────────────────────────────────────

    private record Upload(UUID postId, Platform platform, UUID clipId, Path file, PostMetadata metadata, UUID jobId) {}

    void upload(UUID postId) {
        Upload u = tx.execute(s -> {
            ScheduledPost post = posts.findById(postId).orElse(null);
            if (post == null || post.getState() != ScheduledPostState.UPLOADING) return null;
            Clip clip = post.getClip();
            VideoJob job = clip.getVideoJob();
            return new Upload(postId, post.getPlatform(), clip.getId(),
                    files.renderedClip(job.getId(), clip.getClipIndex()),
                    metadata(clip, job), job.getId());
        });
        if (u == null) return;

        Publisher publisher;
        UploadResult result;
        try {
            publisher = publisher(u.platform());
            SocialAccount account = account(u.platform());
            result = publisher.upload(accounts.accessToken(account),
                    new PublishItem(u.clipId(), u.file(), u.metadata(), account.getExternalAccountId()));
        } catch (PublishException e) {
            if (e.retryAfter() != null) postpone(postId, e.getMessage(), e.retryAfter());
            else handleError(postId, e.getMessage(), e.isRetryable());
            return;
        } catch (RuntimeException e) {
            log.error("Publication {} failed unexpectedly: {}", postId, e.getMessage(), e);
            handleError(postId, "Error inesperado: " + e.getMessage(), true);
            return;
        }

        // From here the platform has the video. Nothing below may send the
        // publication back to the queue: it would be uploaded a second time.
        try {
            tx.executeWithoutResult(s -> posts.findById(postId).ifPresent(post -> {
                post.uploaded(result.externalId(), result.url());
                posts.save(post);
            }));
        } catch (RuntimeException e) {
            log.error("Publication {} uploaded to {} as {} but the id couldn't be saved",
                    postId, u.platform(), result.externalId(), e);
            failQuietly(postId, "Subido a " + u.platform().label() + " (id " + result.externalId()
                    + ") pero no se pudo guardar: revísalo en la app antes de reintentar");
            return;
        }
        try {
            String link = result.url() != null ? result.url() : publisher.publicUrl(result.externalId());
            log.info("Publication {} uploaded to {} as {}", postId, u.platform(), result.externalId());
            logEvents.info(COMPONENT, u.platform() + ": clip subido, procesándose" + withLink(link), u.jobId().toString());
            // Queued right behind this upload on the platform's thread: in clip order, and the
            // upload itself is done, so its status checks aren't held back
            submit(u.platform(), UUID.randomUUID(), () -> addToPlaylist(u.platform(), u.jobId(), result.externalId()));
        } catch (RuntimeException e) {
            log.error("Publication {}: after-upload bookkeeping failed", postId, e);
        }
    }

    /** Marks an UPLOADING publication FAILED without retry; a failure to do so is only logged. */
    private void failQuietly(UUID postId, String error) {
        try {
            tx.executeWithoutResult(s -> posts.findById(postId)
                    .filter(p -> p.getState() == ScheduledPostState.UPLOADING)
                    .ifPresent(p -> {
                        p.fail(error);
                        posts.save(p);
                        logEvents.error(COMPONENT, p.getPlatform() + ": " + error, jobId(p));
                    }));
        } catch (RuntimeException e) {
            log.error("Could not mark publication {} failed: {}", postId, error, e);
        }
    }

    void checkStatus(UUID postId) {
        record Check(Platform platform, String externalId, String jobId, PostMetadata metadata, Instant since) {}
        Check c = tx.execute(s -> posts.findById(postId)
                .filter(p -> p.getState() == ScheduledPostState.PROCESSING)
                .map(p -> new Check(p.getPlatform(), p.getExternalId(), jobId(p),
                        metadata(p.getClip(), p.getClip().getVideoJob()), p.getUpdatedAt()))
                .orElse(null));
        if (c == null) return;
        // Asked first: after a day off the platform may well have finished it. Only
        // when it still says PROCESSING (or can't be asked) does the age count.
        boolean tooOld = c.since().isBefore(Instant.now().minus(MAX_PROCESSING));
        try {
            Publisher publisher = publisher(c.platform());
            ProcessingResult result = publisher.status(accounts.accessToken(account(c.platform())), c.externalId());
            switch (result.state()) {
                case PROCESSING -> { if (tooOld) failTooOld(postId); }
                case DONE -> {
                    tx.executeWithoutResult(s -> posts.findById(postId).ifPresent(post -> {
                        post.published(publisher.needsConfirmation());
                        posts.save(post);
                        rollUp(post);
                    }));
                    log.info("Publication {} PUBLISHED on {}", postId, c.platform());
                    if (publisher.deliversAsDraft()) {
                        logEvents.info(COMPONENT, c.platform() + ": borrador listo en la app, falta publicarlo a mano",
                                c.jobId());
                        alerts.draftReady(c.platform().label(), c.metadata());
                    } else {
                        logEvents.info(COMPONENT, c.platform() + ": publicado"
                                + withLink(publicUrl(c.platform(), c.externalId())), c.jobId());
                    }
                }
                case FAILED -> handleError(postId, result.detail(), false);
            }
        } catch (PublishException e) {
            // Status checks are cheap to repeat: only give up if the error is final
            if (!e.isRetryable()) handleError(postId, e.getMessage(), false);
            else if (tooOld) failTooOld(postId);
            else log.warn("Status check for publication {} failed, will retry: {}", postId, e.getMessage());
        } catch (RuntimeException e) {
            // The video is on the platform: try again next tick, never re-upload
            if (tooOld) failTooOld(postId);
            else log.warn("Status check for publication {} crashed, will retry", postId, e);
        }
    }

    /** A revoked token or a draft nobody finished would otherwise be polled forever. */
    private void failTooOld(UUID postId) {
        handleError(postId, "La plataforma no terminó de procesarlo en " + MAX_PROCESSING.toHours()
                + " h: revísalo en la app antes de reintentar", false);
    }

    // ── Playlists (DESIGN.md §11.2) ────────────────────────────────────────

    /**
     * Adds a just-uploaded clip to its video's playlist on the platform,
     * creating the playlist with the first clip. Runs on the platform's upload
     * thread, so clips go in in order and the playlist is created only once.
     * Never fails the publication: a problem is only logged.
     */
    void addToPlaylist(Platform platform, UUID jobId, String externalId) {
        Publisher publisher = publishers.get(platform);
        if (publisher == null || !publisher.supportsPlaylists() || !settings.publishPlaylists()) return;
        record Target(String playlistId, PlaylistInfo info) {}
        Target target = tx.execute(s -> jobs.findById(jobId).map(job -> new Target(
                job.getPlaylistIdsJson() == null ? null : job.getPlaylistIdsJson().get(platform.name()),
                playlistInfo(job))).orElse(null));
        if (target == null) return;
        try {
            String id = publisher.addToPlaylist(accounts.accessToken(account(platform)), target.playlistId(),
                    target.info(), externalId);
            if (!id.equals(target.playlistId())) {
                tx.executeWithoutResult(s -> jobs.findById(jobId).ifPresent(job -> {
                    Map<String, String> ids = new LinkedHashMap<>(
                            job.getPlaylistIdsJson() == null ? Map.of() : job.getPlaylistIdsJson());
                    ids.put(platform.name(), id);
                    jobs.updatePlaylistIds(jobId, ids);
                }));
                logEvents.info(COMPONENT, platform.label() + ": lista de reproducción creada para este vídeo", jobId.toString());
            }
        } catch (PublishException | RuntimeException e) {
            log.warn("Could not add {} to the {} playlist of job {}: {}", externalId, platform, jobId, e.getMessage());
            logEvents.warn(COMPONENT, platform.label() + ": no se pudo añadir el clip a la lista: " + e.getMessage(),
                    jobId.toString());
        }
    }

    /** The playlist of a source video: its title and the credit to its channel. */
    static PlaylistInfo playlistInfo(VideoJob job) {
        String title = job.getTitle() == null || job.getTitle().isBlank() ? "Clips" : job.getTitle().strip();
        String channel = job.getSourceChannel() == null || job.getSourceChannel().isBlank() ? null : job.getSourceChannel().strip();
        String description = "Todos los clips de «" + title + "», en orden."
                + (channel != null ? " Clips de " + channel + "." : "");
        return new PlaylistInfo(title, description);
    }

    // ── Errors and roll-up ─────────────────────────────────────────────────

    private void handleError(UUID postId, String error, boolean retryable) {
        tx.executeWithoutResult(s -> posts.findById(postId).ifPresent(post -> {
            if (post.isDone() || post.getState() == ScheduledPostState.FAILED) {
                log.warn("Publication {} is already {}; ignoring: {}", postId, post.getState(), error);
                return;
            }
            if (retryable && post.getExternalId() != null) {
                // Uploaded already: a retry would upload it again. Keep checking its status.
                log.warn("Publication {} hit an error after the upload; it stays {}: {}",
                        postId, post.getState(), error);
                return;
            }
            if (retryable && post.getAttempts() + 1 < MAX_ATTEMPTS) {
                Instant next = Instant.now().plus(backoff(post.getAttempts()));
                post.retryLater(error, next);
                log.warn("Publication {} failed (attempt {}), retrying at {}: {}",
                        postId, post.getAttempts(), next, error);
                logEvents.warn(COMPONENT, post.getPlatform() + ": falló (se reintentará): " + error, jobId(post));
            } else {
                post.fail(error);
                log.error("Publication {} FAILED: {}", postId, error);
                logEvents.error(COMPONENT, post.getPlatform() + ": publicación fallida: " + error, jobId(post));
            }
            posts.save(post);
        }));
    }

    /**
     * A platform limit: wait {@code delay} without spending an attempt, and the
     * first time, email you to upload it by hand so the calendar doesn't stall
     * (§11.3).
     */
    private void postpone(UUID postId, String reason, Duration delay) {
        record Alert(Platform platform, PostMetadata metadata, UUID jobId, UUID clipId) {}
        Alert alert = tx.execute(s -> posts.findById(postId).map(post -> {
            Instant next = Instant.now().plus(delay);
            post.postpone(reason, next);
            posts.save(post);
            log.info("Publication {} postponed to {}: {}", postId, next, reason);
            logEvents.warn(COMPONENT, post.getPlatform() + ": aplazada " + delay.toMinutes() + " min: " + reason,
                    jobId(post));
            if (post.isManualAlertSent()) return null;
            Clip clip = post.getClip();
            return new Alert(post.getPlatform(), metadata(clip, clip.getVideoJob()), clip.getVideoJob().getId(), clip.getId());
        }).orElse(null));
        if (alert == null) return;
        if (alerts.manualUploadNeeded(alert.platform().label(), reason, alert.metadata(), alert.jobId(), alert.clipId())) {
            tx.executeWithoutResult(s -> posts.findById(postId).ifPresent(post -> {
                post.setManualAlertSent(true);
                posts.save(post);
            }));
            logEvents.info(COMPONENT, alert.platform().label() + ": enviado email para subirla a mano",
                    alert.jobId().toString());
        }
    }

    /** 1 min, 2, 4, 8… up to an hour. */
    static Duration backoff(int attempts) {
        long minutes = Math.min(60, 1L << Math.min(attempts, 6));
        return Duration.ofMinutes(minutes);
    }

    /**
     * After a publication finishes: a clip is PUBLISHED once all its
     * publications are, and the job COMPLETED once all of its are published or
     * cancelled — and confirmed, when they went out as a draft or private
     * (§11.3). A FAILED publication keeps the job in PUBLISHING so it can be
     * retried from the job page.
     */
    private void rollUp(ScheduledPost post) {
        Clip clip = post.getClip();
        VideoJob job = clip.getVideoJob();
        List<ScheduledPost> jobPosts = posts.findByClipVideoJobId(job.getId());
        if (jobPosts.stream().filter(p -> p.getClip().getId().equals(clip.getId()))
                .allMatch(p -> p.getState() == ScheduledPostState.PUBLISHED)) {
            clip.setState(ClipState.PUBLISHED);
            clips.save(clip);
        }
        // With the Claude planner some clips may still be waiting for a day (§10.4)
        boolean clipsWaiting = clips.countByVideoJobIdAndStateIn(job.getId(), WAITING_CLIPS) > 0;
        if (!clipsWaiting && !jobPosts.isEmpty() && jobPosts.stream().allMatch(ScheduledPost::isFinished)) {
            job.setState(VideoJobState.COMPLETED);
            jobs.save(job);
            log.info("Job {} → COMPLETED", job.getId());
            logEvents.info(COMPONENT, "Trabajo completado: todas las publicaciones terminadas",
                    job.getId().toString());
        }
    }

    private Publisher publisher(Platform platform) throws PublishException {
        Publisher p = publishers.get(platform);
        if (p == null) throw new PublishException("No hay publicador para " + platform, false);
        return p;
    }

    private SocialAccount account(Platform platform) throws PublishException {
        return accounts.accountFor(platform)
                .orElseThrow(() -> new PublishException("No hay cuenta de " + platform + " conectada", false));
    }

    /** Claude's title/description for the clip, or a fallback from the video title. */
    static PostMetadata metadata(Clip clip, VideoJob job) {
        String title = clip.getTitle();
        if (title == null || title.isBlank()) {
            // No texts from Claude: the section and its part say more than "Video (3)" (§10.3)
            if (clip.getSectionTitle() != null && !clip.getSectionTitle().isBlank()) {
                title = ClipMetadataService.withPart(clip.getSectionTitle(), clip, job.getLanguage());
            } else {
                String base = (job.getTitle() == null || job.getTitle().isBlank()) ? "Clip" : job.getTitle();
                title = base + " (" + (clip.getClipIndex() + 1) + ")";
            }
        }
        return new PostMetadata(title, clip.getDescription() == null ? "" : clip.getDescription());
    }
}
