package io.github.raulgl422.cliphive.server.web.admin;

import io.github.raulgl422.cliphive.server.model.enums.PublishMode;
import io.github.raulgl422.cliphive.server.repository.AdminUserRepository;
import io.github.raulgl422.cliphive.server.security.EncryptionService;
import io.github.raulgl422.cliphive.server.service.AlertService;
import io.github.raulgl422.cliphive.server.service.FileCleanupService;
import io.github.raulgl422.cliphive.server.service.SettingService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Settings page — docs/DESIGN.md §12.2. Editable runtime config for the
 * superadmin (language, fps mode, publish mode, duration limits, email alerts…).
 * Secrets stay in env vars and are never shown here.
 */
@Controller
@RequestMapping("/admin/settings")
public class AdminSettingsController {

    private final SettingService settingService;
    private final AdminUserRepository adminUserRepository;
    private final EncryptionService encryptionService;
    private final AlertService alertService;
    private final FileCleanupService cleanup;

    public AdminSettingsController(SettingService settingService,
                                   AdminUserRepository adminUserRepository,
                                   EncryptionService encryptionService,
                                   AlertService alertService,
                                   FileCleanupService cleanup) {
        this.cleanup = cleanup;
        this.settingService = settingService;
        this.adminUserRepository = adminUserRepository;
        this.encryptionService = encryptionService;
        this.alertService = alertService;
    }

    @GetMapping
    public String settings(Authentication auth, Model model) {
        model.addAttribute("defaultLanguage", settingService.defaultLanguage());
        model.addAttribute("defaultFpsMode", settingService.defaultFpsMode());
        model.addAttribute("defaultReviewMode", settingService.defaultReviewMode());
        model.addAttribute("correctTranscript", settingService.correctTranscript());
        model.addAttribute("publishMode", settingService.publishMode().value());
        model.addAttribute("videoMaxDurationS", settingService.videoMaxDurationS());
        model.addAttribute("tasksStallMinutes", settingService.tasksStallMinutes());
        model.addAttribute("publishMinGapMinutes", settingService.publishMinGapMinutes());
        model.addAttribute("youtubeAudited", settingService.youtubeAudited());
        model.addAttribute("subtitlesBottomPercent", settingService.subtitlesBottomPercent());
        model.addAttribute("overlayEnabled", settingService.overlayEnabled());
        model.addAttribute("clipsCoverage", settingService.clipsCoverage());
        model.addAttribute("filesRetentionDays", settingService.filesRetentionDays());
        model.addAttribute("logsRetentionDays", settingService.logsRetentionDays());
        FileCleanupService.DiskUsage usage = cleanup.usage();
        model.addAttribute("jobsSize", FileCleanupService.human(usage.jobsBytes()));
        model.addAttribute("diskFree", usage.freeBytes() > 0 ? FileCleanupService.human(usage.freeBytes()) : null);
        model.addAttribute("publishDefaultSlots", settingService.publishDefaultSlots());
        model.addAttribute("publishClipsPerDay", settingService.publishClipsPerDay());
        model.addAttribute("publishMaxClipsPerDay", settingService.publishMaxClipsPerDay());
        model.addAttribute("publishBacklogDays", settingService.publishBacklogDays());
        model.addAttribute("publishAudience", settingService.publishAudience());
        model.addAttribute("publishPlaylists", settingService.publishPlaylists());
        model.addAttribute("alertQueueThreshold", settingService.alertQueueThreshold());
        model.addAttribute("alertEmail", settingService.alertEmail());
        model.addAttribute("alertReminderHours", settingService.alertReminderHours());
        model.addAttribute("smtpConfigured", alertService.isConfigured());
        model.addAttribute("encryptionConfigured", encryptionService.isConfigured());
        String username = auth.getName();
        adminUserRepository.findByUsername(username).ifPresent(u ->
                model.addAttribute("totpEnabled", u.isTotpEnabled()));
        return "admin/settings";
    }

    @PostMapping
    public String save(@RequestParam(required = false) String defaultLanguage,
                       @RequestParam(required = false) String defaultFpsMode,
                       @RequestParam(required = false) String defaultReviewMode,
                       @RequestParam(required = false) String correctTranscript,
                       @RequestParam(required = false) String publishMode,
                       @RequestParam(required = false) String videoMaxDurationS,
                       @RequestParam(required = false) String tasksStallMinutes,
                       @RequestParam(required = false) String publishMinGapMinutes,
                       @RequestParam(required = false) String youtubeAudited,
                       @RequestParam(required = false) String subtitlesBottomPercent,
                       @RequestParam(required = false) String overlayEnabled,
                       @RequestParam(required = false) String clipsCoverage,
                       @RequestParam(required = false) String filesRetentionDays,
                       @RequestParam(required = false) String logsRetentionDays,
                       @RequestParam(required = false) String publishDefaultSlots,
                       @RequestParam(required = false) String publishClipsPerDay,
                       @RequestParam(required = false) String publishMaxClipsPerDay,
                       @RequestParam(required = false) String publishBacklogDays,
                       @RequestParam(required = false) String publishAudience,
                       @RequestParam(required = false) String publishPlaylists,
                       @RequestParam(required = false) String alertQueueThreshold,
                       @RequestParam(required = false) String alertEmail,
                       @RequestParam(required = false) String alertReminderHours,
                       RedirectAttributes ra) {
        saveIfPresent(SettingService.DEFAULT_LANGUAGE, defaultLanguage);
        // Only known values: anything else would silently stop scheduling or reach the worker
        if (defaultFpsMode != null) saveIfPresent(SettingService.DEFAULT_FPS_MODE,
                SettingService.fpsModeOr(defaultFpsMode, settingService.defaultFpsMode()));
        saveIfPresent(SettingService.DEFAULT_REVIEW_MODE, defaultReviewMode);
        saveIfPresent(SettingService.TRANSCRIPT_CORRECT, correctTranscript);
        PublishMode.parse(publishMode).ifPresent(m -> settingService.set(SettingService.PUBLISH_MODE, m.value()));
        saveIfPresent(SettingService.VIDEO_MAX_DURATION_S, videoMaxDurationS);
        saveIfPresent(SettingService.TASKS_STALL_MINUTES, tasksStallMinutes);
        saveIfPresent(SettingService.PUBLISH_MIN_GAP_MINUTES, publishMinGapMinutes);
        saveIfPresent(SettingService.YOUTUBE_AUDITED, youtubeAudited);
        saveIfPresent(SettingService.SUBTITLES_BOTTOM_PERCENT, subtitlesBottomPercent);
        saveIfPresent(SettingService.OVERLAY_ENABLED, overlayEnabled);
        saveIfPresent(SettingService.CLIPS_COVERAGE, clipsCoverage);
        saveIfPresent(SettingService.FILES_RETENTION_DAYS, filesRetentionDays);
        saveIfPresent(SettingService.LOGS_RETENTION_DAYS, logsRetentionDays);
        if (publishDefaultSlots != null) {
            if (publishDefaultSlots.isBlank() || !publishDefaultSlots.strip().matches("\\d{1,2}:\\d{2}(\\s*,\\s*\\d{1,2}:\\d{2})*")) {
                ra.addFlashAttribute("error", "Franjas por defecto no válidas: usa horas HH:MM separadas por comas.");
                return "redirect:/admin/settings";
            }
            settingService.set(SettingService.PUBLISH_DEFAULT_SLOTS, publishDefaultSlots.strip());
        }
        saveIfPresent(SettingService.PUBLISH_CLIPS_PER_DAY, publishClipsPerDay);
        saveIfPresent(SettingService.PUBLISH_MAX_CLIPS_PER_DAY, publishMaxClipsPerDay);
        saveIfPresent(SettingService.PUBLISH_BACKLOG_DAYS, publishBacklogDays);
        saveIfPresent(SettingService.PUBLISH_PLAYLISTS, publishPlaylists);
        if (publishAudience != null) settingService.set(SettingService.PUBLISH_AUDIENCE, publishAudience.strip());
        saveIfPresent(SettingService.ALERT_QUEUE_THRESHOLD, alertQueueThreshold);
        saveIfPresent(SettingService.ALERT_EMAIL, alertEmail);
        saveIfPresent(SettingService.ALERT_REMINDER_HOURS, alertReminderHours);
        ra.addFlashAttribute("success", "Ajustes guardados.");
        return "redirect:/admin/settings";
    }

    /**
     * "Enviar email de prueba" (§12.3). Posted by the settings form itself, so it
     * tests the address typed in the field even before saving it.
     */
    @PostMapping("/test-email")
    public String testEmail(@RequestParam(required = false) String alertEmail, RedirectAttributes ra) {
        String to = (alertEmail == null || alertEmail.isBlank()) ? settingService.alertEmail() : alertEmail;
        try {
            alertService.sendTest(to);
            ra.addFlashAttribute("success", "Email de prueba enviado a " + to.strip() + ".");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/settings";
    }

    /** "Limpiar ahora" (§15): the hourly sweep, right away. */
    @PostMapping("/cleanup")
    public String cleanupNow(RedirectAttributes ra) {
        FileCleanupService.Report r = cleanup.sweep();
        ra.addFlashAttribute("success", r.isEmpty() ? "No había nada que borrar."
                : "Limpieza hecha: " + r.folders() + " carpeta(s), " + FileCleanupService.human(r.bytes())
                  + " liberados" + (r.logs() > 0 ? ", " + r.logs() + " log(s) antiguos." : "."));
        return "redirect:/admin/settings";
    }

    private void saveIfPresent(String key, String value) {
        if (value != null) settingService.set(key, value.trim());
    }
}
