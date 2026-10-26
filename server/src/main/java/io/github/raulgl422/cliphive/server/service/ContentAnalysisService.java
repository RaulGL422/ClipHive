package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.server.llm.LlmClient;
import io.github.raulgl422.cliphive.server.model.ContentAnalysis;
import io.github.raulgl422.cliphive.server.model.Transcript;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.model.enums.ReviewStatus;
import io.github.raulgl422.cliphive.server.repository.ContentAnalysisRepository;
import io.github.raulgl422.cliphive.server.repository.TranscriptRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import io.github.raulgl422.cliphive.server.service.tool.SponsorBlockClient;
import io.github.raulgl422.cliphive.server.service.tool.SponsorSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Content analysis with Claude (DESIGN.md §10.2, pipeline step 6): an
 * <b>editorial plan</b> of the whole video — useful start and end, filler to
 * drop, and the video split into complete thematic sections, each one clip or
 * a series of consecutive parts. {@link EditorialPlan} then guarantees the
 * rules (order, nothing skipped in full coverage, no filler inside a clip,
 * long clips split at sentence ends, short ones merged). If Claude leaves out
 * too much, it's asked once more with the missing stretches.
 */
@Service
public class ContentAnalysisService {

    static final int MAX_RETRIES = 3;
    /** Below this share of the content covered, Claude is asked again (full coverage). */
    static final double MIN_PROPOSED_COVERAGE = 0.85;

    private static final Logger log = LoggerFactory.getLogger(ContentAnalysisService.class);

    private static final String ANALYSIS_PROMPT_TEMPLATE = """
            Eres el editor de un canal de vídeos cortos verticales (YouTube Shorts, Instagram Reels, TikTok). \
            Vas a convertir este vídeo en clips. Recibes por stdin su transcripción en segmentos numerados \
            (id, startMs, endMs, text); cada segmento es una frase o un trozo de frase, y el final de cada uno \
            es una pausa donde se puede cortar.

            Haz un PLAN EDITORIAL del vídeo entero:
            1. "contenido_inicio" y "contenido_fin": ids del primer y último segmento con contenido útil (sin \
            intro ni despedida).
            2. "segmentos_eliminar": SOLO relleno real: patrocinios, "suscríbete", saludos y despedidas, \
            silencios o tiempos muertos, y lo que no tiene nada que ver con el vídeo. Nunca contenido del \
            tema aunque sea menos llamativo.
            3. "secciones": divide el vídeo en secciones temáticas completas, EN ORDEN. Cada sección tiene un \
            "titulo" corto, un "resumen" de una frase y sus "clips" ({"inicio": id, "fin": id}).
               - Una sección corta es un clip. Una larga se parte en varios clips SEGUIDOS (una serie): cada \
            parte empieza justo donde acabó la anterior y termina en una pausa natural, a ser posible en un \
            punto de tensión que invite a ver la siguiente.
               - Cada clip debe entenderse por sí solo: empieza donde arranca la idea (con la pregunta, el \
            planteamiento o el contexto que necesita) y termina en su conclusión o remate. Nunca a mitad de \
            una frase ni de un razonamiento.
               - Duración ideal de 30 a 90 segundos; mínimo %d s y máximo %d s. Si una idea no cabe, mejor \
            una serie de partes completas que un clip que corte la historia.
            %s
            4. "tipo_contenido": gameplay | charla | deporte | tutorial | otro.

            Usa SOLO ids de segmento (como string), nunca tiempos. Devuelve SOLO este JSON, sin texto antes ni \
            después:
            {
              "contenido_inicio": "<id>",
              "contenido_fin": "<id>",
              "segmentos_eliminar": [{"ids": ["<id>", "<id>"], "motivo": "patrocinio|intro|outro|saludos|silencio|otro"}],
              "secciones": [{"titulo": "...", "resumen": "...", "clips": [{"inicio": "<id>", "fin": "<id>"}]}],
              "tipo_contenido": "..."
            }

            Vídeo:
            - Título: %s
            - Canal: %s
            - Plataforma: %s
            - Duración: %d s
            - Idioma: %s
            """;

    private static final String FULL_COVERAGE = """
               - COBERTURA COMPLETA: entre todos los clips tienen que cubrir TODO el contenido entre \
            contenido_inicio y contenido_fin, salvo los segmentos_eliminar. No te saltes nada: lo que une unas \
            partes con otras también cuenta.""";

    private static final String HIGHLIGHTS = """
               - DESTACADOS: elige solo las secciones con más interés; puedes dejar fuera el resto.""";

    private final LlmClient llmClient;
    private final TranscriptRepository transcriptRepository;
    private final VideoJobRepository jobRepository;
    private final ContentAnalysisRepository analysisRepository;
    private final SponsorBlockClient sponsorBlockClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;
    private final SettingService settings;

    public ContentAnalysisService(LlmClient llmClient,
                                  TranscriptRepository transcriptRepository,
                                  VideoJobRepository jobRepository,
                                  ContentAnalysisRepository analysisRepository,
                                  SponsorBlockClient sponsorBlockClient,
                                  ObjectMapper objectMapper,
                                  TransactionTemplate txTemplate,
                                  SettingService settings) {
        this.llmClient = llmClient;
        this.transcriptRepository = transcriptRepository;
        this.jobRepository = jobRepository;
        this.analysisRepository = analysisRepository;
        this.sponsorBlockClient = sponsorBlockClient;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
        this.settings = settings;
    }

    /** Claude's plan, parsed: segment indices, before the server's rules. */
    record ParsedPlan(Integer contentStart, Integer contentEnd, Set<Integer> removed,
                      List<EditorialPlan.Section> sections, String contentType) {}

    /**
     * Runs the full analysis for {@code jobId}. Returns the saved
     * {@link ContentAnalysis} — never null. When Claude fails it returns a
     * fallback row with null analysis fields, PENDING_REVIEW whatever the
     * job's review mode (R12): the provisional cuts go to the review screen
     * instead of being published unseen.
     */
    public ContentAnalysis analyze(UUID jobId) {
        try {
            return doAnalyze(jobId);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("Job {}: content analysis failed: {}", jobId, e.getMessage(), e);
            return saveFallback(jobId, ReviewStatus.PENDING_REVIEW);
        }
    }

    @SuppressWarnings("unchecked")
    private ContentAnalysis doAnalyze(UUID jobId) throws IOException, InterruptedException {
        Transcript transcript = transcriptRepository.findByVideoJobId(jobId)
                .orElseThrow(() -> new IOException("Transcript not found for job " + jobId));
        VideoJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IOException("Job not found: " + jobId));

        Map<String, Object> stitched = transcript.getWordsJson();
        List<Map<String, Object>> rawSegments =
                (List<Map<String, Object>>) stitched.getOrDefault("segments", List.of());
        if (rawSegments.isEmpty()) {
            log.warn("Job {}: transcript has no segments, skipping Claude analysis", jobId);
            return saveFallback(jobId, ReviewStatus.NOT_NEEDED);
        }

        List<EditorialPlan.Segment> segments = toSegments(rawSegments);
        boolean full = fullCoverage();
        long minMs = settings != null ? settings.clipMinMs() : 15_000;
        long maxMs = settings != null ? settings.clipMaxMs() : 180_000;
        String segmentsJson = numberedSegmentsJson(rawSegments);
        String prompt = buildPrompt(job, transcript, full, minMs, maxMs);

        // SponsorBlock marks filler the community already found (YouTube)
        List<SponsorSegment> sbSegs = sponsorBlockClient.fetchSegments(job.getSourceUrl());
        Set<Integer> sponsorSegments = segmentsCoveredBy(segments, sbSegs);

        ParsedPlan plan = null;
        EditorialPlan.Result result = null;
        String feedback = "";
        boolean askedAgain = false;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String raw = llmClient.call(prompt + feedback, segmentsJson);
                ParsedPlan parsed = parsePlan(LlmClient.extractJson(raw), segments.size());
                Set<Integer> removed = new HashSet<>(parsed.removed());
                removed.addAll(sponsorSegments);
                EditorialPlan.Result built = EditorialPlan.build(segments, parsed.contentStart(), parsed.contentEnd(),
                        removed, parsed.sections(), minMs, maxMs, full);
                plan = parsed;
                result = built;
                if (full && built.proposedCoverage() < MIN_PROPOSED_COVERAGE && !askedAgain) {
                    // Claude skipped too much: tell it what and ask once more (the server fills the rest)
                    askedAgain = true;
                    feedback = missingFeedback(segments, parsed, removed, built.proposedCoverage());
                    log.info("Job {}: Claude covered {}% of the content, asking again", jobId,
                            Math.round(built.proposedCoverage() * 100));
                    continue;
                }
                break;
            } catch (IOException | RuntimeException e) {
                // A reply that doesn't parse is as retryable as a failed call
                log.warn("Job {}: Claude analysis attempt {}/{} failed: {}", jobId, attempt, MAX_RETRIES, e.getMessage());
            }
        }
        if (result == null || result.clips().isEmpty()) {
            log.warn("Job {}: no usable editorial plan — provisional planner, sent to review (R12)", jobId);
            return saveFallback(jobId, ReviewStatus.PENDING_REVIEW);
        }

        List<Map<String, Object>> removedJson = removedJson(segments, plan.removed(), sbSegs);
        List<Map<String, Object>> proposedJson = new ArrayList<>();
        for (EditorialPlan.PlannedClip c : result.clips()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("startMs", c.startMs());
            m.put("endMs", c.endMs());
            if (c.section() != null) m.put("section", c.section());
            m.put("part", c.part());
            m.put("parts", c.parts());
            proposedJson.add(m);
        }
        List<String> notes = new ArrayList<>(result.notes());
        if (askedAgain) notes.addFirst("Claude se dejó parte del vídeo en su primera propuesta: se le volvió a pedir.");

        ReviewStatus reviewStatus = job.isReviewMode() ? ReviewStatus.PENDING_REVIEW : ReviewStatus.NOT_NEEDED;
        final ParsedPlan finalPlan = plan;
        return txTemplate.execute(status -> {
            VideoJob j = jobRepository.findById(jobId)
                    .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));
            ContentAnalysis ca = new ContentAnalysis(j, reviewStatus);
            ca.setContentStartSegmentId(finalPlan.contentStart() == null ? null : String.valueOf(finalPlan.contentStart()));
            ca.setContentEndSegmentId(finalPlan.contentEnd() == null ? null : String.valueOf(finalPlan.contentEnd()));
            ca.setRemovedSegmentsJson(removedJson);
            ca.setProposedClipsJson(proposedJson);
            ca.setPlanNotesJson(notes);
            ca.setContentType(finalPlan.contentType());
            ContentAnalysis saved = analysisRepository.save(ca);
            log.info("Job {}: editorial plan saved — {} clip(s) in {} section(s), type={}, {} adjustment(s)",
                    jobId, proposedJson.size(), finalPlan.sections().size(), finalPlan.contentType(), notes.size());
            return saved;
        });
    }

    private boolean fullCoverage() {
        return settings == null || !"destacados".equalsIgnoreCase(settings.clipsCoverage());
    }

    // ── Prompt / serialization ────────────────────────────────────────────────

    private String buildPrompt(VideoJob job, Transcript transcript, boolean full, long minMs, long maxMs) {
        long durationMs = ((Number) transcript.getWordsJson().getOrDefault("durationMs", 0L)).longValue();
        return ANALYSIS_PROMPT_TEMPLATE.formatted(
                minMs / 1000, maxMs / 1000,
                full ? FULL_COVERAGE : HIGHLIGHTS,
                nvl(job.getTitle(), "desconocido"),
                nvl(job.getSourceChannel(), "desconocido"),
                nvl(job.getSourcePlatform(), "desconocida"),
                durationMs / 1000,
                nvl(job.getLanguage(), "es"));
    }

    /** What Claude left out, in its own ids, to ask again. */
    private static String missingFeedback(List<EditorialPlan.Segment> segments, ParsedPlan plan, Set<Integer> removed,
                                          double coverage) {
        StringBuilder sb = new StringBuilder("\nIMPORTANTE: tu propuesta anterior solo cubría el ")
                .append(Math.round(coverage * 100))
                .append(" % del contenido. Te dejaste estos tramos de segmentos, que también tienen que estar en algún clip: ");
        List<String> runs = new ArrayList<>();
        for (int[] r : EditorialPlan.missingRuns(segments.size(), plan.contentStart(), plan.contentEnd(), removed,
                plan.sections())) {
            runs.add(r[0] == r[1] ? String.valueOf(r[0]) : r[0] + "–" + r[1]);
            if (runs.size() == 40) break;
        }
        return sb.append(String.join(", ", runs)).append(". Rehaz el plan completo cubriéndolo todo.\n").toString();
    }

    String numberedSegmentsJson(List<Map<String, Object>> segments) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            Map<String, Object> s = segments.get(i);
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", String.valueOf(i));
            n.put("startMs", s.get("startMs"));
            n.put("endMs", s.get("endMs"));
            n.put("text", s.get("text"));
            result.add(n);
        }
        return objectMapper.writeValueAsString(result);
    }

    Map<String, long[]> buildSegmentTimeMap(List<Map<String, Object>> segments) {
        Map<String, long[]> map = new LinkedHashMap<>();
        for (int i = 0; i < segments.size(); i++) {
            Map<String, Object> s = segments.get(i);
            map.put(String.valueOf(i), new long[]{
                    ((Number) s.get("startMs")).longValue(), ((Number) s.get("endMs")).longValue()});
        }
        return map;
    }

    static List<EditorialPlan.Segment> toSegments(List<Map<String, Object>> raw) {
        List<EditorialPlan.Segment> out = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            Map<String, Object> s = raw.get(i);
            out.add(new EditorialPlan.Segment(i, ((Number) s.get("startMs")).longValue(),
                    ((Number) s.get("endMs")).longValue(), String.valueOf(s.getOrDefault("text", ""))));
        }
        return out;
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    /**
     * Claude's editorial plan with ids as segment indices. Also reads the old
     * flat {@code clips_propuestos} form (each clip its own section).
     */
    ParsedPlan parsePlan(String json, int segmentCount) throws IOException {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IOException("Response is not valid JSON: " + e.getMessage(), e);
        }
        if (root.isMissingNode() || root.isNull() || !root.isObject()) {
            throw new IOException("Response is not a JSON object");
        }

        Integer start = optionalId(root.path("contenido_inicio"), segmentCount, "contenido_inicio");
        Integer end = optionalId(root.path("contenido_fin"), segmentCount, "contenido_fin");

        Set<Integer> removed = new HashSet<>();
        for (JsonNode item : root.path("segmentos_eliminar")) {
            List<Integer> ids = new ArrayList<>();
            for (JsonNode id : item.path("ids")) {
                Integer i = parseId(id.asText(""), segmentCount);
                if (i != null) ids.add(i);
            }
            if (ids.isEmpty()) continue;
            int from = ids.stream().min(Integer::compare).orElseThrow();
            int to = ids.stream().max(Integer::compare).orElseThrow();
            for (int i = from; i <= to; i++) removed.add(i);
        }

        List<EditorialPlan.Section> sections = new ArrayList<>();
        JsonNode sectionsNode = root.path("secciones");
        if (sectionsNode.isArray()) {
            for (JsonNode s : sectionsNode) {
                List<int[]> clips = clips(s.path("clips"), segmentCount);
                if (!clips.isEmpty()) sections.add(new EditorialPlan.Section(s.path("titulo").asText(""), clips));
            }
        } else {
            for (int[] c : clips(root.path("clips_propuestos"), segmentCount)) {
                sections.add(new EditorialPlan.Section("", List.of(c)));
            }
        }
        if (sections.isEmpty()) throw new IOException("secciones is missing or empty");

        return new ParsedPlan(start, end, removed, sections, root.path("tipo_contenido").asText("otro"));
    }

    private static List<int[]> clips(JsonNode node, int segmentCount) throws IOException {
        List<int[]> out = new ArrayList<>();
        for (JsonNode c : node) {
            String ini = c.path("inicio").asText(null);
            String fin = c.path("fin").asText(null);
            if (ini == null || fin == null) continue;
            Integer from = parseId(ini, segmentCount);
            Integer to = parseId(fin, segmentCount);
            if (from == null || to == null) {
                throw new IOException("clip references unknown segment: " + ini + "–" + fin);
            }
            out.add(new int[]{from, to});
        }
        return out;
    }

    private static Integer optionalId(JsonNode node, int segmentCount, String field) throws IOException {
        if (node.isMissingNode() || node.isNull() || node.asText("").isBlank()) return null;
        Integer id = parseId(node.asText(), segmentCount);
        if (id == null) throw new IOException(field + " references unknown segment id: " + node.asText());
        return id;
    }

    private static Integer parseId(String id, int segmentCount) {
        try {
            int i = Integer.parseInt(id.strip());
            return i >= 0 && i < segmentCount ? i : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── Filler ───────────────────────────────────────────────────────────────

    /** Segments at least half covered by a SponsorBlock range. */
    static Set<Integer> segmentsCoveredBy(List<EditorialPlan.Segment> segments, List<SponsorSegment> sponsor) {
        Set<Integer> out = new HashSet<>();
        for (SponsorSegment sb : sponsor) {
            long from = Math.round(sb.start() * 1000.0);
            long to = Math.round(sb.end() * 1000.0);
            for (EditorialPlan.Segment s : segments) {
                long overlap = Math.min(to, s.endMs()) - Math.max(from, s.startMs());
                if (overlap > 0 && overlap * 2 >= s.endMs() - s.startMs()) out.add(s.index());
            }
        }
        return out;
    }

    private static List<Map<String, Object>> removedJson(List<EditorialPlan.Segment> segments, Set<Integer> claudeRemoved,
                                                         List<SponsorSegment> sbSegs) {
        List<Map<String, Object>> result = new ArrayList<>();
        claudeRemoved.stream().sorted().forEach(i -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("startMs", segments.get(i).startMs());
            m.put("endMs", segments.get(i).endMs());
            m.put("source", "claude");
            result.add(m);
        });
        for (SponsorSegment sb : sbSegs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("startMs", Math.round(sb.start() * 1000.0));
            m.put("endMs", Math.round(sb.end() * 1000.0));
            m.put("source", "sponsorblock");
            m.put("category", sb.category());
            result.add(m);
        }
        return result;
    }

    // ── Fallback ──────────────────────────────────────────────────────────────

    private ContentAnalysis saveFallback(UUID jobId, ReviewStatus reviewStatus) {
        return txTemplate.execute(status -> {
            VideoJob job = jobRepository.findById(jobId)
                    .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));
            ContentAnalysis ca = new ContentAnalysis(job, reviewStatus);
            ContentAnalysis saved = analysisRepository.save(ca);
            log.info("Job {}: fallback ContentAnalysis saved (null analysis, {})", jobId, reviewStatus);
            return saved;
        });
    }

    private static String nvl(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }
}
