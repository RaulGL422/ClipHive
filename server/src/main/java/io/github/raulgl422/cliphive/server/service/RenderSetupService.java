package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.protocol.state.VideoJobState;
import io.github.raulgl422.cliphive.server.model.Clip;
import io.github.raulgl422.cliphive.server.model.ContentAnalysis;
import io.github.raulgl422.cliphive.server.model.Task;
import io.github.raulgl422.cliphive.server.model.Transcript;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.model.enums.ReviewStatus;
import io.github.raulgl422.cliphive.server.repository.ClipRepository;
import io.github.raulgl422.cliphive.server.repository.ContentAnalysisRepository;
import io.github.raulgl422.cliphive.server.repository.TaskRepository;
import io.github.raulgl422.cliphive.server.repository.TranscriptRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import io.github.raulgl422.cliphive.server.service.tool.VideoClipper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates the server-side preparation for a job that has just finished
 * transcription (docs/DESIGN.md §4 steps 5-10). Its own steps, each skipped
 * when already done so the job can come back after a review:
 *
 * <ol>
 *   <li>Stitch the per-chunk transcripts, then let Claude correct them (§10.5).
 *   <li>Wait for the selection in manual mode (§4.1); otherwise run Claude's
 *       content analysis (§10.2).
 *   <li>Plan the clips from the proposals, the selection or the fallback planner.
 *   <li>Pause in AWAITING_REVIEW when the analysis needs a review.
 *   <li>Cut each clip's piece of the source with {@code ffmpeg -c copy} and
 *       point its RENDER_CLIP task at it; write titles and descriptions (§10.3).
 *   <li>Transition the job to RENDERING.
 * </ol>
 *
 * <p>Called by {@link RenderTriggerScheduler} on its own thread when the job
 * is in ANALYZING state. On any error the job is transitioned to FAILED.
 */
@Service
public class RenderSetupService {

    private static final Logger log = LoggerFactory.getLogger(RenderSetupService.class);

    private final TranscriptStitchService stitchService;
    private final ContentAnalysisService contentAnalysisService;
    private final ClipPlannerService clipPlannerService;
    private final ClipMetadataService clipMetadataService;
    private final VideoClipper videoClipper;
    private final TranscriptRepository transcriptRepository;
    private final ContentAnalysisRepository contentAnalysisRepository;
    private final VideoJobRepository jobRepository;
    private final ClipRepository clipRepository;
    private final TaskRepository taskRepository;
    private final TransactionTemplate txTemplate;
    private final JobFiles files;
    private final TranscriptCorrectionService transcriptCorrectionService;

    public RenderSetupService(TranscriptStitchService stitchService,
                              ContentAnalysisService contentAnalysisService,
                              ClipPlannerService clipPlannerService,
                              ClipMetadataService clipMetadataService,
                              VideoClipper videoClipper,
                              TranscriptRepository transcriptRepository,
                              ContentAnalysisRepository contentAnalysisRepository,
                              VideoJobRepository jobRepository,
                              ClipRepository clipRepository,
                              TaskRepository taskRepository,
                              TransactionTemplate txTemplate,
                              JobFiles files,
                              TranscriptCorrectionService transcriptCorrectionService) {
        this.stitchService = stitchService;
        this.contentAnalysisService = contentAnalysisService;
        this.clipPlannerService = clipPlannerService;
        this.clipMetadataService = clipMetadataService;
        this.videoClipper = videoClipper;
        this.transcriptRepository = transcriptRepository;
        this.contentAnalysisRepository = contentAnalysisRepository;
        this.jobRepository = jobRepository;
        this.clipRepository = clipRepository;
        this.taskRepository = taskRepository;
        this.txTemplate = txTemplate;
        this.files = files;
        this.transcriptCorrectionService = transcriptCorrectionService;
    }

    /**
     * Runs the full render-setup pipeline for {@code jobId}.
     * Must be called on a non-transactional thread (it manages its own transactions).
     */
    public void setup(UUID jobId) {
        try {
            doSetup(jobId);
        } catch (Exception e) {
            log.error("Render setup failed for job {}: {}", jobId, e.getMessage(), e);
            txTemplate.executeWithoutResult(status ->
                    jobRepository.findById(jobId).filter(job -> !job.isTerminal()).ifPresent(job -> {
                        job.fail("Render setup failed: " + e.getMessage());
                        jobRepository.save(job);
                    }));
        }
    }

    @SuppressWarnings("unchecked")
    private void doSetup(UUID jobId) throws IOException, InterruptedException {
        // Step 1: stitch transcript (idempotent)
        Transcript existingTranscript = transcriptRepository.findByVideoJobId(jobId).orElse(null);
        if (existingTranscript == null) {
            log.info("Job {}: stitching transcript", jobId);
            stitchService.stitch(jobId);
        } else {
            log.info("Job {}: transcript already stitched, skipping", jobId);
        }

        // Step 1b: Claude fixes misheard words, times untouched (idempotent — §10.5)
        transcriptCorrectionService.correct(jobId);

        Transcript transcript = transcriptRepository.findByVideoJobId(jobId)
                .orElseThrow(() -> new IOException("Transcript not found for job " + jobId));
        Map<String, Object> stitched = transcript.getWordsJson();

        // Manual clip mode (DESIGN.md §4.1): wait for the ranges marked in the
        // superadmin; confirming them puts the job back in ANALYZING.
        VideoJob current = jobRepository.findById(jobId)
                .orElseThrow(() -> new IOException("Job not found: " + jobId));
        boolean manual = current.isManual();
        if (manual && !current.isSelectionConfirmed()) {
            txTemplate.executeWithoutResult(status ->
                    jobRepository.findById(jobId).ifPresent(j -> {
                        if (j.getState() == VideoJobState.ANALYZING) {
                            j.awaitingSelection();
                            jobRepository.save(j);
                            log.info("Job {} → AWAITING_SELECTION (manual clips not confirmed yet)", jobId);
                        }
                    }));
            return;
        }

        // Step 2: Claude content analysis (idempotent — docs/DESIGN.md §10.2).
        // Skipped in manual mode: the clips are already chosen.
        ContentAnalysis analysis = manual ? null
                : contentAnalysisRepository.findByVideoJobId(jobId).orElse(null);
        if (manual) {
            log.info("Job {}: manual clip mode, skipping content analysis", jobId);
        } else if (analysis == null) {
            log.info("Job {}: running Claude content analysis", jobId);
            analysis = contentAnalysisService.analyze(jobId);
        } else {
            log.info("Job {}: content analysis already done, skipping", jobId);
        }

        // Step 3: plan clips (idempotent — use Claude proposals when available)
        List<Clip> existingClips = clipRepository.findByVideoJobIdOrderByClipIndex(jobId);
        if (existingClips.isEmpty()) {
            VideoJob job = jobRepository.findById(jobId)
                    .orElseThrow(() -> new IOException("Job not found: " + jobId));
            List<Map<String, Object>> proposals =
                    (analysis != null) ? analysis.getProposedClipsJson() : null;
            if (manual) {
                List<Map<String, Object>> ranges =
                        ManualSelectionService.snapToWords(job.getManualRangesJson(), stitched);
                log.info("Job {}: planning {} manually chosen clip(s)", jobId, ranges.size());
                clipPlannerService.planFromProposals(jobId, ranges, stitched, job.getFpsMode());
            } else if (proposals != null && !proposals.isEmpty()) {
                log.info("Job {}: planning {} clip(s) from Claude proposals", jobId, proposals.size());
                clipPlannerService.planFromProposals(jobId, proposals, stitched, job.getFpsMode());
            } else {
                log.info("Job {}: no Claude proposals — using provisional planner", jobId);
                clipPlannerService.plan(jobId, stitched, job.getFpsMode());
            }
            existingClips = clipRepository.findByVideoJobIdOrderByClipIndex(jobId);
        } else {
            log.info("Job {}: {} clip(s) already planned, skipping", jobId, existingClips.size());
        }

        if (existingClips.isEmpty()) {
            throw new IOException("No clips planned for job " + jobId);
        }

        // Between steps 3 and 4: if the analysis is PENDING_REVIEW (review mode on,
        // or Claude failed and the cuts are provisional, R12), pause here. The admin approves from the superadmin review screen, which sets
        // the job back to ANALYZING so this method re-enters and continues (steps 1-3
        // are idempotent and will be skipped on the second pass).
        final ContentAnalysis finalAnalysis = analysis;
        if (finalAnalysis != null
                && finalAnalysis.getReviewStatus() == ReviewStatus.PENDING_REVIEW) {
            final int count = existingClips.size();
            txTemplate.executeWithoutResult(status ->
                    jobRepository.findById(jobId).ifPresent(j -> {
                        if (j.getState() == VideoJobState.ANALYZING) {
                            j.awaitingReview();
                            jobRepository.save(j);
                            log.info("Job {} → AWAITING_REVIEW ({} clips planned, pending admin review)",
                                     jobId, count);
                        }
                    }));
            return;
        }

        // Step 4: cut per-clip video pieces
        VideoJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IOException("Job not found: " + jobId));
        Path sourceFile = resolveSourceFile(jobId);

        for (Clip clip : existingClips) {
            List<Task> clipTasks = taskRepository.findByClipId(clip.getId());
            if (clipTasks.isEmpty()) {
                log.warn("Job {}: no task found for clip {} — skipping video cut", jobId, clip.getClipIndex());
                continue;
            }
            Task task = clipTasks.get(0);
            Map<String, Object> params = task.getParametersJson();

            // Skip if source path already set
            String existingPath = (String) params.get("sourceClipPath");
            if (existingPath != null && !existingPath.isBlank()) {
                log.debug("Job {}: clip {} source already cut, skipping", jobId, clip.getClipIndex());
                continue;
            }

            List<Map<String, Object>> keptRanges =
                    (List<Map<String, Object>>) params.get("keptRanges");
            Map<String, Object> firstRange = keptRanges.get(0);
            long startMs = ((Number) firstRange.get("startMs")).longValue();
            long endMs   = ((Number) firstRange.get("endMs")).longValue();

            Path clipDir  = files.clipDir(jobId, clip.getClipIndex());
            Path destFile = clipDir.resolve("source.mp4");
            double pieceStart = videoClipper.cut(sourceFile, startMs, endMs, destFile);

            // Store relative path (forward slashes, relative to dataDir)
            String relPath = files.toStored(destFile);

            // Update task parameters with source path and piece offset
            Map<String, Object> updatedParams = new LinkedHashMap<>(params);
            updatedParams.put("sourceClipPath", relPath);
            updatedParams.put("pieceStartSeconds", pieceStart);

            final Map<String, Object> finalParams = updatedParams;
            txTemplate.executeWithoutResult(status -> {
                taskRepository.findById(task.getId()).ifPresent(t -> {
                    t.setParametersJson(finalParams);
                    taskRepository.save(t);
                });
            });

            log.info("Job {}: clip {} source cut to {} (piece starts at {:.3f}s)",
                     jobId, clip.getClipIndex(), destFile.getFileName(), pieceStart);
        }

        // Step 5: generate per-clip title + description (idempotent — skips clips that already have one)
        clipMetadataService.generateForJob(jobId, transcript, existingClips);

        // Step 6: transition job to RENDERING
        final int clipCount = existingClips.size();
        txTemplate.executeWithoutResult(status ->
                jobRepository.findById(jobId).ifPresent(j -> {
                    if (j.getState() == VideoJobState.ANALYZING) {
                        j.startRendering();
                        jobRepository.save(j);
                        log.info("Job {} → RENDERING ({} clips queued)", jobId, clipCount);
                    }
                }));
    }

    // ── Path helpers ──────────────────────────────────────────────────────────

    private Path resolveSourceFile(UUID jobId) throws IOException {
        Path source = files.sourceVideo(jobId);
        if (source == null) throw new IOException("No source video found for job " + jobId);
        return source;
    }
}
