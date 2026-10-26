package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.server.llm.LlmClient;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.repository.TranscriptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Claude fixes words whisper misheard (DESIGN.md §10.5). Only the text of a
 * word may change — never the number of words or their times, which keep the
 * karaoke subtitles in sync. Never blocks the job: on any failure the
 * transcript stays as whisper left it.
 */
@Service
public class TranscriptCorrectionService {

    private static final Logger log = LoggerFactory.getLogger(TranscriptCorrectionService.class);

    /** Segments per Claude call, so a long video doesn't hinge on one huge answer. */
    static final int BATCH_SIZE = 150;
    static final int MAX_RETRIES = 2;
    /** A "word" longer than this is Claude rewriting, not correcting. */
    static final int MAX_WORD_LENGTH = 40;
    static final String CORRECTED_FLAG = "correctedByClaude";

    static final String PROMPT_TEMPLATE = """
            Eres un corrector de transcripciones automáticas de vídeo (idioma: %s). \
            El vídeo se titula "%s".

            Recibes por stdin un JSON con segmentos numerados; cada uno es la lista de \
            palabras que reconoció el sistema de voz. Corrige SOLO las palabras mal \
            reconocidas: errores de ortografía, nombres propios, palabras que no tienen \
            sentido en el contexto. No reformules, no cambies el estilo, no quites \
            muletillas ni repeticiones, no traduzcas.

            Reglas estrictas:
            - Devuelve SOLO los segmentos que cambias.
            - Cada segmento devuelto debe tener EXACTAMENTE el mismo número de palabras \
            que el original, en el mismo orden: no unas, partas, añadas ni quites palabras.
            - La puntuación va pegada a su palabra, como en el original.

            Responde SOLO con JSON, sin texto alrededor:
            {"segments": [{"id": "3", "words": ["palabra", "corregida,", "aquí."]}]}
            Si no hay nada que corregir: {"segments": []}
            """;

    private final LlmClient llmClient;
    private final TranscriptRepository transcripts;
    private final SettingService settings;
    private final LogEventService logEvents;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public TranscriptCorrectionService(LlmClient llmClient,
                                       TranscriptRepository transcripts,
                                       SettingService settings,
                                       LogEventService logEvents,
                                       ObjectMapper objectMapper,
                                       TransactionTemplate tx) {
        this.llmClient = llmClient;
        this.transcripts = transcripts;
        this.settings = settings;
        this.logEvents = logEvents;
        this.objectMapper = objectMapper;
        this.tx = tx;
    }

    /** Corrects the job's stitched transcript once; does nothing if off or already done. */
    @SuppressWarnings("unchecked")
    public void correct(UUID jobId) {
        if (!settings.correctTranscript()) return;

        record Loaded(Map<String, Object> words, String language, String title) {}
        Loaded loaded = tx.execute(s -> transcripts.findByVideoJobId(jobId).map(t -> {
            VideoJob job = t.getVideoJob();
            return new Loaded(t.getWordsJson(), t.getLanguage(), job.getTitle());
        }).orElse(null));
        if (loaded == null || Boolean.TRUE.equals(loaded.words().get(CORRECTED_FLAG))) return;

        List<Map<String, Object>> segments = deepCopySegments(
                (List<Map<String, Object>>) loaded.words().getOrDefault("segments", List.of()));
        String prompt = PROMPT_TEMPLATE.formatted(
                loaded.language() == null ? "es" : loaded.language(),
                loaded.title() == null ? "" : loaded.title().replace("\"", "'"));

        int corrected = 0;
        int failedBatches = 0;
        for (int from = 0; from < segments.size(); from += BATCH_SIZE) {
            int to = Math.min(segments.size(), from + BATCH_SIZE);
            Integer applied = correctBatch(jobId, prompt, segments, from, to);
            if (applied == null) failedBatches++;
            else corrected += applied;
        }

        Map<String, Object> updated = new LinkedHashMap<>(loaded.words());
        updated.put("segments", segments);
        updated.put(CORRECTED_FLAG, true);
        final int correctedSegments = corrected;
        tx.executeWithoutResult(s -> transcripts.findByVideoJobId(jobId).ifPresent(t -> {
            t.correctWords(updated);
            transcripts.save(t);
        }));

        log.info("Job {}: transcript corrected by Claude — {} segment(s) changed, {} batch(es) failed",
                jobId, correctedSegments, failedBatches);
        logEvents.info("transcripción", "Claude corrigió " + correctedSegments + " segmento(s)"
                + (failedBatches > 0 ? " (" + failedBatches + " lote(s) sin corregir)" : ""), jobId.toString());
    }

    /** @return segments changed in this batch, or null when Claude failed for it */
    private Integer correctBatch(UUID jobId, String prompt, List<Map<String, Object>> segments, int from, int to) {
        String input = batchJson(segments, from, to);
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String raw = llmClient.call(prompt, input);
                JsonNode response = objectMapper.readTree(LlmClient.extractJson(raw));
                return apply(segments, response);
            } catch (IOException | RuntimeException e) {
                log.warn("Job {}: transcript correction batch {}-{} attempt {}/{} failed: {}",
                        jobId, from, to, attempt, MAX_RETRIES, e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** {"segments":[{"id":"12","words":["…"]}, …]} for segments [from, to). */
    @SuppressWarnings("unchecked")
    String batchJson(List<Map<String, Object>> segments, int from, int to) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode out = root.putArray("segments");
        for (int i = from; i < to; i++) {
            List<Map<String, Object>> words = (List<Map<String, Object>>) segments.get(i).getOrDefault("words", List.of());
            if (words.isEmpty()) continue;
            ObjectNode seg = out.addObject();
            seg.put("id", String.valueOf(i));
            ArrayNode ws = seg.putArray("words");
            words.forEach(w -> ws.add(String.valueOf(w.get("text"))));
        }
        return objectMapper.writeValueAsString(root);
    }

    /**
     * Applies Claude's corrections in place, segment by segment. A segment is
     * skipped when its id is unknown, the word count differs, or a word is
     * empty or implausibly long. Times are never touched.
     *
     * @return how many segments changed
     */
    @SuppressWarnings("unchecked")
    static int apply(List<Map<String, Object>> segments, JsonNode response) {
        int changed = 0;
        for (JsonNode seg : response.path("segments")) {
            int id;
            try {
                id = Integer.parseInt(seg.path("id").asText(""));
            } catch (NumberFormatException e) {
                continue;
            }
            if (id < 0 || id >= segments.size()) continue;

            List<Map<String, Object>> words = (List<Map<String, Object>>) segments.get(id).get("words");
            JsonNode fixed = seg.path("words");
            if (words == null || !fixed.isArray() || fixed.size() != words.size()) continue;

            List<String> texts = new ArrayList<>();
            boolean valid = true;
            for (JsonNode w : fixed) {
                String t = w.asText("").strip();
                if (t.isEmpty() || t.length() > MAX_WORD_LENGTH || t.contains(" ")) {
                    valid = false;
                    break;
                }
                texts.add(t);
            }
            if (!valid) continue;

            boolean differs = false;
            for (int i = 0; i < words.size(); i++) {
                if (!texts.get(i).equals(words.get(i).get("text"))) {
                    words.get(i).put("text", texts.get(i));
                    differs = true;
                }
            }
            if (differs) {
                segments.get(id).put("text", texts.stream().collect(Collectors.joining(" ")));
                changed++;
            }
        }
        return changed;
    }

    /** Mutable copies, so corrections never touch the entity's loaded state directly. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> deepCopySegments(List<Map<String, Object>> segments) {
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> seg : segments) {
            Map<String, Object> s = new LinkedHashMap<>(seg);
            List<Map<String, Object>> words = new ArrayList<>();
            for (Map<String, Object> w : (List<Map<String, Object>>) seg.getOrDefault("words", List.of())) {
                words.add(new LinkedHashMap<>(w));
            }
            s.put("words", words);
            copy.add(s);
        }
        return copy;
    }
}
