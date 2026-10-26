package io.github.raulgl422.cliphive.server.publish;

import io.github.raulgl422.cliphive.protocol.state.ClipState;
import io.github.raulgl422.cliphive.protocol.state.ScheduledPostState;
import io.github.raulgl422.cliphive.protocol.state.VideoJobState;
import io.github.raulgl422.cliphive.server.llm.LlmClient;
import io.github.raulgl422.cliphive.server.model.Clip;
import io.github.raulgl422.cliphive.server.model.PublicationPlan;
import io.github.raulgl422.cliphive.server.model.ScheduledPost;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.model.enums.Platform;
import io.github.raulgl422.cliphive.server.model.enums.PublishMode;
import io.github.raulgl422.cliphive.server.model.enums.ScheduledPostOrigin;
import io.github.raulgl422.cliphive.server.repository.ClipRepository;
import io.github.raulgl422.cliphive.server.repository.ContentAnalysisRepository;
import io.github.raulgl422.cliphive.server.repository.PublicationPlanRepository;
import io.github.raulgl422.cliphive.server.repository.ScheduledPostRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import io.github.raulgl422.cliphive.server.service.LogEventService;
import io.github.raulgl422.cliphive.server.service.SettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The daily publication plan with Claude (DESIGN.md §10.4): which clips go out
 * today and when on each platform. Claude proposes; {@link PlanValidator}
 * enforces the window, caps, spacing and clip order; if Claude fails, the
 * default slots keep publishing going. Active only in the {@code claude}
 * publish mode.
 */
@Service
public class PublicationPlanner {

    private static final Logger log = LoggerFactory.getLogger(PublicationPlanner.class);
    private static final String COMPONENT = "publicaciones";

    static final ZoneId ZONE = ZoneId.of("Europe/Madrid");
    static final LocalTime DAILY_RUN = LocalTime.of(7, 0);
    static final LocalTime WINDOW_END = LocalTime.of(23, 30);
    static final Duration LEAD_TIME = Duration.ofMinutes(10);
    /** New ready clips since the last plan that trigger a re-plan. */
    static final int REPLAN_NEW_CLIPS = 3;
    static final int MAX_QUEUE_IN_PROMPT = 60;
    static final int MAX_RETRIES = 2;
    /** Key in the plan's input summary: the ready clips it saw, to spot new ones. */
    static final String SEEN_CLIPS = "clips_vistos";

    private static final Set<VideoJobState> PUBLISHABLE_JOBS =
            EnumSet.of(VideoJobState.RENDERED, VideoJobState.SCHEDULED, VideoJobState.PUBLISHING);
    private static final Set<ScheduledPostState> TAKEN = EnumSet.of(ScheduledPostState.SCHEDULED,
            ScheduledPostState.UPLOADING, ScheduledPostState.PROCESSING, ScheduledPostState.PUBLISHED);
    /** Clips still to publish, for how many days the queue lasts (§10.4). */
    private static final Set<ClipState> PENDING_CLIPS =
            EnumSet.of(ClipState.PLANNED, ClipState.RENDERING, ClipState.RENDERED);

    private static final String PROMPT = """
            <rol>
            Eres el responsable de programación de un canal de clips verticales (YouTube Shorts, Instagram \
            Reels y TikTok) y un experto en cómo distribuyen el contenido estas plataformas. Decides qué \
            clips se publican HOY y a qué hora. Cada clip sale a la MISMA hora en todas las plataformas, \
            para que ninguna se adelante ni se quede atrás. Tu objetivo: el máximo alcance total de la \
            semana, que quien vea un clip encuentre el siguiente de la misma serie, y que la cola no se \
            acumule.
            </rol>

            <entrada>
            Por stdin recibes un JSON con:
            - "fecha", "dia_semana", "hora_actual", "hora_minima", "hora_maxima" (hora de Madrid).
            - "publico": a quién va dirigido el canal. Elige las horas pensando en SU zona horaria y \
            costumbres.
            - "calendario": si es fin de semana o festivo, la temporada y las fechas especiales del día.
            - "ritmo": cuántos clips hay en cola, para cuántos días dan y "clips_maximos_hoy", el máximo \
            que puedes programar hoy. Si "modo_acelerado" es true, la cola da para muchos días y conviene \
            acercarse a ese máximo. Si alguna plataforma va atrasada ("retraso_por_plataforma"), ese \
            máximo ya viene rebajado para que las demás no se escapen.
            - "plataformas": cada una con su "tope_restante_hoy" y las "horas_ocupadas_hoy".
            - "videos": por cada vídeo de la cola, si ya está empezado, cuántos clips lleva publicados o \
            programados, cuándo salió el último y cuántos le quedan.
            - "cola": los clips listos YA EN EL ORDEN EN QUE SE PUBLICAN (id, título, duración, vídeo, \
            sección y "parte_de_serie"): primero lo que le queda al vídeo en curso, luego el siguiente.
            - "publicado_ultimos_7_dias" y, si hay datos, "vistas_por_hora_de_publicacion" del propio canal.
            </entrada>

            <como_elegir_las_horas>
            Las primeras 1-2 horas tras publicar deciden a cuánta gente lo enseña cada plataforma: publica \
            un poco antes de que tu público tenga el móvil en la mano, no cuando ya se está yendo a dormir.
            Puntos de partida para público de España (estudios de 2025-2026 de Metricool, Buffer y Sprout \
            Social, ajustados a que en España se come de 14:00 a 16:00 y se cena de 21:00 a 22:30):
            - Franjas fuertes: mediodía 13:00-15:30 y tarde-noche 19:00-23:00. Secundaria: 8:00-9:30.
            - TikTok: mejor de tarde-noche (19:00-23:00); domingo y lunes son sus mejores días; viernes y \
            sábado por la noche baja algo porque la gente sale.
            - Instagram Reels: 13:00-15:00 y a partir de las 20:00.
            - YouTube Shorts: 17:00-22:00; el feed de Shorts reparte un clip durante días, así que la hora \
            pesa menos que en las otras dos.
            - Fines de semana y festivos: las mañanas empiezan más tarde (11:00-13:00 funciona) y la noche \
            se alarga. En verano todo se desplaza más hacia la noche.
            - En las fechas especiales del calendario evita las horas que se indican.
            - Si el público no es de España, traslada estas franjas a su zona horaria.
            Los datos propios mandan: si "vistas_por_hora_de_publicacion" tiene varias publicaciones en una \
            hora, fíate más de eso que de las reglas generales. Como la hora es la misma en todas las \
            plataformas, elige la que mejor encaja en conjunto: la tarde-noche (19:00-22:00) suele ser buena \
            en las tres; el mediodía funciona bien en Reels y TikTok.
            </como_elegir_las_horas>

            <cuantos_publicar>
            - Elige cuántos clips salen hoy, sin pasar de "clips_maximos_hoy" ni del tope de cada \
            plataforma. Un ritmo constante rinde más que picos: mejor 3 todos los días que 6 un día y 0 al \
            siguiente.
            - En modo acelerado ve a por el máximo si hay buenas horas libres para todos; si no, menos.
            - Reparte los clips a lo largo del día en las buenas franjas; no los amontones en la misma hora.
            </cuantos_publicar>

            <orden>
            El orden lo marca la cola: un vídeo se publica entero, clip a clip, antes de empezar el \
            siguiente, para que quien entre al perfil encuentre los clips de un vídeo seguidos. Tú decides \
            CUÁNTOS clips salen hoy y A QUÉ HORA: devuelve los primeros de la cola (c1, c2, c3…) con su \
            hora. Si eliges otros, el servidor publica igualmente los primeros de la cola con tus horas.
            </orden>

            <reglas>
            - Horas en formato HH:MM entre "hora_minima" y "hora_maxima".
            - Deja al menos "separacion_minima_min" minutos entre dos clips, contando las horas ya ocupadas \
            de cualquier plataforma.
            - Cada clip que elijas va en TODAS las plataformas conectadas a la hora que le des.
            - El servidor corrige lo que no cumpla estas reglas, pero cuanto menos tenga que corregir, más \
            se parecerá el plan a lo que decidiste.
            </reglas>

            <ejemplo>
            La cola empieza por los dos clips que le quedan al vídeo en curso (c1, c2) y sigue con el \
            vídeo siguiente (c3, c4…); modo acelerado con clips_maximos_hoy 4:
            {"publicaciones": [{"clip": "c1", "hora": "13:30"}, {"clip": "c2", "hora": "16:00"}, \
            {"clip": "c3", "hora": "19:30"}, {"clip": "c4", "hora": "21:30"}], \
            "explicacion": "Termino el vídeo en curso a mediodía y por la tarde; c3 y c4 abren el \
            siguiente en el pico de la noche."}
            </ejemplo>

            Devuelve SOLO este JSON, sin texto antes ni después:
            {"publicaciones": [{"clip": "<id de la cola>", "hora": "HH:MM"}],
             "explicacion": "<una o dos frases con el criterio>"}
            """;

    private final ClipRepository clips;
    private final VideoJobRepository jobs;
    private final ScheduledPostRepository posts;
    private final PublicationPlanRepository plans;
    private final ContentAnalysisRepository analyses;
    private final PublicationService publications;
    private final StatsDashboard dashboard;
    private final SettingService settings;
    private final LlmClient llm;
    private final ObjectMapper objectMapper;
    private final LogEventService logEvents;
    private final TransactionTemplate tx;

    /** Off only in tests that drive the planner by hand. */
    @Value("${cliphive.planner.scheduler-enabled:true}")
    private boolean schedulerEnabled = true;

    /** Replaceable in tests, which need "now" to be a known time of day. */
    private Clock clock = Clock.system(ZONE);

    void setClock(Clock clock) {
        this.clock = clock;
    }

    public PublicationPlanner(ClipRepository clips, VideoJobRepository jobs, ScheduledPostRepository posts,
                              PublicationPlanRepository plans, ContentAnalysisRepository analyses,
                              PublicationService publications, StatsDashboard dashboard, SettingService settings,
                              LlmClient llm, ObjectMapper objectMapper, LogEventService logEvents,
                              TransactionTemplate tx) {
        this.clips = clips;
        this.jobs = jobs;
        this.posts = posts;
        this.plans = plans;
        this.analyses = analyses;
        this.publications = publications;
        this.dashboard = dashboard;
        this.settings = settings;
        this.llm = llm;
        this.objectMapper = objectMapper;
        this.logEvents = logEvents;
        this.tx = tx;
    }

    /** What the superadmin shows about today's plan. */
    public record PlanView(Instant createdAt, String source, String explanation, String trigger,
                           int scheduled, List<String> adjustments) {}

    // ── Triggers ───────────────────────────────────────────────────────────

    @Scheduled(cron = "0 */10 * * * *", zone = "Europe/Madrid")
    public void tick() {
        if (!schedulerEnabled) return;
        try {
            check();
        } catch (RuntimeException e) {
            log.error("Publication planner failed: {}", e.getMessage(), e);
        }
    }

    /** Plans at 07:00 (or later if the server was down) and re-plans when 3+ new clips are ready. */
    void check() {
        if (!isActive()) return;
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(ZONE);
        if (now.toLocalTime().isBefore(DAILY_RUN)) return;
        Optional<PublicationPlan> today = plans.findByPlanDate(now.toLocalDate());
        if (today.isEmpty()) {
            plan("plan diario");
        } else if (newClipsSince(today.get()) >= REPLAN_NEW_CLIPS) {
            plan("clips nuevos en cola");
        }
    }

    /** True in the {@code claude} publish mode. */
    public boolean isActive() {
        return settings.publishMode() == PublishMode.CLAUDE;
    }

    /**
     * Ready clips the last plan didn't see. Not "updated since": a clip saved for
     * any other reason isn't new, and the clips of a job that finishes rendering
     * after the plan are, however long ago each one rendered.
     */
    private long newClipsSince(PublicationPlan plan) {
        Object seen = plan.getInputSummaryJson() == null ? null : plan.getInputSummaryJson().get(SEEN_CLIPS);
        if (!(seen instanceof List<?> ids)) {
            // Plans saved before the list existed
            return clips.findQueue(ClipState.RENDERED, PUBLISHABLE_JOBS).stream()
                    .filter(c -> c.getUpdatedAt() != null && c.getUpdatedAt().isAfter(plan.getCreatedAt()))
                    .count();
        }
        Set<String> known = new HashSet<>();
        ids.forEach(id -> known.add(String.valueOf(id)));
        return clips.findQueue(ClipState.RENDERED, PUBLISHABLE_JOBS).stream()
                .filter(c -> !known.contains(c.getId().toString()))
                .count();
    }

    // ── Planning ───────────────────────────────────────────────────────────

    /** Plans (or re-plans) the rest of today. Returns how many publications were scheduled. */
    public synchronized int plan(String trigger) {
        List<Platform> platforms = publications.connectedPlatforms();
        if (platforms.isEmpty()) {
            log.info("Planner: no connected platforms, nothing to plan");
            return 0;
        }
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(ZONE);
        LocalDate day = now.toLocalDate();
        Instant dayStart = day.atStartOfDay(ZONE).toInstant();
        Instant dayEnd = day.plusDays(1).atStartOfDay(ZONE).toInstant();
        Instant windowStart = now.toInstant().plus(LEAD_TIME);
        Instant windowEnd = day.atTime(WINDOW_END).atZone(ZONE).toInstant();

        // Append-only (DESIGN.md §10.4): what's already scheduled stays where it is

        Map<UUID, VideoProgress> progress = new LinkedHashMap<>();
        List<Clip> queue = publishingOrder(clips.findQueue(ClipState.RENDERED, PUBLISHABLE_JOBS), progress);
        List<PlanValidator.Candidate> candidates = new ArrayList<>();
        Map<UUID, Clip> clipById = new LinkedHashMap<>();
        for (Clip c : queue.subList(0, Math.min(queue.size(), MAX_QUEUE_IN_PROMPT))) {
            String key = "c" + (candidates.size() + 1);
            candidates.add(new PlanValidator.Candidate(key, c.getId(), c.getVideoJob().getId(), c.getClipIndex(),
                    c.getTitle(), progress.get(c.getVideoJob().getId()).started()));
            clipById.put(c.getId(), c);
        }

        Map<Platform, List<Instant>> occupied = new EnumMap<>(Platform.class);
        Set<UUID> clipsToday = new HashSet<>();
        for (ScheduledPost p : posts.findInStatesBetween(TAKEN, dayStart, dayEnd)) {
            occupied.computeIfAbsent(p.getPlatform(), k -> new ArrayList<>())
                    .add(p.getPublishedAt() != null ? p.getPublishedAt() : p.getScheduledAt());
            clipsToday.add(p.getClip().getId());
        }
        List<ScheduledPost> scheduled = posts.findByState(ScheduledPostState.SCHEDULED);
        Pace pace = pace(clipsToday.size(), behind(platforms, now.toInstant(), scheduled));
        Map<Platform, Integer> remaining = new EnumMap<>(Platform.class);
        for (Platform p : platforms) {
            remaining.put(p, capacity(p, now.toInstant(), occupied.getOrDefault(p, List.of()).size(), scheduled));
        }
        Duration minGap = Duration.ofMinutes(settings.publishMinGapMinutes());
        var ctx = new PlanValidator.Context(platforms, remaining, occupied, minGap,
                windowStart, windowEnd, day, ZONE, pace.remainingToday());

        // Claude proposes…
        String source = "claude";
        String explanation = null;
        List<PlanValidator.Proposal> proposals = List.of();
        Map<String, Object> rawOutput = Map.of();
        Map<String, Object> input = input(now, ctx, candidates, clipById, queue.size(), pace, progress);
        if (!candidates.isEmpty() && windowStart.isBefore(windowEnd) && pace.remainingToday() > 0) {
            try {
                JsonNode out = askClaude(input);
                proposals = parseProposals(out);
                explanation = out.path("explicacion").asText(null);
                rawOutput = objectMapper.convertValue(out, Map.class);
            } catch (IOException | RuntimeException e) {
                log.warn("Planner: Claude failed ({}), using the default slots", e.getMessage());
                source = "franjas";
                explanation = "Claude no respondió bien (" + e.getMessage() + "): se usan las franjas por defecto.";
                proposals = PlanValidator.fallback(candidates, platforms, defaultSlots());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 0;
            }
        }

        // …the server guarantees
        PlanValidator.Result result = PlanValidator.validate(candidates, proposals, ctx);
        save(result, clipById);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("origen", source);
        if (explanation != null) output.put("explicacion", explanation);
        output.put("respuesta", rawOutput);
        Map<String, Object> validations = new LinkedHashMap<>();
        validations.put("ajustes", result.adjustments());
        validations.put("programadas", result.assignments().size());
        if (pace.heldBack() > 0) {
            List<String> adjustments = new ArrayList<>(result.adjustments());
            List<String> late = new ArrayList<>();
            pace.behind().forEach((p, n) -> { if (n > 0) late.add(p.label() + " " + n); });
            adjustments.addFirst("Plataformas a la par: hay publicaciones atrasadas (" + String.join(", ", late)
                    + "), así que hoy salen " + pace.heldBack() + " clip(s) menos hasta que se pongan al día.");
            validations.put("ajustes", adjustments);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("motivo", trigger);
        summary.put("clips_en_cola", queue.size());
        summary.put("plataformas", platforms.stream().map(Platform::label).toList());
        summary.put("topes_restantes", remaining);
        summary.put("ritmo", pace.asMap());
        summary.put(SEEN_CLIPS, queue.stream().map(c -> c.getId().toString()).toList());
        tx.executeWithoutResult(s -> plans.findByPlanDate(day).ifPresentOrElse(
                p -> { p.replace(summary, output, validations); plans.save(p); },
                () -> plans.save(new PublicationPlan(day, summary, output, validations))));

        int scheduledClips = (int) result.assignments().stream().map(PlanValidator.Assignment::clipId).distinct().count();
        log.info("Planner ({}): {} publication(s) for {} clip(s) today, source {}", trigger,
                result.assignments().size(), scheduledClips, source);
        logEvents.info(COMPONENT, "Plan de hoy (" + trigger + "): " + scheduledClips + " clip(s), "
                + result.assignments().size() + " publicación(es)"
                + ("franjas".equals(source) ? " con las franjas por defecto" : " según Claude"));
        return result.assignments().size();
    }

    /** What a video has already out or scheduled: when it started and how many clips. */
    record VideoProgress(Instant startedAt, int clipsOut, Instant lastOut) {
        boolean started() {
            return startedAt != null;
        }
    }

    /**
     * The queue in publishing order (DESIGN.md §10.4): first the videos in
     * progress — with clips published or scheduled — from the one that started
     * earliest, then the rest oldest first; each video's clips in index order.
     * Fills {@code progress} for each video, which Claude also gets.
     */
    List<Clip> publishingOrder(List<Clip> queue, Map<UUID, VideoProgress> progress) {
        Map<UUID, Integer> createdOrder = new LinkedHashMap<>();
        for (Clip c : queue) createdOrder.putIfAbsent(c.getVideoJob().getId(), createdOrder.size());
        // Every queued video's publications in one query, by video
        Map<UUID, List<ScheduledPost>> postsByJob = new HashMap<>();
        for (ScheduledPost p : createdOrder.isEmpty() ? List.<ScheduledPost>of()
                : posts.findWithClipByJobIdIn(createdOrder.keySet())) {
            postsByJob.computeIfAbsent(p.getClip().getVideoJob().getId(), k -> new ArrayList<>()).add(p);
        }
        for (UUID job : createdOrder.keySet()) {
            progress.computeIfAbsent(job, id -> {
                Instant first = null, last = null;
                Set<UUID> out = new HashSet<>();
                for (ScheduledPost p : postsByJob.getOrDefault(id, List.of())) {
                    if (!TAKEN.contains(p.getState())) continue;
                    Instant at = p.getPublishedAt() != null ? p.getPublishedAt() : p.getScheduledAt();
                    if (first == null || at.isBefore(first)) first = at;
                    if (p.getState() == ScheduledPostState.PUBLISHED && (last == null || at.isAfter(last))) last = at;
                    out.add(p.getClip().getId());
                }
                return new VideoProgress(first, out.size(), last);
            });
        }
        List<Clip> ordered = new ArrayList<>(queue);
        ordered.sort(Comparator
                .comparing((Clip c) -> progress.get(c.getVideoJob().getId()).startedAt(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(c -> createdOrder.get(c.getVideoJob().getId()))
                .thenComparingInt(Clip::getClipIndex));
        return ordered;
    }

    /**
     * How many more publications a platform takes today (DESIGN.md §10.4): its
     * cap minus what's taken today. A platform with a publication waiting out
     * its limit takes none — except a draft platform (TikTok): its pending-drafts
     * cap no longer holds the plan back, you're emailed to upload by hand (§11.3).
     */
    private int capacity(Platform platform, Instant now, int takenToday, List<ScheduledPost> scheduled) {
        boolean waitingOutLimit = !publications.deliversAsDraft(platform)
                && scheduled.stream().anyMatch(p ->
                        p.getPlatform() == platform && p.isPostponed() && p.getScheduledAt().isAfter(now));
        if (waitingOutLimit) return 0;
        return Math.max(0, publications.dailyLimit(platform) - takenToday);
    }

    /**
     * Today's pace (DESIGN.md §10.4): the queue (clips ready or on their way)
     * divided by the normal clips a day says for how many days there's content;
     * past {@code publish.backlog-days} the limit rises to the accelerated one.
     */
    record Pace(long pendingClips, int normalPerDay, int maxPerDay, int backlogDays, boolean accelerated,
                int limitToday, Map<Platform, Integer> behind, int heldBack, int remainingToday) {
        double days() {
            return normalPerDay == 0 ? 0 : pendingClips / (double) normalPerDay;
        }

        Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("clips_pendientes", pendingClips);
            m.put("dias_de_contenido_a_ritmo_normal", Math.round(days() * 10) / 10.0);
            m.put("ritmo_normal_clips_dia", normalPerDay);
            m.put("modo_acelerado", accelerated);
            if (accelerated) m.put("motivo", "la cola da para más de " + backlogDays + " días a ritmo normal");
            m.put("clips_maximos_hoy", limitToday);
            if (heldBack > 0) {
                Map<String, Integer> late = new LinkedHashMap<>();
                behind.forEach((p, n) -> late.put(p.name().toLowerCase(Locale.ROOT), n));
                m.put("retraso_por_plataforma", late);
                m.put("clips_menos_hoy_por_retraso", heldBack);
            }
            m.put("clips_que_aun_caben_hoy", remainingToday);
            return m;
        }
    }

    /**
     * Today's limit also drops by how far the most delayed platform is behind the
     * least delayed one (DESIGN.md §10.4, "plataformas a la par"), so the others
     * don't run ahead while one piles up a queue.
     */
    Pace pace(int clipsTakenToday, Map<Platform, Integer> behind) {
        long pending = clips.countByStateIn(PENDING_CLIPS);
        int normal = settings.publishClipsPerDay();
        int max = settings.publishMaxClipsPerDay();
        int backlog = settings.publishBacklogDays();
        boolean accelerated = pending > (long) normal * backlog;
        int limit = accelerated ? max : normal;
        int held = behind.isEmpty() ? 0 : Collections.max(behind.values()) - Collections.min(behind.values());
        return new Pace(pending, normal, max, backlog, accelerated, limit, behind, held,
                Math.max(0, limit - clipsTakenToday - held));
    }

    /** A due publication still waiting this long counts as delayed. */
    static final Duration LATE_AFTER = Duration.ofMinutes(30);

    /**
     * Publications each platform has delayed: due over {@link #LATE_AFTER} ago and
     * not out, postponed by a platform limit, or failed.
     */
    Map<Platform, Integer> behind(List<Platform> platforms, Instant now, List<ScheduledPost> scheduled) {
        Map<Platform, Integer> out = new EnumMap<>(Platform.class);
        platforms.forEach(p -> out.put(p, 0));
        Instant lateBefore = now.minus(LATE_AFTER);
        for (ScheduledPost p : scheduled) {
            if (out.containsKey(p.getPlatform()) && (p.isPostponed() || p.getScheduledAt().isBefore(lateBefore))) {
                out.merge(p.getPlatform(), 1, Integer::sum);
            }
        }
        for (ScheduledPost p : posts.findByState(ScheduledPostState.FAILED)) {
            if (out.containsKey(p.getPlatform())) out.merge(p.getPlatform(), 1, Integer::sum);
        }
        return out;
    }

    private void save(PlanValidator.Result result, Map<UUID, Clip> clipById) {
        tx.executeWithoutResult(s -> {
            for (PlanValidator.Assignment a : result.assignments()) {
                Clip clip = clips.findById(a.clipId()).orElse(null);
                if (clip == null || clip.getState() == ClipState.CANCELLED) continue;
                posts.save(new ScheduledPost(clip, a.platform(), a.at(), ScheduledPostOrigin.CLAUDE_PLAN));
                if (clip.getState() == ClipState.RENDERED) {
                    clip.setState(ClipState.SCHEDULED);
                    clips.save(clip);
                }
                VideoJob job = clip.getVideoJob();
                if (job.getState() == VideoJobState.RENDERED) {
                    job.setState(VideoJobState.SCHEDULED);
                    jobs.save(job);
                }
            }
        });
    }

    // ── Claude ─────────────────────────────────────────────────────────────

    private JsonNode askClaude(Map<String, Object> input) throws IOException, InterruptedException {
        String stdin = objectMapper.writeValueAsString(input);
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String raw = llm.call(PROMPT, stdin);
                JsonNode out = objectMapper.readTree(LlmClient.extractJson(raw));
                if (!out.path("publicaciones").isArray()) throw new IOException("falta \"publicaciones\"");
                return out;
            } catch (IOException e) {
                last = e;
            } catch (RuntimeException e) {
                last = new IOException("JSON no válido: " + e.getMessage(), e);
            }
            log.warn("Planner: Claude attempt {} failed: {}", attempt, last.getMessage());
        }
        throw last;
    }

    static List<PlanValidator.Proposal> parseProposals(JsonNode out) {
        List<PlanValidator.Proposal> proposals = new ArrayList<>();
        for (JsonNode p : out.path("publicaciones")) {
            Platform platform = null;
            LocalTime time;
            try {
                // One time per clip for every platform; a platform named anyway is still understood
                String named = p.path("plataforma").asText("").trim();
                if (!named.isEmpty()) platform = Platform.valueOf(named.toUpperCase(Locale.ROOT));
                time = LocalTime.parse(p.path("hora").asText("").trim());
            } catch (IllegalArgumentException | DateTimeParseException e) {
                continue; // unknown platform or bad time: dropped like any other invalid proposal
            }
            proposals.add(new PlanValidator.Proposal(p.path("clip").asText(""), platform, time));
        }
        return proposals;
    }

    private Map<String, Object> input(ZonedDateTime now, PlanValidator.Context ctx,
                                      List<PlanValidator.Candidate> candidates, Map<UUID, Clip> clipById,
                                      int queueSize, Pace pace, Map<UUID, VideoProgress> progress) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("fecha", now.toLocalDate().toString());
        in.put("dia_semana", now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.forLanguageTag("es")));
        in.put("hora_actual", hhmm(now.toInstant()));
        in.put("hora_minima", hhmm(ctx.windowStart()));
        in.put("hora_maxima", WINDOW_END.toString());
        in.put("separacion_minima_min", ctx.minGap().toMinutes());
        in.put("publico", settings.publishAudience());
        in.put("calendario", SpanishCalendar.describe(now.toLocalDate()));
        in.put("ritmo", pace.asMap());

        List<Map<String, Object>> platforms = new ArrayList<>();
        for (Platform p : ctx.platforms()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("plataforma", p.name().toLowerCase(Locale.ROOT));
            m.put("tope_restante_hoy", ctx.remaining().get(p));
            m.put("horas_ocupadas_hoy", ctx.occupied().getOrDefault(p, List.of()).stream().sorted().map(this::hhmm).toList());
            platforms.add(m);
        }
        in.put("plataformas", platforms);

        Map<UUID, String> contentTypes = new LinkedHashMap<>();
        List<Map<String, Object>> queue = new ArrayList<>();
        for (PlanValidator.Candidate c : candidates) {
            Clip clip = clipById.get(c.clipId());
            VideoJob job = clip.getVideoJob();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.key());
            m.put("titulo", clip.getTitle());
            m.put("duracion_s", clip.getDurationSeconds());
            m.put("video", job.getTitle());
            m.put("clip_del_video", clip.getClipIndex() + 1);
            if (clip.getSectionTitle() != null) m.put("seccion", clip.getSectionTitle());
            if (clip.partLabel() != null) m.put("parte_de_serie", clip.partLabel());
            m.put("tipo_contenido", contentTypes.computeIfAbsent(job.getId(),
                    id -> analyses.findByVideoJobId(id).map(a -> a.getContentType()).orElse(null)));
            if (clip.getUpdatedAt() != null) {
                m.put("horas_en_cola", Duration.between(clip.getUpdatedAt(), now.toInstant()).toHours());
            }
            queue.add(m);
        }
        in.put("cola", queue);
        if (queueSize > candidates.size()) in.put("clips_en_cola_total", queueSize);

        // Per video: started or not, and what's left — so a started one is finished first
        Map<UUID, Map<String, Object>> videos = new LinkedHashMap<>();
        for (PlanValidator.Candidate c : candidates) {
            Clip clip = clipById.get(c.clipId());
            Map<String, Object> v = videos.computeIfAbsent(c.jobId(), id -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("video", clip.getVideoJob().getTitle());
                m.put("ya_empezado", c.videoStarted());
                VideoProgress vp = progress.get(id);
                if (vp != null && vp.started()) {
                    m.put("clips_publicados_o_programados", vp.clipsOut());
                    if (vp.lastOut() != null) m.put("ultima_publicacion",
                            vp.lastOut().atZone(ZONE).toLocalDateTime().withSecond(0).withNano(0).toString());
                }
                m.put("clips_en_cola", 0);
                m.put("ids", new ArrayList<String>());
                return m;
            });
            v.put("clips_en_cola", (Integer) v.get("clips_en_cola") + 1);
            @SuppressWarnings("unchecked") List<String> ids = (List<String>) v.get("ids");
            ids.add(c.key());
        }
        in.put("videos", new ArrayList<>(videos.values()));

        List<Map<String, Object>> history = new ArrayList<>();
        for (ScheduledPost p : posts.findByStateAndPublishedAtAfter(ScheduledPostState.PUBLISHED,
                now.toInstant().minus(Duration.ofDays(7)))) {
            history.add(Map.of("plataforma", p.getPlatform().name().toLowerCase(Locale.ROOT),
                    "cuando", p.getPublishedAt().atZone(ZONE).toLocalDateTime().withSecond(0).withNano(0).toString()));
        }
        in.put("publicado_ultimos_7_dias", history);

        Map<String, Object> byHour = new LinkedHashMap<>();
        for (Platform p : ctx.platforms()) {
            try {
                var buckets = dashboard.byHour(p).stream()
                        .filter(b -> b.clips() > 0)
                        .map(b -> Map.<String, Object>of("hora", b.hour(), "vistas_medias", Math.round(b.averageViews()),
                                "clips", b.clips()))
                        .toList();
                if (!buckets.isEmpty()) byHour.put(p.name().toLowerCase(Locale.ROOT), buckets);
            } catch (RuntimeException e) {
                log.debug("No stats by hour for {}: {}", p, e.getMessage());
            }
        }
        if (!byHour.isEmpty()) in.put("vistas_por_hora_de_publicacion", byHour);
        return in;
    }

    private List<LocalTime> defaultSlots() {
        List<LocalTime> slots = new ArrayList<>();
        for (String s : settings.publishDefaultSlots().split(",")) {
            try {
                slots.add(LocalTime.parse(s.trim()));
            } catch (DateTimeParseException e) {
                log.warn("Ignoring bad default slot '{}'", s);
            }
        }
        return slots.isEmpty() ? List.of(LocalTime.of(13, 0), LocalTime.of(20, 0)) : slots;
    }

    private String hhmm(Instant t) {
        return t.atZone(ZONE).toLocalTime().withSecond(0).withNano(0).toString();
    }

    // ── Superadmin ─────────────────────────────────────────────────────────

    /** Today's plan as the publications page shows it, if there is one. */
    @SuppressWarnings("unchecked")
    public Optional<PlanView> today() {
        return plans.findByPlanDate(LocalDate.now(clock.withZone(ZONE))).map(p -> {
            Map<String, Object> out = p.getOutputJson();
            Map<String, Object> val = p.getValidationsAppliedJson();
            Object adjustments = val.getOrDefault("ajustes", List.of());
            return new PlanView(p.getCreatedAt(),
                    "franjas".equals(out.get("origen")) ? "Franjas por defecto" : "Claude",
                    (String) out.get("explicacion"),
                    (String) p.getInputSummaryJson().get("motivo"),
                    ((Number) val.getOrDefault("programadas", 0)).intValue(),
                    adjustments instanceof List<?> l ? (List<String>) l : List.of());
        });
    }
}
