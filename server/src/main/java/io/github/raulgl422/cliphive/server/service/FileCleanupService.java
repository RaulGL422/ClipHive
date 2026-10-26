package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.protocol.state.ClipState;
import io.github.raulgl422.cliphive.protocol.state.ScheduledPostState;
import io.github.raulgl422.cliphive.protocol.state.VideoJobState;
import io.github.raulgl422.cliphive.server.model.Clip;
import io.github.raulgl422.cliphive.server.model.ScheduledPost;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.publish.PublicationService;
import io.github.raulgl422.cliphive.server.repository.ClipRepository;
import io.github.raulgl422.cliphive.server.repository.LogEventRepository;
import io.github.raulgl422.cliphive.server.repository.ScheduledPostRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Deletes the files nothing needs any more (DESIGN.md §15): the source video
 * and intermediates once a job has rendered, each clip's folder once it has
 * no upload pending, finished jobs' folders, orphan folders, failed
 * publications nobody retried in N days, and old logs. Runs every hour and
 * from the "Limpiar ahora" button; never touches a job with an upload in
 * flight.
 */
@Service
public class FileCleanupService {

    private static final Logger log = LoggerFactory.getLogger(FileCleanupService.class);
    private static final String COMPONENT = "limpieza";

    /** The platform may still be reading the file (Instagram fetches it from /media). */
    private static final Set<ScheduledPostState> IN_FLIGHT =
            EnumSet.of(ScheduledPostState.UPLOADING, ScheduledPostState.PROCESSING);
    /** Every clip has its own cut piece by now: the source and intermediates can go. */
    private static final Set<VideoJobState> RENDER_FINISHED =
            EnumSet.of(VideoJobState.RENDERED, VideoJobState.SCHEDULED, VideoJobState.PUBLISHING);

    /** What a sweep did. */
    public record Report(int folders, long bytes, int logs) {
        public boolean isEmpty() {
            return folders == 0 && bytes == 0 && logs == 0;
        }
    }

    /** Disk usage for the settings page. */
    public record DiskUsage(long jobsBytes, long freeBytes) {}

    private final VideoJobRepository jobs;
    private final ClipRepository clips;
    private final ScheduledPostRepository posts;
    private final LogEventRepository logRepository;
    private final PublicationService publications;
    private final SettingService settings;
    private final JobFiles files;
    private final LogEventService logEvents;
    private final TransactionTemplate tx;
    private Clock clock = Clock.systemUTC();

    /** Off only in tests that call {@link #sweep()} by hand. */
    @Value("${cliphive.cleanup.scheduler-enabled:true}")
    private boolean schedulerEnabled = true;

    public FileCleanupService(VideoJobRepository jobs,
                              ClipRepository clips,
                              ScheduledPostRepository posts,
                              LogEventRepository logRepository,
                              PublicationService publications,
                              SettingService settings,
                              JobFiles files,
                              LogEventService logEvents,
                              TransactionTemplate tx) {
        this.jobs = jobs;
        this.clips = clips;
        this.posts = posts;
        this.logRepository = logRepository;
        this.publications = publications;
        this.settings = settings;
        this.files = files;
        this.logEvents = logEvents;
        this.tx = tx;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    @Scheduled(initialDelay = 120_000, fixedDelay = 3_600_000)
    void scheduledSweep() {
        if (!schedulerEnabled) return;
        try {
            sweep();
        } catch (RuntimeException e) {
            log.error("File cleanup failed", e);
        }
    }

    /** One pass over {@code jobs/} and the logs; synchronized so the button and the timer don't overlap. */
    public synchronized Report sweep() {
        Path root = files.jobsRoot();
        Instant cutoff = clock.instant().minus(Duration.ofDays(settings.filesRetentionDays()));
        int folders = 0;
        long bytes = 0;

        if (Files.isDirectory(root)) {
            List<Path> dirs;
            try (Stream<Path> s = Files.list(root)) {
                dirs = s.filter(Files::isDirectory).toList();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            for (Path dir : dirs) {
                try {
                    long[] r = sweepJob(dir, cutoff);
                    folders += (int) r[0];
                    bytes += r[1];
                } catch (IOException | RuntimeException e) {
                    log.warn("Cleanup of {} failed: {}", dir, e.getMessage());
                }
            }
        }

        Instant logCutoff = clock.instant().minus(Duration.ofDays(settings.logsRetentionDays()));
        Integer logs = tx.execute(s -> logRepository.deleteByCreatedAtBefore(logCutoff));
        Report report = new Report(folders, bytes, logs == null ? 0 : logs);
        if (!report.isEmpty()) {
            log.info("Cleanup: {} folder(s), {} freed, {} old log(s)", folders, human(bytes), report.logs());
            if (folders > 0) {
                logEvents.info(COMPONENT, "Limpieza: " + folders + " carpeta(s) borradas, " + human(bytes) + " liberados");
            }
        }
        return report;
    }

    /** @return {deleted folders, freed bytes} */
    private long[] sweepJob(Path dir, Instant cutoff) throws IOException {
        UUID jobId;
        try {
            jobId = UUID.fromString(dir.getFileName().toString());
        } catch (IllegalArgumentException e) {
            return new long[]{0, 0}; // not ours
        }
        VideoJob job = jobs.findById(jobId).orElse(null);
        if (job == null) {
            // Orphan (e.g. the folder outlived a deleted job): only once it's been quiet a while
            if (lastModified(dir).isBefore(cutoff)) {
                log.info("Cleanup: orphan folder {}", dir);
                return new long[]{1, delete(dir)};
            }
            return new long[]{0, 0};
        }

        List<ScheduledPost> jobPosts = posts.findByClipVideoJobId(jobId);
        if (jobPosts.stream().anyMatch(p -> IN_FLIGHT.contains(p.getState()))) return new long[]{0, 0};

        if (job.isTerminal()) {
            markCleaned(jobId);
            return new long[]{1, delete(dir)};
        }

        int folders = 0;
        long bytes = 0;
        if (RENDER_FINISHED.contains(job.getState())) {
            // Source, audio, chunks, loose JSON: everything but clips/
            try (Stream<Path> s = Files.list(dir)) {
                for (Path p : s.filter(p -> !p.getFileName().toString().equals("clips")).toList()) {
                    bytes += delete(p);
                }
            }
            if (bytes > 0) {
                folders++;
                log.info("Cleanup: job {} rendered, source and intermediates deleted", jobId);
            }
        }

        Map<UUID, List<ScheduledPost>> byClip = jobPosts.stream()
                .collect(Collectors.groupingBy(p -> p.getClip().getId()));
        for (Clip clip : clips.findByVideoJobIdOrderByClipIndex(jobId)) {
            Path clipDir = files.clipDir(jobId, clip.getClipIndex());
            if (!Files.exists(clipDir)) continue;
            List<ScheduledPost> clipPosts = byClip.getOrDefault(clip.getId(), List.of());
            if (!clipPosts.isEmpty() && staleFailures(clipPosts, cutoff)) {
                // §15 safety net: nobody retried in N days — give up so the file can go
                for (ScheduledPost p : clipPosts) {
                    if (p.getState() == ScheduledPostState.FAILED) publications.cancel(p.getId());
                }
                logEvents.warn(COMPONENT, "Publicación fallida cancelada: el fichero se borra tras "
                        + settings.filesRetentionDays() + " días sin reintentarla", jobId.toString());
                clipPosts = posts.findByClipId(clip.getId());
            }
            boolean done = clip.getState() == ClipState.CANCELLED
                    || (!clipPosts.isEmpty() && clipPosts.stream().allMatch(p -> finished(p, cutoff)));
            if (!done) continue;
            bytes += delete(clipDir);
            folders++;
            tx.executeWithoutResult(s -> clips.findById(clip.getId()).ifPresent(c -> {
                if (c.getState() == ClipState.PUBLISHED || c.getState() == ClipState.SCHEDULED) {
                    c.setState(ClipState.CLEANED);
                    clips.save(c);
                }
            }));
        }
        return new long[]{folders, bytes};
    }

    /**
     * Published or cancelled — but a draft or private upload you haven't
     * confirmed keeps its file (you may resend or upload it by hand, §11.3),
     * for N days at most.
     */
    private static boolean finished(ScheduledPost p, Instant cutoff) {
        return p.isFinished() || (p.isDone() && p.getPublishedAt() != null && p.getPublishedAt().isBefore(cutoff));
    }

    /** Only FAILED and finished publications, and every FAILED one untouched since before the cutoff. */
    private static boolean staleFailures(List<ScheduledPost> clipPosts, Instant cutoff) {
        boolean anyFailed = false;
        for (ScheduledPost p : clipPosts) {
            if (p.getState() == ScheduledPostState.FAILED) {
                if (!p.getUpdatedAt().isBefore(cutoff)) return false;
                anyFailed = true;
            } else if (!finished(p, cutoff)) {
                return false;
            }
        }
        return anyFailed;
    }

    private void markCleaned(UUID jobId) {
        tx.executeWithoutResult(s -> {
            for (Clip c : clips.findByVideoJobIdOrderByClipIndex(jobId)) {
                if (c.getState() == ClipState.PUBLISHED) {
                    c.setState(ClipState.CLEANED);
                    clips.save(c);
                }
            }
        });
    }

    public DiskUsage usage() {
        Path root = files.jobsRoot();
        long free = 0;
        try {
            Path existing = Files.isDirectory(root) ? root : files.dataDir().toAbsolutePath();
            while (existing != null && !Files.exists(existing)) existing = existing.getParent();
            if (existing != null) free = Files.getFileStore(existing).getUsableSpace();
        } catch (IOException e) {
            log.debug("Free space unknown: {}", e.getMessage());
        }
        return new DiskUsage(Files.isDirectory(root) ? size(root) : 0, free);
    }

    /** Bytes deleted. */
    private static long delete(Path p) throws IOException {
        long size = size(p);
        FileSystemUtils.deleteRecursively(p);
        return size;
    }

    private static long size(Path p) {
        try (Stream<Path> s = Files.walk(p)) {
            return s.filter(Files::isRegularFile).mapToLong(f -> {
                try {
                    return Files.size(f);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException | UncheckedIOException e) {
            return 0;
        }
    }

    private static Instant lastModified(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.map(f -> {
                try {
                    return Files.getLastModifiedTime(f).toInstant();
                } catch (IOException e) {
                    return Instant.EPOCH;
                }
            }).max(Instant::compareTo).orElse(Instant.EPOCH);
        }
    }

    /** "1.4 GB", "820 MB", "12 KB". */
    public static String human(long bytes) {
        if (bytes >= 1L << 30) return String.format(Locale.ROOT, "%.1f GB", bytes / (double) (1L << 30));
        if (bytes >= 1L << 20) return (bytes >> 20) + " MB";
        return (bytes >> 10) + " KB";
    }
}
