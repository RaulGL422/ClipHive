package io.github.raulgl422.cliphive.server.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.github.raulgl422.cliphive.protocol.state.TaskState;
import io.github.raulgl422.cliphive.protocol.state.TaskType;
import io.github.raulgl422.cliphive.server.model.Task;
import io.github.raulgl422.cliphive.server.model.Transcript;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.repository.TaskRepository;
import io.github.raulgl422.cliphive.server.repository.TranscriptRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import io.github.raulgl422.cliphive.server.service.tool.AudioChunker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Merges per-chunk whisper.cpp JSON transcripts into a single, time-adjusted
 * transcript stored in the {@code transcript} table (docs/DESIGN.md §4, step 5).
 *
 * <p>Whisper is run on overlapping chunks so sentences are never split at a
 * boundary. When stitching, words that fall in the "canonical" range of each
 * chunk are kept; words in the overlap portion (either echoed from the
 * previous or the next chunk) are discarded.
 *
 * <p>The canonical range for chunk {@code i} is
 * {@code [i * AudioChunker.CHUNK_DURATION_SECONDS, (i+1) * AudioChunker.CHUNK_DURATION_SECONDS)}.
 * The last chunk keeps everything until the end of the audio.
 *
 * <p>Output format stored in {@code transcript.words_json}:
 * <pre>{@code
 * {
 *   "language": "es",
 *   "durationMs": 123456,
 *   "segments": [
 *     {
 *       "startMs": 1000,
 *       "endMs": 4500,
 *       "text": "Hello world",
 *       "words": [
 *         { "text": "Hello", "startMs": 1000, "endMs": 1800 },
 *         { "text": "world", "startMs": 1900, "endMs": 4500 }
 *       ]
 *     }
 *   ]
 * }
 * }</pre>
 */
@Service
public class TranscriptStitchService {

    private static final Logger log = LoggerFactory.getLogger(TranscriptStitchService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final TaskRepository taskRepository;
    private final VideoJobRepository jobRepository;
    private final TranscriptRepository transcriptRepository;
    private final TransactionTemplate txTemplate;
    private final JobFiles files;
    private final ObjectMapper objectMapper;

    public TranscriptStitchService(TaskRepository taskRepository,
                                   VideoJobRepository jobRepository,
                                   TranscriptRepository transcriptRepository,
                                   TransactionTemplate txTemplate,
                                   JobFiles files,
                                   ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.jobRepository = jobRepository;
        this.transcriptRepository = transcriptRepository;
        this.txTemplate = txTemplate;
        this.files = files;
        this.objectMapper = objectMapper;
    }

    /**
     * Reads all chunk JSON files for {@code jobId}, stitches them, and saves
     * the result. Should only be called once all TRANSCRIBE tasks are DONE.
     *
     * @throws IOException if any chunk JSON file is missing or unparseable
     */
    public void stitch(UUID jobId) throws IOException {
        List<Task> chunks = txTemplate.execute(status ->
                taskRepository.findByVideoJobIdAndTypeAndState(jobId, TaskType.TRANSCRIBE, TaskState.DONE).stream()
                        .sorted(Comparator.comparingInt(t ->
                                ((Number) t.getParametersJson().getOrDefault("chunkIndex", 0)).intValue()))
                        .toList());

        if (chunks == null || chunks.isEmpty()) {
            throw new IOException("No completed TRANSCRIBE tasks found for job " + jobId);
        }

        int totalChunks = ((Number) chunks.get(0).getParametersJson()
                .getOrDefault("totalChunks", chunks.size())).intValue();

        List<Map<String, Object>> allSegments = new ArrayList<>();
        String language = "es";
        long maxEndMs = 0;

        for (Task task : chunks) {
            Map<String, Object> params = task.getParametersJson();
            int chunkIndex = ((Number) params.getOrDefault("chunkIndex", 0)).intValue();
            double chunkStartSeconds = ((Number) params.getOrDefault("chunkStartSeconds", 0.0)).doubleValue();
            long chunkStartMs = Math.round(chunkStartSeconds * 1000);
            boolean isLastChunk = (chunkIndex == totalChunks - 1);

            // Canonical range for this chunk (in absolute video ms)
            long canonicalStartMs = (long) chunkIndex * AudioChunker.CHUNK_DURATION_SECONDS * 1000L;
            long canonicalEndMs = isLastChunk ? Long.MAX_VALUE
                    : (long) (chunkIndex + 1) * AudioChunker.CHUNK_DURATION_SECONDS * 1000L;

            Path chunkJson = resolveChunkJson(params);
            Map<String, Object> whisperData = objectMapper.readValue(chunkJson.toFile(), MAP_TYPE);

            if (whisperData.containsKey("result")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) whisperData.get("result");
                if (result.containsKey("language")) language = result.get("language").toString();
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> transcription =
                    (List<Map<String, Object>>) whisperData.getOrDefault("transcription", List.of());

            for (Map<String, Object> segment : transcription) {
                List<Map<String, Object>> words = extractWords(segment, chunkStartMs,
                        canonicalStartMs, canonicalEndMs);
                if (words.isEmpty()) continue;

                long segStart = (long) words.get(0).get("startMs");
                long segEnd   = (long) words.get(words.size() - 1).get("endMs");
                maxEndMs = Math.max(maxEndMs, segEnd);

                String text = words.stream()
                        .map(w -> w.get("text").toString().strip())
                        .reduce("", (a, b) -> a.isBlank() ? b : a + " " + b);

                Map<String, Object> seg = new LinkedHashMap<>();
                seg.put("startMs", segStart);
                seg.put("endMs", segEnd);
                seg.put("text", text);
                seg.put("words", words);
                allSegments.add(seg);
            }

            log.debug("Job {}: chunk {} → {} segments after dedup", jobId, chunkIndex, allSegments.size());
        }

        Map<String, Object> stitched = new LinkedHashMap<>();
        stitched.put("language", language);
        stitched.put("durationMs", maxEndMs);
        stitched.put("segments", allSegments);

        final String finalLanguage = language;
        txTemplate.executeWithoutResult(status -> {
            VideoJob job = jobRepository.findById(jobId)
                    .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));
            Transcript transcript = new Transcript(job, finalLanguage, stitched);
            transcriptRepository.save(transcript);
        });

        log.info("Job {}: transcript stitched ({} segments, {}ms)", jobId, allSegments.size(), maxEndMs);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds words from whisper's tokens. Tokens are sub-word pieces: a token
     * that starts with a space begins a new word, any other one ("venidos"
     * after " bien", or a "," after " todos") continues the previous word and
     * extends its end time. Treating every token as a word gave subtitles and
     * Claude "bien venidos" and "todos , bien".
     */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> extractWords(Map<String, Object> segment,
                                                  long chunkStartMs,
                                                  long canonicalStartMs,
                                                  long canonicalEndMs) {
        List<Map<String, Object>> words = new ArrayList<>();
        List<Map<String, Object>> tokens =
                (List<Map<String, Object>>) segment.getOrDefault("tokens", List.of());

        Map<String, Object> current = null;
        for (Map<String, Object> token : tokens) {
            String raw = token.getOrDefault("text", "").toString();
            String text = raw.strip();
            if (text.isEmpty() || text.startsWith("[")) continue; // skip [_BEG_], [_TT_n], [BLANK_AUDIO]…

            Map<String, Object> offsets = (Map<String, Object>) token.getOrDefault("offsets", Map.of());
            long relStartMs = ((Number) offsets.getOrDefault("from", 0)).longValue();
            long relEndMs   = ((Number) offsets.getOrDefault("to", relStartMs)).longValue();

            boolean startsWord = current == null || Character.isWhitespace(raw.charAt(0));
            if (startsWord) {
                current = new LinkedHashMap<>();
                current.put("text", text);
                current.put("startMs", chunkStartMs + relStartMs);
                current.put("endMs", chunkStartMs + relEndMs);
                words.add(current);
            } else {
                current.put("text", current.get("text") + text);
                current.put("endMs", chunkStartMs + relEndMs);
            }
        }

        // Keep only words that start in this chunk's canonical range. Filtering the
        // finished words (not tokens) keeps a word's pieces together across the cut.
        words.removeIf(w -> {
            long start = (long) w.get("startMs");
            return start < canonicalStartMs || start >= canonicalEndMs;
        });
        return words;
    }

    private Path resolveChunkJson(Map<String, Object> params) throws IOException {
        // chunkPath is like "jobs/{id}/chunks/chunk_000.wav" (stored with forward slashes)
        Object rawPath = params.get("chunkPath");
        if (rawPath == null) throw new IOException("chunkPath missing from task parameters");
        // Strip extension and add .json — chunk_000.wav → chunk_000.json
        String chunkWavRel = rawPath.toString().replace('\\', '/');
        String chunkJsonRel = chunkWavRel.endsWith(".wav")
                ? chunkWavRel.substring(0, chunkWavRel.length() - 4) + ".json"
                : chunkWavRel + ".json";

        Path jsonPath = files.fromStored(chunkJsonRel);
        if (!Files.exists(jsonPath)) {
            throw new IOException("Transcript chunk not found: " + jsonPath);
        }
        return jsonPath;
    }
}
