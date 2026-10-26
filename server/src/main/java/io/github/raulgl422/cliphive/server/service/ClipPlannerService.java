package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.server.model.Clip;
import io.github.raulgl422.cliphive.server.model.Task;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.repository.ClipRepository;
import io.github.raulgl422.cliphive.server.repository.TaskRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns clip ranges into {@link Clip} rows and their RENDER_CLIP tasks.
 *
 * <p>{@link #planFromProposals} is the normal path: the ranges come from
 * Claude's editorial plan (docs/DESIGN.md §10.2) or from a manual selection
 * (§4.1). {@link #plan} is the fallback when there are none (R12): it groups
 * words into ~{@value #TARGET_CLIP_DURATION_MS} ms clips, cutting at natural
 * pauses (a gap of at least {@value #PAUSE_THRESHOLD_MS} ms between a segment's
 * end and the next one's start), each with a single kept range and no inner cuts.
 *
 * <p>Creates a RENDER_CLIP {@link Task} for each clip, including the
 * per-clip word list needed by the worker's ASS generator.
 */
@Service
public class ClipPlannerService {

    /** Target clip duration of the fallback planner, in ms. */
    static final long TARGET_CLIP_DURATION_MS = 60_000;

    /** Minimum clip duration — clips shorter than this are merged with the previous. */
    static final long MIN_CLIP_DURATION_MS = 15_000;

    /** A gap this long or more between segments is a cut-candidate pause. */
    static final long PAUSE_THRESHOLD_MS = 500;

    private static final Logger log = LoggerFactory.getLogger(ClipPlannerService.class);

    private final ClipRepository clipRepository;
    private final TaskRepository taskRepository;
    private final VideoJobRepository jobRepository;
    private final TransactionTemplate txTemplate;
    private final SettingService settings;

    /** Output frame height the worker renders (1080×1920). */
    static final int FRAME_HEIGHT = 1920;

    public ClipPlannerService(ClipRepository clipRepository,
                              TaskRepository taskRepository,
                              VideoJobRepository jobRepository,
                              TransactionTemplate txTemplate,
                              SettingService settings) {
        this.clipRepository = clipRepository;
        this.taskRepository = taskRepository;
        this.jobRepository = jobRepository;
        this.txTemplate = txTemplate;
        this.settings = settings;
    }

    /** Subtitle distance from the bottom in px, from the superadmin setting (DESIGN.md §9.2). */
    int subtitleMarginV() {
        int percent = settings != null ? settings.subtitlesBottomPercent() : 25;
        return Math.round(FRAME_HEIGHT * percent / 100f);
    }

    /**
     * Plans clips for the given job from its stitched transcript, then creates
     * one RENDER_CLIP task per clip. The transcript must already be saved.
     *
     * @param jobId       the video job
     * @param stitched    the normalized transcript ({@code segments}, {@code language} keys)
     * @param fpsMode     fps mode string from the job ("auto", "30", "60")
     */
    @SuppressWarnings("unchecked")
    public void plan(UUID jobId, Map<String, Object> stitched, String fpsMode) {
        List<Map<String, Object>> segments =
                (List<Map<String, Object>>) stitched.getOrDefault("segments", List.of());
        String language = stitched.getOrDefault("language", "es").toString();

        List<ClipRange> ranges = buildRanges(segments);
        log.info("Job {}: {} provisional clip(s) from {} segments", jobId, ranges.size(), segments.size());
        persistRanges(jobId, ranges, language, fpsMode);
    }

    // ── Range building ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    List<ClipRange> buildRanges(List<Map<String, Object>> segments) {
        if (segments.isEmpty()) return List.of();

        // Collect all words across all segments
        List<WordInfo> allWords = allWords(segments);
        if (allWords.isEmpty()) return List.of();

        // Find pause points: indices where a gap >= threshold exists
        List<Integer> pauseIndices = new ArrayList<>();
        for (int i = 0; i < segments.size() - 1; i++) {
            long segEnd   = ((Number) segments.get(i).get("endMs")).longValue();
            long nextStart = ((Number) segments.get(i + 1).get("startMs")).longValue();
            if (nextStart - segEnd >= PAUSE_THRESHOLD_MS) {
                // Find the word index that corresponds to the end of segment i
                for (int wi = allWords.size() - 1; wi >= 0; wi--) {
                    if (allWords.get(wi).endMs() <= segEnd) {
                        pauseIndices.add(wi + 1); // cut after this word
                        break;
                    }
                }
            }
        }

        // Build clip ranges by grouping words up to the target duration,
        // preferring to cut at a pause point.
        List<ClipRange> result = new ArrayList<>();
        int clipStart = 0;

        while (clipStart < allWords.size()) {
            long clipStartMs = allWords.get(clipStart).startMs();
            int clipEnd = allWords.size() - 1; // default: rest of words

            // Find the latest word index where duration <= target
            for (int wi = clipStart; wi < allWords.size(); wi++) {
                long duration = allWords.get(wi).endMs() - clipStartMs;
                if (duration > TARGET_CLIP_DURATION_MS) {
                    // We've gone past target — find nearest pause at or before this word
                    int cutAt = wi; // fallback: cut here
                    for (int pi = pauseIndices.size() - 1; pi >= 0; pi--) {
                        int pause = pauseIndices.get(pi);
                        if (pause > clipStart && pause <= wi) {
                            cutAt = pause;
                            break;
                        }
                    }
                    clipEnd = cutAt - 1;
                    break;
                }
            }

            List<Map<String, Object>> words = new ArrayList<>();
            for (int wi = clipStart; wi <= clipEnd; wi++) {
                WordInfo w = allWords.get(wi);
                Map<String, Object> wm = new LinkedHashMap<>();
                wm.put("text", w.text());
                wm.put("startMs", w.startMs());
                wm.put("endMs", w.endMs());
                words.add(wm);
            }

            long rangeStart = allWords.get(clipStart).startMs();
            long rangeEnd   = allWords.get(clipEnd).endMs();
            long duration   = rangeEnd - rangeStart;

            // If this clip is too short and not the first one, merge into previous
            if (duration < MIN_CLIP_DURATION_MS && !result.isEmpty()) {
                ClipRange prev = result.remove(result.size() - 1);
                List<Map<String, Object>> merged = new ArrayList<>(prev.words());
                merged.addAll(words);
                result.add(new ClipRange(prev.startMs(), rangeEnd, merged));
            } else {
                result.add(new ClipRange(rangeStart, rangeEnd, words));
            }

            clipStart = clipEnd + 1;
        }

        return result;
    }

    /**
     * Plans clips from Claude-proposed or manually chosen ranges (docs/DESIGN.md §10.2, §4.1).
     * Each proposal is {@code {startMs, endMs}}; words are sourced from the
     * stitched transcript for those time bounds.
     */
    @SuppressWarnings("unchecked")
    public void planFromProposals(UUID jobId,
                                   List<Map<String, Object>> proposals,
                                   Map<String, Object> stitched,
                                   String fpsMode) {
        List<Map<String, Object>> segments =
                (List<Map<String, Object>>) stitched.getOrDefault("segments", List.of());
        String language = stitched.getOrDefault("language", "es").toString();

        // Collect all words from transcript
        List<WordInfo> allWords = allWords(segments);

        List<ClipRange> ranges = new ArrayList<>();
        for (Map<String, Object> proposal : proposals) {
            long startMs = ((Number) proposal.get("startMs")).longValue();
            long endMs   = ((Number) proposal.get("endMs")).longValue();
            List<Map<String, Object>> words = new ArrayList<>();
            for (WordInfo w : allWords) {
                if (w.startMs() >= startMs && w.endMs() <= endMs) {
                    Map<String, Object> wm = new LinkedHashMap<>();
                    wm.put("text", w.text());
                    wm.put("startMs", w.startMs());
                    wm.put("endMs", w.endMs());
                    words.add(wm);
                }
            }
            ranges.add(new ClipRange(startMs, endMs, words,
                    proposal.get("section") instanceof String s && !s.isBlank() ? s : null,
                    proposal.get("part") instanceof Number p ? p.intValue() : null,
                    proposal.get("parts") instanceof Number t ? t.intValue() : null));
        }

        log.info("Job {}: {} Claude-proposed clip(s) from {} proposals", jobId, ranges.size(), proposals.size());
        persistRanges(jobId, ranges, language, fpsMode);
    }

    /** Shared persistence logic for {@link #plan} and {@link #planFromProposals}. */
    private void persistRanges(UUID jobId, List<ClipRange> ranges, String language, String fpsMode) {
        int marginV = subtitleMarginV();
        txTemplate.executeWithoutResult(status -> {
            VideoJob job = jobRepository.findById(jobId)
                    .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));

            for (int i = 0; i < ranges.size(); i++) {
                ClipRange range = ranges.get(i);

                Map<String, Object> keptRange = new LinkedHashMap<>();
                keptRange.put("startMs", range.startMs());
                keptRange.put("endMs", range.endMs());

                Clip clip = new Clip(job, i, List.of(keptRange));
                clip.setDurationSeconds((int) ((range.endMs() - range.startMs()) / 1000));
                clip.setSectionTitle(range.section());
                clip.setPartIndex(range.part());
                clip.setPartCount(range.parts());
                clipRepository.save(clip);

                Map<String, Object> params = new LinkedHashMap<>();
                params.put("clipIndex",  i);
                params.put("keptRanges", List.of(keptRange));
                params.put("words",      range.words());
                params.put("language",   language);
                params.put("fpsMode",    fpsMode != null ? fpsMode : "auto");
                params.put("sourceClipPath", "");
                params.put("pieceStartSeconds", 0.0);
                params.put("subtitleMarginV", marginV);

                Task task = Task.forRenderClip(clip, params);
                task.setPriority(i);
                taskRepository.save(task);

                log.debug("Job {}: clip {} [{}-{}ms], {} words",
                          jobId, i, range.startMs(), range.endMs(), range.words().size());
            }
        });
    }

    /** Every word of the transcript's segments, in order. */
    @SuppressWarnings("unchecked")
    private static List<WordInfo> allWords(List<Map<String, Object>> segments) {
        List<WordInfo> allWords = new ArrayList<>();
        for (Map<String, Object> seg : segments) {
            List<Map<String, Object>> words =
                    (List<Map<String, Object>>) seg.getOrDefault("words", List.of());
            for (Map<String, Object> w : words) {
                allWords.add(new WordInfo(
                        w.get("text").toString(),
                        ((Number) w.get("startMs")).longValue(),
                        ((Number) w.get("endMs")).longValue()
                ));
            }
        }
        return allWords;
    }

    // ── Value types ──────────────────────────────────────────────────────────

    record WordInfo(String text, long startMs, long endMs) {}

    record ClipRange(long startMs, long endMs, List<Map<String, Object>> words,
                     String section, Integer part, Integer parts) {
        ClipRange(long startMs, long endMs, List<Map<String, Object>> words) {
            this(startMs, endMs, words, null, null, null);
        }
    }
}
