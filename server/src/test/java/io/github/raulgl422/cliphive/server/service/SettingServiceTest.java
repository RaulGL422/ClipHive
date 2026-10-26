package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.server.model.Setting;
import io.github.raulgl422.cliphive.server.model.enums.PublishMode;
import io.github.raulgl422.cliphive.server.repository.SettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettingServiceTest {

    @Mock
    private SettingRepository repo;

    private SettingService service;

    @BeforeEach
    void setUp() {
        service = new SettingService(repo);
    }

    // ── get ───────────────────────────────────────────────────────────────────

    @Test
    void get_returns_stored_value() {
        when(repo.findById("default.language"))
                .thenReturn(Optional.of(new Setting("default.language", "en")));

        assertThat(service.get("default.language", "es")).isEqualTo("en");
    }

    @Test
    void get_returns_default_when_absent() {
        when(repo.findById("default.language")).thenReturn(Optional.empty());

        assertThat(service.get("default.language", "es")).isEqualTo("es");
    }

    @Test
    void get_optional_is_empty_when_absent() {
        when(repo.findById("some.key")).thenReturn(Optional.empty());

        assertThat(service.get("some.key")).isEmpty();
    }

    // ── set ───────────────────────────────────────────────────────────────────

    @Test
    void set_updates_existing_setting() {
        Setting existing = new Setting("publish.mode", "immediate");
        when(repo.findById("publish.mode")).thenReturn(Optional.of(existing));

        service.set("publish.mode", "claude");

        verify(repo).save(existing);
        assertThat(existing.getValue()).isEqualTo("claude");
    }

    @Test
    void set_creates_new_setting_when_absent() {
        when(repo.findById("publish.mode")).thenReturn(Optional.empty());

        service.set("publish.mode", "claude");

        ArgumentCaptor<Setting> cap = ArgumentCaptor.forClass(Setting.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getKey()).isEqualTo("publish.mode");
        assertThat(cap.getValue().getValue()).isEqualTo("claude");
    }

    // ── Typed defaults ────────────────────────────────────────────────────────

    @Test
    void defaultLanguage_returns_es_by_default() {
        when(repo.findById(SettingService.DEFAULT_LANGUAGE)).thenReturn(Optional.empty());
        assertThat(service.defaultLanguage()).isEqualTo("es");
    }

    @Test
    void defaultFpsMode_returns_auto_by_default() {
        when(repo.findById(SettingService.DEFAULT_FPS_MODE)).thenReturn(Optional.empty());
        assertThat(service.defaultFpsMode()).isEqualTo("auto");
    }

    @Test
    void defaultReviewMode_returns_false_by_default() {
        when(repo.findById(SettingService.DEFAULT_REVIEW_MODE)).thenReturn(Optional.empty());
        assertThat(service.defaultReviewMode()).isFalse();
    }

    @Test
    void videoMaxDurationS_returns_7200_by_default() {
        when(repo.findById(SettingService.VIDEO_MAX_DURATION_S)).thenReturn(Optional.empty());
        assertThat(service.videoMaxDurationS()).isEqualTo(7200);
    }

    @Test
    void publishMode_returns_immediate_by_default() {
        when(repo.findById(SettingService.PUBLISH_MODE)).thenReturn(Optional.empty());
        assertThat(service.publishMode()).isEqualTo(PublishMode.IMMEDIATE);
    }

    @Test
    void publish_mode_reads_through_case_and_spaces() {
        when(repo.findById(SettingService.PUBLISH_MODE)).thenReturn(Optional.of(new Setting(SettingService.PUBLISH_MODE, " Claude ")));
        assertThat(service.publishMode()).isEqualTo(PublishMode.CLAUDE);
    }

    @Test
    void an_unknown_fps_mode_falls_back_instead_of_reaching_the_worker() {
        assertThat(SettingService.fpsModeOr(" 60 ", "auto")).isEqualTo("60");
        assertThat(SettingService.fpsModeOr("60fps", "auto")).isEqualTo("auto");
        assertThat(SettingService.fpsModeOr(null, "30")).isEqualTo("30");
    }

    @Test
    void alertReminderHours_returns_0_by_default() {
        when(repo.findById(SettingService.ALERT_REMINDER_HOURS)).thenReturn(Optional.empty());
        assertThat(service.alertReminderHours()).isEqualTo(0);
    }
}
