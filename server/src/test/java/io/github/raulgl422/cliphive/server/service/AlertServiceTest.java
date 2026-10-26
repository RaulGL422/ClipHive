package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.server.model.Clip;
import io.github.raulgl422.cliphive.server.model.Notification;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.model.enums.NotificationStatus;
import io.github.raulgl422.cliphive.server.publish.PostMetadata;
import io.github.raulgl422.cliphive.server.repository.ClipRepository;
import io.github.raulgl422.cliphive.server.repository.NotificationRepository;
import io.github.raulgl422.cliphive.server.repository.SettingRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Low-queue email alert (DESIGN.md §12.3): once on the way down, re-armed when
 * the queue recovers, optional reminder, failures recorded. SMTP is mocked.
 */
@SpringBootTest(properties = {
        "cliphive.alerts.scheduler-enabled=false",
        "cliphive.publishing.scheduler-enabled=false"})
@ActiveProfiles("embedded")
class AlertServiceTest {

    @MockitoBean EmailSender email;

    @Autowired AlertService alerts;
    @Autowired SettingService settings;
    @Autowired SettingRepository settingRepository;
    @Autowired NotificationRepository notifications;
    @Autowired ClipRepository clips;
    @Autowired VideoJobRepository jobs;
    @Autowired JdbcTemplate jdbc;

    private VideoJob job;

    @BeforeEach
    void setUp() {
        reset(email);
        when(email.isConfigured()).thenReturn(true);
        settings.set(SettingService.ALERT_QUEUE_THRESHOLD, "3");
        settings.set(SettingService.ALERT_EMAIL, "yo@example.com");
        settings.set(SettingService.ALERT_REMINDER_HOURS, "0");
        // A finished job: none of the schedulers touch its clips
        job = jobs.save(new VideoJob("https://example.com/v", "es", "auto", false));
        jdbc.update("UPDATE video_job SET state = 'SCHEDULED' WHERE id = ?", job.getId());
    }

    @AfterEach
    void tearDown() {
        notifications.deleteAll();
        clips.deleteAll();
        jobs.deleteAll();
        settingRepository.deleteAll();
    }

    /** Leaves exactly {@code n} clips pending to publish. */
    private void pending(int n) {
        clips.deleteAll();
        for (int i = 0; i < n; i++) {
            UUID id = clips.save(new Clip(job, i, List.of())).getId();
            jdbc.update("UPDATE clip SET state = 'SCHEDULED' WHERE id = ?", id);
        }
    }

    @Test
    void alerts_once_on_the_way_down_and_rearms_when_the_queue_recovers() {
        pending(5);
        alerts.checkQueue();
        verify(email, never()).send(anyString(), anyString(), anyString());

        pending(2);
        alerts.checkQueue();
        alerts.checkQueue(); // still low: no second email
        verify(email, times(1)).send(eq("yo@example.com"), contains("quedan 2 clips"), contains("No se ha publicado nada"));
        assertThat(notifications.findAll()).singleElement()
                .extracting(Notification::getStatus).isEqualTo(NotificationStatus.SENT);

        pending(4);
        alerts.checkQueue(); // recovered → re-armed
        pending(1);
        alerts.checkQueue();
        verify(email, times(1)).send(anyString(), contains("quedan 1 clip "), anyString());
    }

    @Test
    void reminder_repeats_while_the_queue_stays_low() {
        settings.set(SettingService.ALERT_REMINDER_HOURS, "6");
        pending(1);
        alerts.checkQueue();
        alerts.checkQueue(); // too soon for a reminder
        verify(email, times(1)).send(anyString(), anyString(), anyString());

        jdbc.update("UPDATE notification SET sent_at = now() - interval '7 hours'");
        alerts.checkQueue();
        verify(email).send(anyString(), contains("Recordatorio"), anyString());
    }

    @Test
    void disabled_without_threshold_recipient_or_smtp() {
        pending(0);
        settings.set(SettingService.ALERT_QUEUE_THRESHOLD, "0");
        alerts.checkQueue();
        settings.set(SettingService.ALERT_QUEUE_THRESHOLD, "3");
        settings.set(SettingService.ALERT_EMAIL, "");
        alerts.checkQueue();
        settings.set(SettingService.ALERT_EMAIL, "yo@example.com");
        when(email.isConfigured()).thenReturn(false);
        alerts.checkQueue();

        verify(email, never()).send(anyString(), anyString(), anyString());
        assertThat(notifications.count()).isZero();
    }

    @Test
    void a_failed_send_is_recorded_and_not_retried_straight_away() {
        doThrow(new MailSendException("535 invalid API key")).when(email).send(anyString(), anyString(), anyString());
        pending(1);
        alerts.checkQueue();
        alerts.checkQueue();

        verify(email, times(1)).send(anyString(), anyString(), anyString());
        Notification failed = notifications.findAll().getFirst();
        assertThat(failed.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(failed.getMetadataJson()).containsEntry("error", "535 invalid API key");
        assertThat(settings.get(AlertService.QUEUE_LOW_FIRED, "false")).as("still armed").isEqualTo("false");
    }

    @Test
    void a_draft_ready_email_carries_the_text_to_paste() {
        alerts.draftReady("TikTok", new PostMetadata("Mi clip", "Con #hashtag"));
        verify(email).send(eq("yo@example.com"), eq("TikTok: borrador listo — Mi clip"),
                contains("Mi clip\n\nCon #hashtag"));
        assertThat(notifications.findAll()).singleElement()
                .extracting(Notification::getType).isEqualTo(AlertService.DRAFT_READY);

        // Without a recipient it's skipped, and a failure never reaches the publisher
        settings.set(SettingService.ALERT_EMAIL, "");
        alerts.draftReady("TikTok", new PostMetadata("Otro", ""));
        settings.set(SettingService.ALERT_EMAIL, "yo@example.com");
        doThrow(new MailSendException("down")).when(email).send(anyString(), anyString(), anyString());
        alerts.draftReady("TikTok", new PostMetadata("Otro", ""));
        verify(email, times(2)).send(anyString(), anyString(), anyString());
    }

    @Test
    void test_email_reports_why_it_could_not_be_sent() {
        alerts.sendTest("yo@example.com");
        verify(email).send(eq("yo@example.com"), eq("ClipHive: email de prueba"), any());

        assertThatThrownBy(() -> alerts.sendTest(" ")).hasMessageContaining("destinatario");
        when(email.isConfigured()).thenReturn(false);
        assertThatThrownBy(() -> alerts.sendTest("yo@example.com")).hasMessageContaining("CLIPHIVE_SMTP_HOST");
    }

    @Test
    void a_manual_upload_email_has_the_text_and_the_links() {
        UUID clipId = UUID.randomUUID();
        boolean sent = alerts.manualUploadNeeded("TikTok", "TikTok: hay demasiados borradores",
                new PostMetadata("Mi clip (Parte 1/2)", "Descripción\n\n#tag"),
                job.getId(), clipId);

        assertThat(sent).isTrue();
        verify(email).send(eq("yo@example.com"), eq("TikTok: súbelo a mano — Mi clip (Parte 1/2)"),
                contains("Mi clip (Parte 1/2)\n\nDescripción\n\n#tag"));
        verify(email).send(anyString(), anyString(), contains("demasiados borradores"));
        assertThat(notifications.findAll()).singleElement()
                .extracting(Notification::getType).isEqualTo(AlertService.MANUAL_UPLOAD);

        settings.set(SettingService.ALERT_EMAIL, "");
        assertThat(alerts.manualUploadNeeded("TikTok", "x",
                new PostMetadata("Otro", ""), job.getId(), clipId)).isFalse();
    }
}
