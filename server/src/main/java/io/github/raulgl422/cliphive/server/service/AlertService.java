package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.protocol.state.ClipState;
import io.github.raulgl422.cliphive.server.model.Notification;
import io.github.raulgl422.cliphive.server.model.enums.NotificationStatus;
import io.github.raulgl422.cliphive.server.publish.PostMetadata;
import io.github.raulgl422.cliphive.server.repository.ClipRepository;
import io.github.raulgl422.cliphive.server.repository.NotificationRepository;
import io.github.raulgl422.cliphive.server.repository.ScheduledPostRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Email alerts — docs/DESIGN.md §12.3: the low queue (when fewer than X clips
 * are left to publish, one email on the way down, re-armed once the queue is
 * back at X or more, with an optional reminder every N hours while it stays
 * low), a TikTok draft ready to finish, a publication to upload by hand, and a
 * test email from the settings. Every send is recorded in {@code notification}.
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    static final String QUEUE_LOW = "QUEUE_LOW";
    static final String TEST = "TEST";
    static final String DRAFT_READY = "DRAFT_READY";
    static final String MANUAL_UPLOAD = "MANUAL_UPLOAD";
    /** "true" while the queue is below the threshold and its alert already went out. */
    static final String QUEUE_LOW_FIRED = "alert.queue-low.fired";

    /** Clips not published yet: rendered or still on their way (§12.3). */
    private static final Set<ClipState> PENDING =
            EnumSet.of(ClipState.PLANNED, ClipState.RENDERING, ClipState.RENDERED, ClipState.SCHEDULED);
    private static final Duration RATE_WINDOW = Duration.ofDays(7);
    /** A failed send isn't retried before this, so a broken SMTP doesn't mean a try every 5 minutes. */
    private static final Duration FAILED_RETRY = Duration.ofHours(1);
    private static final Locale ES = Locale.forLanguageTag("es");

    private final ClipRepository clips;
    private final ScheduledPostRepository posts;
    private final NotificationRepository notifications;
    private final SettingService settings;
    private final EmailSender email;
    private final String adminPublicUrl;

    /** Off only in tests that drive the check by hand. */
    @Value("${cliphive.alerts.scheduler-enabled:true}")
    private boolean schedulerEnabled = true;

    public AlertService(ClipRepository clips,
                        ScheduledPostRepository posts,
                        NotificationRepository notifications,
                        SettingService settings,
                        EmailSender email,
                        @Value("${cliphive.admin-public-url:}") String adminPublicUrl) {
        this.clips = clips;
        this.posts = posts;
        this.notifications = notifications;
        this.settings = settings;
        this.email = email;
        this.adminPublicUrl = adminPublicUrl.strip().replaceAll("/+$", "");
    }

    public boolean isConfigured() {
        return email.isConfigured();
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 300_000)
    public void tick() {
        if (!schedulerEnabled) return;
        try {
            checkQueue();
        } catch (RuntimeException e) {
            log.error("Alert check failed: {}", e.getMessage(), e);
        }
    }

    /** Sends the low-queue alert (or its reminder) when due. */
    public void checkQueue() {
        int threshold = settings.alertQueueThreshold();
        long pending = clips.countByStateIn(PENDING);
        boolean fired = Boolean.parseBoolean(settings.get(QUEUE_LOW_FIRED, "false"));

        if (threshold <= 0 || pending >= threshold) {
            if (fired) settings.set(QUEUE_LOW_FIRED, "false"); // re-armed for the next drop
            return;
        }
        String to = settings.alertEmail().strip();
        if (to.isEmpty() || !email.isConfigured()) return;

        Instant now = Instant.now();
        Optional<Notification> last = notifications.findTopByTypeOrderBySentAtDesc(QUEUE_LOW);
        if (last.isPresent() && last.get().getStatus() == NotificationStatus.FAILED
                && last.get().getSentAt().isAfter(now.minus(FAILED_RETRY))) {
            return;
        }

        boolean reminder = false;
        if (fired) {
            int hours = settings.alertReminderHours();
            if (hours <= 0) return;
            Instant lastSent = notifications
                    .findTopByTypeAndStatusOrderBySentAtDesc(QUEUE_LOW, NotificationStatus.SENT)
                    .map(Notification::getSentAt).orElse(Instant.EPOCH);
            if (lastSent.isAfter(now.minus(Duration.ofHours(hours)))) return;
            reminder = true;
        }

        long publishedLastWeek = posts.countClipsPublishedSince(now.minus(RATE_WINDOW));
        double perDay = publishedLastWeek / (double) RATE_WINDOW.toDays();

        String subject = (reminder ? "Recordatorio: " : "")
                + "ClipHive: quedan " + pending + " clip" + (pending == 1 ? "" : "s") + " por publicar";
        var body = new StringBuilder()
                .append("Quedan ").append(pending).append(" clip").append(pending == 1 ? "" : "s")
                .append(" pendientes de publicar (el aviso salta por debajo de ").append(threshold).append(").\n\n");
        if (perDay > 0) {
            body.append(String.format(ES, "Al ritmo de los últimos 7 días (%.1f clips/día) hay contenido para unos %.0f días.%n%n",
                    perDay, Math.floor(pending / perDay)));
        } else {
            body.append("No se ha publicado nada en los últimos 7 días, así que no hay estimación de para cuántos días hay contenido.\n\n");
        }
        body.append("Envía nuevos enlaces desde el superadmin");
        body.append(adminPublicUrl.isEmpty() ? ".\n" : ":\n" + adminPublicUrl + "/admin/jobs\n");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("pending", pending);
        metadata.put("threshold", threshold);
        metadata.put("reminder", reminder);
        if (send(QUEUE_LOW, to, subject, body.toString(), metadata)) {
            settings.set(QUEUE_LOW_FIRED, "true");
        }
    }

    /**
     * A draft reached the platform's app and has to be posted by hand (TikTok
     * before its audit, §11.4): emails the title and description to paste,
     * since the draft API doesn't take them. Silently skipped without a
     * recipient or SMTP; never throws.
     */
    public void draftReady(String platformLabel, PostMetadata metadata) {
        String to = settings.alertEmail().strip();
        if (to.isEmpty() || !email.isConfigured()) return;
        String title = metadata.title() == null ? "" : metadata.title().strip();
        String description = metadata.description() == null ? "" : metadata.description().strip();
        String body = "Hay un borrador nuevo en " + platformLabel + ". Abre la app, entra en la notificación "
                + "del borrador y publícalo con este texto:\n\n"
                + "──────────\n"
                + (description.isEmpty() ? title : title + "\n\n" + description) + "\n"
                + "──────────\n";
        try {
            send(DRAFT_READY, to, platformLabel + ": borrador listo — " + title, body, Map.of(
                    "platform", platformLabel, "title", title));
        } catch (RuntimeException e) {
            log.warn("Draft-ready email failed: {}", e.getMessage());
        }
    }

    /**
     * A platform refused a publication because of its own limit (TikTok's 5
     * pending drafts, §11.3): asks you to upload it by hand, with the text to
     * paste and links to download the clip and to mark it as uploaded. Returns
     * true if it went out; never throws.
     */
    public boolean manualUploadNeeded(String platformLabel, String reason, PostMetadata metadata,
                                      UUID jobId, UUID clipId) {
        String to = settings.alertEmail().strip();
        if (to.isEmpty() || !email.isConfigured()) return false;
        String title = metadata.title() == null ? "" : metadata.title().strip();
        String description = metadata.description() == null ? "" : metadata.description().strip();
        var body = new StringBuilder()
                .append(platformLabel).append(" no admite ahora esta publicación:\n").append(reason).append("\n\n")
                .append("Para no frenar el calendario, súbela tú a mano en ").append(platformLabel)
                .append(". ClipHive seguirá intentándolo cada hora hasta que la marques como subida.\n\n");
        if (adminPublicUrl.isEmpty()) {
            body.append("Descarga el clip desde la página del trabajo en el superadmin.\n\n");
        } else {
            body.append("1. Descarga el clip:\n").append(adminPublicUrl).append("/admin/jobs/").append(jobId)
                    .append("/clips/").append(clipId).append("/video?download=true\n\n");
        }
        body.append(adminPublicUrl.isEmpty() ? "" : "2. ").append("Publícalo con este texto:\n\n")
                .append("──────────\n")
                .append(description.isEmpty() ? title : title + "\n\n" + description).append("\n")
                .append("──────────\n\n")
                .append(adminPublicUrl.isEmpty() ? "" : "3. ")
                .append("Cuando esté subido, pulsa \"Ya lo subí a mano\" en Publicaciones (y pega el enlace si quieres), ")
                .append("o ClipHive la subirá también cuando la plataforma lo vuelva a permitir");
        body.append(adminPublicUrl.isEmpty() ? ".\n" : ":\n" + adminPublicUrl + "/admin/publications\n");
        try {
            return send(MANUAL_UPLOAD, to, platformLabel + ": súbelo a mano — " + title, body.toString(), Map.of(
                    "platform", platformLabel, "title", title, "clip", clipId.toString()));
        } catch (RuntimeException e) {
            log.warn("Manual-upload email failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * The "enviar email de prueba" button.
     *
     * @throws IllegalArgumentException with a message for the page if it can't be sent
     */
    public void sendTest(String to) {
        if (to == null || to.isBlank()) throw new IllegalArgumentException("Indica un email destinatario.");
        if (!email.isConfigured()) {
            throw new IllegalArgumentException("El SMTP no está configurado: define CLIPHIVE_SMTP_HOST y CLIPHIVE_SMTP_FROM en el .env.");
        }
        String body = "Si lees esto, los avisos por email de ClipHive funcionan.\n\n"
                + "Umbral de cola baja actual: " + settings.alertQueueThreshold() + " clips.\n";
        if (!send(TEST, to.strip(), "ClipHive: email de prueba", body, Map.of())) {
            throw new IllegalArgumentException("No se pudo enviar el email de prueba; el motivo está en los logs del servidor.");
        }
    }

    private boolean send(String type, String to, String subject, String body, Map<String, Object> metadata) {
        try {
            email.send(to, subject, body);
            notifications.save(new Notification(type, to, NotificationStatus.SENT, metadata));
            log.info("Email alert {} sent to {}", type, to);
            return true;
        } catch (RuntimeException e) {
            Map<String, Object> failed = new LinkedHashMap<>(metadata);
            failed.put("error", String.valueOf(e.getMessage()));
            notifications.save(new Notification(type, to, NotificationStatus.FAILED, failed));
            log.warn("Email alert {} to {} failed: {}", type, to, e.getMessage());
            return false;
        }
    }
}
