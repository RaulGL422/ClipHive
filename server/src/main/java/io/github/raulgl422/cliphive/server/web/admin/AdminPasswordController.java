package io.github.raulgl422.cliphive.server.web.admin;

import io.github.raulgl422.cliphive.server.model.AdminUser;
import io.github.raulgl422.cliphive.server.repository.AdminUserRepository;
import io.github.raulgl422.cliphive.server.security.EncryptionService;
import io.github.raulgl422.cliphive.server.security.LoginAttemptService;
import io.github.raulgl422.cliphive.server.security.TotpService;
import io.github.raulgl422.cliphive.server.service.LogEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Changes the superadmin's password from Settings (DESIGN.md §12.1): the
 * current password, the new one twice and, with 2FA on, a code. Tries are
 * limited per account like the login, so a stolen session can't be used to
 * guess the current password.
 */
@Controller
@RequestMapping("/admin/settings/password")
public class AdminPasswordController {

    private static final Logger log = LoggerFactory.getLogger(AdminPasswordController.class);
    static final int MIN_LENGTH = 12;

    private final AdminUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TotpService totpService;
    private final EncryptionService encryptionService;
    private final LoginAttemptService attempts;
    private final LogEventService logEvents;

    public AdminPasswordController(AdminUserRepository users, PasswordEncoder passwordEncoder,
                                   TotpService totpService, EncryptionService encryptionService,
                                   LoginAttemptService attempts, LogEventService logEvents) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.totpService = totpService;
        this.encryptionService = encryptionService;
        this.attempts = attempts;
        this.logEvents = logEvents;
    }

    @PostMapping
    public String change(Authentication auth,
                         @RequestParam String currentPassword,
                         @RequestParam String newPassword,
                         @RequestParam String confirmPassword,
                         @RequestParam(required = false) String code,
                         RedirectAttributes ra) {
        AdminUser user = users.findByUsername(auth.getName()).orElse(null);
        if (user == null) {
            ra.addFlashAttribute("error", "No se encuentra la cuenta.");
            return "redirect:/admin/settings";
        }
        // Checked before anything that doesn't need a credential, so a blocked key learns nothing
        String key = "password-change:" + user.getUsername();
        if (!attempts.tryAcquire(key)) {
            ra.addFlashAttribute("error", "Demasiados intentos fallidos. Espera 15 minutos.");
            return "redirect:/admin/settings";
        }
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            ra.addFlashAttribute("error", "La contraseña actual no es correcta.");
            return "redirect:/admin/settings";
        }
        if (user.isTotpEnabled()) {
            String secret = encryptionService.decrypt(user.getTotpSecretEncrypted());
            if (code == null || !totpService.verify(secret, code.strip())) {
                ra.addFlashAttribute("error", "El código 2FA no es correcto.");
                return "redirect:/admin/settings";
            }
        }
        attempts.recordSuccess(key);

        String problem = problemWith(newPassword, confirmPassword, currentPassword);
        if (problem != null) {
            ra.addFlashAttribute("error", problem);
            return "redirect:/admin/settings";
        }
        user.changePassword(passwordEncoder.encode(newPassword));
        users.save(user);
        log.info("Superadmin password changed for {}", user.getUsername());
        logEvents.info("seguridad", "Contraseña del superadmin cambiada");
        ra.addFlashAttribute("success", "Contraseña cambiada.");
        return "redirect:/admin/settings";
    }

    /** Why {@code newPassword} can't be used, or null. */
    static String problemWith(String newPassword, String confirmPassword, String currentPassword) {
        if (newPassword == null || newPassword.length() < MIN_LENGTH) {
            return "La nueva contraseña debe tener al menos " + MIN_LENGTH + " caracteres.";
        }
        if (!newPassword.equals(confirmPassword)) return "Las dos contraseñas nuevas no coinciden.";
        if (newPassword.equals(currentPassword)) return "La nueva contraseña es igual a la actual.";
        return null;
    }
}
