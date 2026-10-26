package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.protocol.state.ClipState;
import io.github.raulgl422.cliphive.protocol.state.ScheduledPostState;
import io.github.raulgl422.cliphive.server.llm.LlmClient;
import io.github.raulgl422.cliphive.server.model.Clip;
import io.github.raulgl422.cliphive.server.model.ScheduledPost;
import io.github.raulgl422.cliphive.server.model.Transcript;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.repository.ClipRepository;
import io.github.raulgl422.cliphive.server.repository.ContentAnalysisRepository;
import io.github.raulgl422.cliphive.server.repository.ScheduledPostRepository;
import io.github.raulgl422.cliphive.server.repository.TranscriptRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Texts for each clip with Claude (docs/DESIGN.md §10.3, pipeline step 9),
 * written for reach — the same text goes to YouTube Shorts, Instagram Reels
 * and TikTok: title, description with hashtags, and the short on-screen title
 * the worker burns on top of the video. All the clips of a video go in one
 * call so Claude can spot a series and number it ("La remontada · 2/3").
 * Claude gets the original video's title, channel, platform and content type;
 * the server enforces the limits (R10) and normalizes the hashtags. On failure
 * the clips keep a null title and publishing falls back to the video title.
 */
@Service
public class ClipMetadataService {

    static final int MAX_TITLE_LENGTH = 100;
    static final int MAX_DESCRIPTION_LENGTH = 2000;
    static final int MAX_OVERLAY_LENGTH = 60;
    static final int MAX_HASHTAGS = 5;
    static final int MAX_RETRIES = 2;
    /** Clips per Claude call (§10.3). */
    static final int BATCH_SIZE = 30;
    /** Transcript characters per clip sent to Claude — plenty to summarize it. */
    static final int MAX_TEXT_PER_CLIP = 1500;
    /** Hashtags that only add noise (DESIGN.md §10.3). */
    static final Set<String> FILLER_HASHTAGS = Set.of("fyp", "foryou", "foryoupage", "parati", "paratii",
            "viral", "viralvideo", "trending", "xyzbca", "fypシ", "explore", "explorepage");

    private static final Pattern TRAILING_HASHTAGS = Pattern.compile("(\\s*#[\\p{L}\\p{N}_]+)+\\s*$");
    private static final Logger log = LoggerFactory.getLogger(ClipMetadataService.class);

    private static final String PROMPT = """
            <rol>
            Eres el redactor de un canal de clips verticales que se publican igual en YouTube Shorts, \
            Instagram Reels y TikTok. Escribes los textos de todos los clips de un vídeo: título, \
            descripción, hashtags y el título corto que se graba encima del vídeo. Tu objetivo es que cada \
            clip llegue a la mayor cantidad de gente posible sin engañar a nadie, porque un título que \
            promete algo que el clip no cumple hace que la gente se vaya en el primer segundo y la \
            plataforma deje de mostrarlo.
            </rol>

            <entrada>
            Por stdin recibes un JSON con:
            - "idioma": el idioma en el que escribes todo.
            - "video_original": título, canal y plataforma del vídeo del que salen los clips.
            - "tipo_contenido": gameplay, charla, deporte, tutorial…
            - "clips": TODOS los clips del vídeo en orden, cada uno con su "id", "duracion_s", \
            "transcripcion" (lo que se dice en el clip, ya corregido), "seccion" (el tema del que forma \
            parte) y, si la sección se publica en varias partes, "parte" y "de".
            - Los clips con "fijo": true ya están publicados o tienen el título grabado: traen su texto \
            actual ("titulo_actual", "titulo_pantalla_actual"). NO escribas textos para ellos; úsalos solo \
            como referencia para que los demás encajen (misma serie, sin repetir títulos).
            </entrada>

            <como_se_leen_estos_textos>
            - En el feed vertical casi nadie lee el título antes de ver: lo que retiene es el primer segundo \
            y el título en pantalla. El título y la descripción pesan sobre todo en la BÚSQUEDA (TikTok, \
            Instagram y YouTube indexan el texto y los hashtags), en el perfil y en las sugerencias. Por \
            eso deben llevar las palabras que alguien escribiría para encontrar el clip: nombres propios \
            (personas, juego, equipo, programa), el tema y lo que pasa.
            - Quien ve una parte de una serie tiene que saber que hay más y encontrar las otras: la \
            numeración la añade el sistema, tú haz que el texto de cada parte cuente lo que pasa en ESA \
            parte.
            </como_se_leen_estos_textos>

            <titulo>
            - Entre 40 y 70 caracteres; nunca más de 85 (el sistema añade " (Parte 2/3)" al final en las \
            series).
            - Concreto y fiel a SU clip: quién + qué pasa, o la pregunta/idea central con su gancho. Las \
            primeras palabras son las que se ven: pon ahí lo más llamativo o el nombre propio.
            - Distinto de los títulos de los demás clips del vídeo. En una serie, cada parte describe su \
            propio momento (la 2 no repite el título de la 1).
            - Prohibido: escribir "Parte", "Part", "1/3" u otra numeración; hashtags; los caracteres < y >; \
            todo en mayúsculas; más de un emoji; títulos genéricos que valdrían para cualquier clip \
            ("Momento épico", "No te lo vas a creer", "Increíble", "Lo mejor del directo"); copiar el título \
            del vídeo original; mencionar cosas que pasan en otro clip.
            </titulo>

            <descripcion>
            - 1 a 3 frases, máximo 600 caracteres, sin hashtags.
            - La primera frase resume el clip con las palabras clave de búsqueda (los mismos nombres propios \
            y el tema).
            - Da crédito al creador original si hay canal (por ejemplo "Clip de <canal>").
            - En una serie: di que es la parte N de M y que el resto está en el perfil; en las partes 2 y \
            siguientes, una frase que conecte con lo anterior sin repetirlo.
            - Termina con una pregunta concreta sobre el clip solo si sale natural, nunca un "¿Qué opinas?" \
            genérico.
            - Nada que no esté en la transcripción o en los datos del vídeo: no inventes resultados, nombres \
            ni cifras.
            </descripcion>

            <hashtags>
            De 3 a 5: uno o dos de entidades (persona, juego, equipo, programa), uno o dos del tema y uno \
            amplio del nicho. En el idioma del vídeo salvo nombres propios; en CamelCase si son varias \
            palabras (#LaVelada). Nada de relleno como #fyp, #parati, #viral o #foryou: no aportan alcance \
            y diluyen la categoría del clip.
            </hashtags>

            <titulo_pantalla>
            Frase muy corta (máximo 32 caracteres) que se graba encima del vídeo durante todo el clip y se \
            lee en la miniatura del perfil. Dice de qué va el clip en dos segundos. Sin emojis, sin hashtags \
            y SIN numeración (la pone el sistema). Todas las partes de una misma serie usan EXACTAMENTE el \
            mismo titulo_pantalla (el nombre de la serie), para que en el perfil se vea que van juntas.
            </titulo_pantalla>

            <ejemplo>
            Entrada (resumida): idioma "es", canal "TheGrefg", clips: c1 (seccion "La final del torneo", \
            parte 1 de 2), c2 (seccion "La final del torneo", parte 2 de 2), c3 (seccion "Su primer setup", \
            sin partes).
            Salida:
            {"clips": [
              {"id": "c1", "titulo": "TheGrefg llega a la final con un solo punto de vida", \
            "descripcion": "TheGrefg se juega la final del torneo con la vida al mínimo y una jugada \
            arriesgadísima. Clip de TheGrefg. Parte 1 de 2: la siguiente, en el perfil.", \
            "hashtags": ["#TheGrefg", "#Fortnite", "#TorneoFortnite", "#Gaming"], \
            "titulo_pantalla": "La final del torneo"},
              {"id": "c2", "titulo": "La última jugada que decidió la final de TheGrefg", \
            "descripcion": "Así termina la final: después de aguantar con un punto de vida, TheGrefg lo \
            arriesga todo en la última zona. Clip de TheGrefg. Parte 2 de 2.", \
            "hashtags": ["#TheGrefg", "#Fortnite", "#TorneoFortnite", "#Gaming"], \
            "titulo_pantalla": "La final del torneo"},
              {"id": "c3", "titulo": "TheGrefg enseña el setup con el que empezó a hacer directos", \
            "descripcion": "TheGrefg recuerda su primer setup para streamear y lo poco que costaba. Clip de \
            TheGrefg. ¿Con qué empezaste tú?", \
            "hashtags": ["#TheGrefg", "#Setup", "#Streaming", "#Gaming"], \
            "titulo_pantalla": "Su primer setup"}
            ]}
            </ejemplo>

            <antes_de_responder>
            Comprueba que: hay una entrada por cada clip no fijo, con su mismo "id"; cada título habla de \
            lo que pasa en su propia transcripción; no hay dos títulos iguales; ningún título lleva \
            numeración; las partes de una serie comparten titulo_pantalla.
            </antes_de_responder>

            Devuelve SOLO el JSON, sin texto antes ni después:
            {"clips": [{"id": "c1", "titulo": "...", "descripcion": "...", "hashtags": ["#..."], "titulo_pantalla": "..."}]}
            """;

    /** Publications that have started: their clip's text can no longer change. */
    private static final Set<ScheduledPostState> STARTED = EnumSet.of(
            ScheduledPostState.UPLOADING, ScheduledPostState.PROCESSING, ScheduledPostState.PUBLISHED);
    private static final Set<ClipState> EDITABLE = EnumSet.of(
            ClipState.PLANNED, ClipState.RENDERING, ClipState.RENDERED, ClipState.SCHEDULED);

    private final LlmClient llmClient;
    private final ClipRepository clipRepository;
    private final VideoJobRepository jobRepository;
    private final ContentAnalysisRepository analysisRepository;
    private final TranscriptRepository transcriptRepository;
    private final ScheduledPostRepository postRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public ClipMetadataService(LlmClient llmClient,
                               ClipRepository clipRepository,
                               VideoJobRepository jobRepository,
                               ContentAnalysisRepository analysisRepository,
                               TranscriptRepository transcriptRepository,
                               ScheduledPostRepository postRepository,
                               ObjectMapper objectMapper,
                               TransactionTemplate txTemplate) {
        this.llmClient = llmClient;
        this.clipRepository = clipRepository;
        this.jobRepository = jobRepository;
        this.analysisRepository = analysisRepository;
        this.transcriptRepository = transcriptRepository;
        this.postRepository = postRepository;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
    }

    /** The texts Claude wrote for one clip, already validated. */
    record ClipTexts(String title, String description, String overlayTitle) {}

    /**
     * Generates and saves the texts of the clips of {@code jobId} that don't
     * have a title yet.
     */
    public void generateForJob(UUID jobId, Transcript transcript, List<Clip> clips) {
        Set<UUID> missing = new HashSet<>();
        clips.stream().filter(c -> c.getTitle() == null || c.getTitle().isBlank()).forEach(c -> missing.add(c.getId()));
        if (missing.isEmpty()) return;
        // The other clips of the video go too, as context (§10.3)
        List<Clip> all = clipRepository.findByVideoJobIdOrderByClipIndex(jobId);
        generate(jobId, transcript, all.isEmpty() ? clips : all, missing);
    }

    /**
     * "Regenerar textos" (§10.3): new texts for every clip of the job that
     * hasn't started uploading anywhere. Returns how many changed.
     */
    public int regenerate(UUID jobId) {
        Transcript transcript = transcriptRepository.findByVideoJobId(jobId).orElse(null);
        if (transcript == null) return 0;
        List<Clip> all = clipRepository.findByVideoJobIdOrderByClipIndex(jobId);
        Set<UUID> started = new HashSet<>();
        for (ScheduledPost p : postRepository.findByClipVideoJobId(jobId)) {
            if (STARTED.contains(p.getState())) started.add(p.getClip().getId());
        }
        Set<UUID> editable = new HashSet<>();
        all.stream()
                .filter(c -> EDITABLE.contains(c.getState()))
                .filter(c -> !started.contains(c.getId()))
                .forEach(c -> editable.add(c.getId()));
        // Every clip goes, the locked ones as context: a part 3 written without parts 1 and 2 doesn't fit (§10.3)
        return editable.isEmpty() ? 0 : generate(jobId, transcript, all, editable);
    }

    @SuppressWarnings("unchecked")
    private int generate(UUID jobId, Transcript transcript, List<Clip> clips, Set<UUID> toWrite) {
        Map<String, Object> stitched = transcript.getWordsJson();
        List<Map<String, Object>> segments = (List<Map<String, Object>>) stitched.getOrDefault("segments", List.of());
        VideoJob job = jobRepository.findById(jobId).orElse(null);

        Map<String, Object> base = new LinkedHashMap<>();
        base.put("idioma", String.valueOf(stitched.getOrDefault("language", job != null ? job.getLanguage() : "es")));
        Map<String, Object> video = new LinkedHashMap<>();
        video.put("titulo", job != null ? job.getTitle() : null);
        video.put("canal", job != null ? job.getSourceChannel() : null);
        video.put("plataforma", job != null ? job.getSourcePlatform() : null);
        base.put("video_original", video);
        base.put("tipo_contenido", analysisRepository.findByVideoJobId(jobId).map(a -> a.getContentType()).orElse(null));

        String language = String.valueOf(base.get("idioma"));
        int done = 0;
        for (List<Clip> batch : batches(clips)) {
            if (batch.stream().noneMatch(c -> toWrite.contains(c.getId()))) continue;
            Map<String, Clip> byKey = new LinkedHashMap<>();
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Clip c : batch) {
                String text = clipText(c, segments);
                if (text.isBlank()) continue;
                String key = "c" + (c.getClipIndex() + 1);
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("id", key);
                if (toWrite.contains(c.getId())) {
                    byKey.put(key, c);
                } else {
                    e.put("fijo", true);
                    if (c.getTitle() != null) e.put("titulo_actual", c.getTitle());
                    if (c.getOverlayTitle() != null) e.put("titulo_pantalla_actual", c.getOverlayTitle());
                }
                e.put("duracion_s", c.getDurationSeconds());
                if (c.getSectionTitle() != null) e.put("seccion", c.getSectionTitle());
                if (c.getPartCount() != null && c.getPartCount() > 1) {
                    e.put("parte", c.getPartIndex());
                    e.put("de", c.getPartCount());
                }
                // Locked clips are only context: their start is enough
                int max = toWrite.contains(c.getId()) ? MAX_TEXT_PER_CLIP : MAX_TEXT_PER_CLIP / 3;
                e.put("transcripcion", text.length() > max ? text.substring(0, max) + "…" : text);
                entries.add(e);
            }
            if (byKey.isEmpty()) continue;
            Map<String, Object> input = new LinkedHashMap<>(base);
            input.put("clips", entries);
            done += generateBatch(jobId, input, byKey, language);
        }
        return done;
    }

    /**
     * Batches of about {@value #BATCH_SIZE} clips that never split a section, so a
     * whole series is written in one call (§10.3). A single section longer than a
     * batch goes on its own.
     */
    static List<List<Clip>> batches(List<Clip> clips) {
        List<List<Clip>> out = new ArrayList<>();
        List<Clip> current = new ArrayList<>();
        int i = 0;
        while (i < clips.size()) {
            int j = i + 1;
            String section = clips.get(i).getSectionTitle();
            while (section != null && j < clips.size() && section.equals(clips.get(j).getSectionTitle())) j++;
            List<Clip> group = clips.subList(i, j);
            if (!current.isEmpty() && current.size() + group.size() > BATCH_SIZE) {
                out.add(current);
                current = new ArrayList<>();
            }
            current.addAll(group);
            i = j;
        }
        if (!current.isEmpty()) out.add(current);
        return out;
    }

    private int generateBatch(UUID jobId, Map<String, Object> input, Map<String, Clip> byKey, String language) {
        Map<String, ClipTexts> texts = null;
        try {
            String stdin = objectMapper.writeValueAsString(input);
            for (int attempt = 1; attempt <= MAX_RETRIES && texts == null; attempt++) {
                try {
                    texts = parseBatch(LlmClient.extractJson(llmClient.call(PROMPT, stdin)));
                } catch (IOException e) {
                    log.warn("Job {}: texts attempt {}/{} failed: {}", jobId, attempt, MAX_RETRIES, e.getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
        if (texts == null) {
            log.warn("Job {}: all text retries failed for {} clip(s)", jobId, byKey.size());
            return 0;
        }

        int saved = 0;
        for (Map.Entry<String, Clip> e : byKey.entrySet()) {
            ClipTexts t = texts.get(e.getKey());
            if (t == null) {
                log.warn("Job {}: Claude returned no texts for clip {}", jobId, e.getKey());
                continue;
            }
            UUID clipId = e.getValue().getId();
            txTemplate.executeWithoutResult(status -> clipRepository.findById(clipId).ifPresent(c -> {
                c.setTitle(withPart(t.title(), c, language));
                c.setDescription(withPartLine(t.description(), c, language));
                // Already rendered clips have the old one burned in: keep it so the page matches the video
                if (c.getState() == ClipState.PLANNED || c.getState() == ClipState.RENDERING || c.getOverlayTitle() == null) {
                    c.setOverlayTitle(numbered(t.overlayTitle(), c));
                }
                clipRepository.save(c);
            }));
            log.info("Clip {}: title=\"{}\" overlay=\"{}\"", clipId, t.title(), t.overlayTitle());
            saved++;
        }
        return saved;
    }

    /** {@code {"clips": [...]}} → validated texts by clip id. */
    Map<String, ClipTexts> parseBatch(String json) throws IOException {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IOException("Not valid JSON: " + e.getMessage(), e);
        }
        if (!root.path("clips").isArray()) throw new IOException("clips missing");
        Map<String, ClipTexts> out = new LinkedHashMap<>();
        for (JsonNode node : root.path("clips")) {
            try {
                out.put(node.path("id").asText(""), validate(node));
            } catch (IOException e) {
                log.debug("Dropping texts for {}: {}", node.path("id").asText("?"), e.getMessage());
            }
        }
        if (out.isEmpty()) throw new IOException("no usable clip texts");
        return out;
    }

    /** One clip's texts (single-clip form, kept for tests and older callers). */
    void parseAndValidate(String json, String[] titleHolder, String[] descHolder) throws IOException {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IOException("Not valid JSON: " + e.getMessage(), e);
        }
        if (!root.isObject()) {
            throw new IOException("Response is not a JSON object");
        }
        ClipTexts t = validate(root);
        titleHolder[0] = t.title();
        descHolder[0] = t.description();
    }

    private ClipTexts validate(JsonNode root) throws IOException {
        String title = root.path("titulo").asText(null);
        String desc = root.path("descripcion").asText(null);
        if (title == null || title.isBlank()) {
            throw new IOException("titulo missing or blank");
        }

        // Enforce limits (R10)
        title = title.replace("<", "").replace(">", "").strip();
        // Numbering is the server's job: drop any Claude wrote anyway
        title = PART_IN_TITLE.matcher(title).replaceAll("").strip();
        if (title.length() > MAX_TITLE_LENGTH) {
            title = title.substring(0, MAX_TITLE_LENGTH).strip();
        }
        desc = desc == null ? "" : desc.strip();

        List<String> hashtags = hashtags(root.path("hashtags"));
        if (!hashtags.isEmpty()) {
            // Hashtags go once, at the end — drop any Claude also left at the end of the text
            desc = TRAILING_HASHTAGS.matcher(desc).replaceAll("").strip();
            String tagLine = String.join(" ", hashtags);
            int room = MAX_DESCRIPTION_LENGTH - tagLine.length() - 2;
            if (desc.length() > room) desc = desc.substring(0, Math.max(0, room)).strip();
            desc = desc.isEmpty() ? tagLine : desc + "\n\n" + tagLine;
        } else if (desc.length() > MAX_DESCRIPTION_LENGTH) {
            desc = desc.substring(0, MAX_DESCRIPTION_LENGTH).strip();
        }
        return new ClipTexts(title, desc, overlay(root.path("titulo_pantalla").asText(null)));
    }

    /** "(Parte 2/3)", "- Part 2", "| 2/3"… at the end of a title. */
    private static final Pattern PART_IN_TITLE = Pattern.compile(
            "\\s*[(\\[|·\\-–]*\\s*(?:(?:parte|part|partie|teil|pt\\.?)\\s*\\d+(?:\\s*(?:/|de|of)\\s*\\d+)?)\\s*[)\\]]?\\s*$",
            Pattern.CASE_INSENSITIVE);

    /** "Parte" in the video's language (§10.3). */
    static String partWord(String language) {
        String l = language == null ? "" : language.toLowerCase(Locale.ROOT);
        if (l.startsWith("en")) return "Part";
        if (l.startsWith("fr")) return "Partie";
        if (l.startsWith("de")) return "Teil";
        return "Parte";
    }

    /**
     * The publication title of a series part ends in " (Parte 2/3)" (§10.3): so
     * whoever sees one knows there are more. Added by the server so it's always
     * there and right; the title is cut if needed to stay within
     * {@value #MAX_TITLE_LENGTH} characters.
     */
    public static String withPart(String title, Clip clip, String language) {
        if (title == null) return null;
        String label = clip.partLabel();
        String base = PART_IN_TITLE.matcher(title).replaceAll("").strip();
        if (label == null) return base;
        String suffix = " (" + partWord(language) + " " + label + ")";
        int room = MAX_TITLE_LENGTH - suffix.length();
        if (base.length() > room) base = base.substring(0, Math.max(1, room - 1)).strip() + "…";
        return base + suffix;
    }

    /**
     * Adds "Parte 2 de 3 · el resto, en el perfil" before the hashtags of a
     * series part when the description doesn't say which part it is (§10.3).
     */
    static String withPartLine(String description, Clip clip, String language) {
        if (clip.partLabel() == null) return description;
        String desc = description == null ? "" : description;
        String l = language == null ? "" : language.toLowerCase(Locale.ROOT);
        String line;
        Pattern mentions;
        if (l.startsWith("en")) {
            line = "Part " + clip.getPartIndex() + " of " + clip.getPartCount() + " · the rest is on the profile";
            mentions = Pattern.compile("\\bpart\\s*" + clip.getPartIndex() + "\\b", Pattern.CASE_INSENSITIVE);
        } else {
            line = partWord(language) + " " + clip.getPartIndex() + " de " + clip.getPartCount() + " · el resto, en el perfil";
            mentions = Pattern.compile("\\b" + partWord(language) + "\\s*" + clip.getPartIndex() + "\\b",
                    Pattern.CASE_INSENSITIVE);
        }
        if (mentions.matcher(desc).find() || desc.contains(clip.partLabel())) return desc;
        var tags = TRAILING_HASHTAGS.matcher(desc);
        if (tags.find()) {
            String text = desc.substring(0, tags.start()).strip();
            String tagLine = desc.substring(tags.start()).strip();
            return (text.isEmpty() ? line : text + "\n" + line) + "\n\n" + tagLine;
        }
        return desc.isBlank() ? line : desc.strip() + "\n" + line;
    }

    /** The series number goes on by the server, so it's always right: "La remontada · 2/3". */
    static String numbered(String overlay, Clip clip) {
        String label = clip.partLabel();
        if (label == null) return overlay;
        String base = overlay == null ? clip.getSectionTitle() : overlay;
        if (base == null || base.isBlank()) return null;
        base = base.replaceAll("\\s*[·|\\-–]?\\s*\\(?\\d+\\s*/\\s*\\d+\\)?\\s*$", "").strip();
        int room = MAX_OVERLAY_LENGTH - label.length() - 3;
        if (base.length() > room) base = base.substring(0, Math.max(1, room - 1)).strip() + "…";
        return base + " · " + label;
    }

    /** On-screen title: one short line, no hashtags or line breaks; null if there's none. */
    static String overlay(String raw) {
        if (raw == null) return null;
        String t = raw.replaceAll("#[\\p{L}\\p{N}_]+", "").replaceAll("\\s+", " ").strip();
        if (t.isEmpty()) return null;
        return t.length() > MAX_OVERLAY_LENGTH ? t.substring(0, MAX_OVERLAY_LENGTH - 1).strip() + "…" : t;
    }

    /** The clip's words as plain text — only the ranges that are kept. */
    @SuppressWarnings("unchecked")
    static String clipText(Clip clip, List<Map<String, Object>> segments) {
        List<Map<String, Object>> ranges = clip.getKeptRangesJson();
        if (ranges == null || ranges.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> seg : segments) {
            for (Map<String, Object> w : (List<Map<String, Object>>) seg.getOrDefault("words", List.of())) {
                long start = ((Number) w.get("startMs")).longValue();
                long end = ((Number) w.get("endMs")).longValue();
                boolean kept = ranges.stream().anyMatch(r -> start >= ((Number) r.get("startMs")).longValue()
                        && end <= ((Number) r.get("endMs")).longValue());
                if (kept) {
                    if (!sb.isEmpty()) sb.append(' ');
                    sb.append(String.valueOf(w.getOrDefault("text", "")).strip());
                }
            }
        }
        return sb.toString().replaceAll("\\s+", " ").strip();
    }

    /** "#Tag" form, no spaces or symbols, no fillers, no repeats, at most {@value #MAX_HASHTAGS}. */
    static List<String> hashtags(JsonNode node) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        if (!node.isArray()) return out;
        for (JsonNode n : node) {
            String tag = n.asText("").strip().replaceFirst("^#+", "").replaceAll("[^\\p{L}\\p{N}_]", "");
            if (tag.length() < 2 || FILLER_HASHTAGS.contains(tag.toLowerCase(Locale.ROOT))) continue;
            if (!seen.add(tag.toLowerCase(Locale.ROOT))) continue;
            out.add("#" + tag);
            if (out.size() == MAX_HASHTAGS) break;
        }
        return out;
    }
}
