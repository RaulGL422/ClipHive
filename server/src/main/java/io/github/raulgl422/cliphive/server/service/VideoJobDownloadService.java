package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.protocol.state.VideoJobState;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import io.github.raulgl422.cliphive.server.service.tool.AudioExtractor;
import io.github.raulgl422.cliphive.server.service.tool.SponsorBlockClient;
import io.github.raulgl422.cliphive.server.service.tool.SponsorSegment;
import io.github.raulgl422.cliphive.server.service.tool.VideoDownloader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * Runs the download pipeline asynchronously (docs/DESIGN.md §4, steps 3-4):
 * <ol>
 *   <li>Disk-space guard (R6)</li>
 *   <li>yt-dlp download at max 1080p, at most {@value #MAX_CONCURRENT_DOWNLOADS} concurrently</li>
 *   <li>SHA-256 hash of the source file → stored in {@code video_job.file_hash}</li>
 *   <li>SponsorBlock segment fetch (YouTube only, stored as data)</li>
 *   <li>ffmpeg audio extraction → WAV 16 kHz mono</li>
 *   <li>Transition: DOWNLOADING → TRANSCRIBING</li>
 * </ol>
 *
 * <p>Separated from {@link VideoJobValidationService} so the {@link Async} proxy works —
 * Spring cannot intercept self-calls within the same bean.
 */
@Service
public class VideoJobDownloadService {

    /**
     * Minimum free disk space required before a download starts (R6). Fixed at 10 GB.
     */
    static final long MIN_FREE_BYTES = 10L * 1024 * 1024 * 1024; // 10 GB

    /** At most 2 concurrent yt-dlp downloads (docs/DESIGN.md §4, step 3). */
    static final int MAX_CONCURRENT_DOWNLOADS = 2;

    private static final Logger log = LoggerFactory.getLogger(VideoJobDownloadService.class);

    private final Semaphore downloadSemaphore = new Semaphore(MAX_CONCURRENT_DOWNLOADS);

    private final VideoJobRepository jobRepository;
    private final TransactionTemplate txTemplate;
    private final VideoDownloader downloader;
    private final SponsorBlockClient sponsorBlock;
    private final AudioExtractor audioExtractor;
    private final ObjectMapper objectMapper;
    private final JobFiles files;
    private final TranscribeSetupService transcribeSetup;

    public VideoJobDownloadService(VideoJobRepository jobRepository,
                                   TransactionTemplate txTemplate,
                                   VideoDownloader downloader,
                                   SponsorBlockClient sponsorBlock,
                                   AudioExtractor audioExtractor,
                                   ObjectMapper objectMapper,
                                   JobFiles files,
                                   TranscribeSetupService transcribeSetup) {
        this.jobRepository = jobRepository;
        this.txTemplate = txTemplate;
        this.downloader = downloader;
        this.sponsorBlock = sponsorBlock;
        this.audioExtractor = audioExtractor;
        this.objectMapper = objectMapper;
        this.files = files;
        this.transcribeSetup = transcribeSetup;
    }

    /**
     * Runs the download pipeline for the given job id.
     * The job must already be in DOWNLOADING state when this is called.
     */
    @Async
    public void startDownload(UUID jobId) {
        log.debug("Starting download pipeline for job {}", jobId);

        VideoJob job = txTemplate.execute(status ->
                jobRepository.findById(jobId)
                        .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId)));

        if (job == null) return;

        Path jobDir    = files.jobDir(jobId);
        Path sourceDir = jobDir.resolve("source");
        Path audioDir  = jobDir.resolve("audio");

        // ── Disk space check (R6) ──────────────────────────────────────────────
        try {
            Path checkPath = nearestExistingAncestor(jobDir);
            long usable = Files.getFileStore(checkPath).getUsableSpace();
            if (usable < MIN_FREE_BYTES) {
                failJob(jobId, "Insufficient disk space: %d GB available, %d GB required"
                        .formatted(usable / (1024 * 1024 * 1024),
                                   MIN_FREE_BYTES / (1024 * 1024 * 1024)));
                return;
            }
        } catch (IOException e) {
            // Non-fatal — proceed; yt-dlp will fail naturally if the disk fills up.
            log.warn("Could not check disk space for job {}: {}", jobId, e.getMessage());
        }

        // ── Concurrency limit ──────────────────────────────────────────────────
        downloadSemaphore.acquireUninterruptibly();
        try {
            runDownloadPipeline(job, sourceDir, audioDir, jobDir);
        } catch (RuntimeException e) {
            // Anything unexpected must still end the job: @Async would only log
            // it, and the job would sit in DOWNLOADING or TRANSCRIBING forever.
            log.error("Job {}: download pipeline crashed", jobId, e);
            failJob(jobId, "Unexpected error: " + e);
        } finally {
            downloadSemaphore.release();
        }
    }

    /**
     * Startup recovery: the job finished downloading but the server stopped
     * before its TRANSCRIBE tasks were created (step 5).
     */
    @Async
    public void resumeTranscribeSetup(UUID jobId) {
        try {
            transcribeSetup.setup(jobId);
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            failJob(jobId, "Transcribe setup failed: " + describe(e));
        }
    }

    private void runDownloadPipeline(VideoJob job, Path sourceDir, Path audioDir, Path jobDir) {
        UUID jobId = job.getId();
        String sourceUrl = job.getSourceUrl();

        // ── Step 3a: yt-dlp download ───────────────────────────────────────────
        Path sourceFile;
        try {
            // A dub is only requested when validation found one; otherwise the original audio
            String audioLanguage = Boolean.FALSE.equals(job.getDubbedAudioAvailable()) ? null : job.getAudioLanguage();
            sourceFile = downloader.download(sourceUrl, sourceDir, audioLanguage);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            failJob(jobId, "Download failed: " + describe(e));
            return;
        }
        log.info("Job {} downloaded: {}", jobId, sourceFile.getFileName());

        // ── Step 3b: SHA-256 hash ──────────────────────────────────────────────
        String fileHash;
        try {
            fileHash = sha256Hex(sourceFile);
        } catch (IOException | NoSuchAlgorithmException e) {
            failJob(jobId, "Hash computation failed: " + describe(e));
            return;
        }
        log.debug("Job {} source hash: {}", jobId, fileHash);

        // ── Step 3c: SponsorBlock (YouTube only, stored as data) ───────────────
        // yt-dlp reports the platform as the extractor name, e.g. "youtube".
        String platform = job.getSourcePlatform();
        if (platform != null && platform.toLowerCase().startsWith("youtube")) {
            List<SponsorSegment> segments = sponsorBlock.fetchSegments(sourceUrl);
            if (!segments.isEmpty()) {
                writeSponsorBlockData(jobId, segments, jobDir);
            }
            log.debug("Job {} SponsorBlock segments: {}", jobId, segments.size());
        }

        // ── Step 4: ffmpeg audio extraction ───────────────────────────────────
        try {
            audioExtractor.extract(sourceFile, audioDir);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            failJob(jobId, "Audio extraction failed: " + describe(e));
            return;
        }
        log.info("Job {} audio extracted", jobId);

        // ── Transition: DOWNLOADING → TRANSCRIBING ─────────────────────────────
        final String hash = fileHash;
        txTemplate.execute(status -> {
            VideoJob j = jobRepository.findById(jobId)
                    .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));
            if (j.getState() != VideoJobState.DOWNLOADING) {
                log.warn("Job {} is no longer DOWNLOADING (state={}); skipping transition",
                        jobId, j.getState());
                return null;
            }
            j.finishDownloading(hash);
            return jobRepository.save(j);
        });
        log.info("Job {} → TRANSCRIBING", jobId);

        // ── Step 5: create TRANSCRIBE tasks from audio chunks ─────────────────
        try {
            transcribeSetup.setup(jobId);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            failJob(jobId, "Transcribe setup failed: " + describe(e));
        }
    }

    /**
     * The failure for the job page. File-system errors only carry the path as
     * their message ("/data/jobs/..."), so their type goes in front.
     */
    static String describe(Exception e) {
        if (e instanceof FileSystemException fse) {
            return fse.getClass().getSimpleName() + " (" + fse.getFile()
                    + (fse.getReason() != null ? ": " + fse.getReason() : "") + ")";
        }
        return e.getMessage();
    }

    private void failJob(UUID jobId, String reason) {
        log.error("Job {} failed: {}", jobId, reason);
        txTemplate.execute(status -> {
            VideoJob j = jobRepository.findById(jobId).orElse(null);
            if (j == null || j.isTerminal()) return j;   // deleted or cancelled meanwhile: leave it
            j.fail(reason);
            return jobRepository.save(j);
        });
    }

    private void writeSponsorBlockData(UUID jobId, List<SponsorSegment> segments, Path jobDir) {
        try {
            Files.createDirectories(jobDir);
            Path file = jobDir.resolve("sponsorblock.json");
            try (OutputStream out = Files.newOutputStream(file)) {
                objectMapper.writeValue(out, segments);
            }
        } catch (IOException e) {
            // Non-fatal — the Claude analysis step can run without SponsorBlock data.
            log.warn("Job {} could not write sponsorblock.json: {}", jobId, e.getMessage());
        }
    }

    /**
     * Walks up from {@code path} until it finds an existing ancestor, to check its disk store.
     * Needed because the job directory may not exist yet when we do the space check.
     */
    private static Path nearestExistingAncestor(Path path) {
        Path p = path.toAbsolutePath();
        while (p != null && !Files.exists(p)) {
            p = p.getParent();
        }
        return p != null ? p : Path.of(".");
    }

    static String sha256Hex(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
