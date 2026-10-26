package io.github.raulgl422.cliphive.server.web.admin;

import io.github.raulgl422.cliphive.server.model.AdminUser;
import io.github.raulgl422.cliphive.server.repository.AdminUserRepository;
import io.github.raulgl422.cliphive.server.security.AdminSecurityConfig;
import io.github.raulgl422.cliphive.server.security.EncryptionService;
import io.github.raulgl422.cliphive.server.security.LoginAttemptService;
import io.github.raulgl422.cliphive.server.security.TotpService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * TOTP second-factor verification and 2FA setup/disable UI
 * — docs/DESIGN.md §12.1.
 */
@Controller
@RequestMapping("/admin/totp")
public class TotpController {

    private final AdminUserRepository adminUserRepository;
    private final TotpService totpService;
    private final EncryptionService encryptionService;
    private final AdminSecurityConfig securityConfig;
    private final LoginAttemptService attempts;

    public TotpController(AdminUserRepository adminUserRepository,
                          TotpService totpService,
                          EncryptionService encryptionService,
                          AdminSecurityConfig securityConfig,
                          LoginAttemptService attempts) {
        this.adminUserRepository = adminUserRepository;
        this.totpService = totpService;
        this.encryptionService = encryptionService;
        this.securityConfig = securityConfig;
        this.attempts = attempts;
    }

    // ── Step-2 verification (after username/password login) ───────────────────

    @GetMapping
    public String totpForm(Authentication auth) {
        if (isFullAdmin(auth)) return "redirect:/admin";
        return "admin/totp-verify";
    }

    @PostMapping
    public String verifyTotp(@RequestParam String code,
                             Authentication auth,
                             HttpServletRequest request,
                             HttpServletResponse response,
                             RedirectAttributes ra) {
        String username = auth.getName();
        AdminUser user = adminUserRepository.findByUsername(username).orElse(null);
        if (user == null || !user.isTotpEnabled()) {
            securityConfig.upgradeToAdmin(request, response, auth);
            return "redirect:/admin";
        }
        // Keyed by user, not IP: whoever got this far has the password, and
        // changing IP must not buy them more guesses at the code.
        String key = "totp:" + username;
        if (!attempts.tryAcquire(key)) {
            ra.addFlashAttribute("error", "Demasiados códigos incorrectos. Espera 15 minutos.");
            return "redirect:/admin/totp";
        }
        String secret = encryptionService.decrypt(user.getTotpSecretEncrypted());
        if (!totpService.verify(secret, code)) {
            ra.addFlashAttribute("error", "Código incorrecto. Inténtalo de nuevo.");
            return "redirect:/admin/totp";
        }
        attempts.recordSuccess(key);
        securityConfig.upgradeToAdmin(request, response, auth);
        return "redirect:/admin";
    }

    // ── 2FA setup and disable (ROLE_ADMIN only, see AdminSecurityConfig) ──────

    @GetMapping("/setup")
    public String setupForm(Authentication auth, Model model) {
        String username = auth.getName();
        AdminUser user = adminUserRepository.findByUsername(username).orElseThrow();
        model.addAttribute("totpEnabled", user.isTotpEnabled());
        if (!encryptionService.isConfigured()) {
            model.addAttribute("error",
                    "CLIPHIVE_ENCRYPTION_KEY no está configurado — no se puede activar 2FA.");
            return "admin/totp-setup";
        }
        if (user.isTotpEnabled()) return "admin/totp-setup";   // shows the disable form
        String secret = totpService.generateSecret();
        model.addAttribute("secret", secret);
        model.addAttribute("totpUri", totpService.buildTotpUri(secret, username, "ClipHive"));
        return "admin/totp-setup";
    }

    @PostMapping("/setup")
    public String confirmSetup(@RequestParam String secret,
                               @RequestParam String code,
                               Authentication auth,
                               RedirectAttributes ra) {
        if (!totpService.verify(secret, code)) {
            ra.addFlashAttribute("error", "Código incorrecto. Vuelve a escanear el QR.");
            return "redirect:/admin/totp/setup";
        }
        String username = auth.getName();
        AdminUser user = adminUserRepository.findByUsername(username).orElseThrow();
        if (user.isTotpEnabled()) {
            // Replacing the secret would bypass the current one: disable first.
            ra.addFlashAttribute("error", "El 2FA ya está activo: desactívalo antes de configurarlo de nuevo.");
            return "redirect:/admin/settings";
        }
        user.enableTotp(encryptionService.encrypt(secret));
        adminUserRepository.save(user);
        ra.addFlashAttribute("success", "2FA activado correctamente.");
        return "redirect:/admin/settings";
    }

    @PostMapping("/disable")
    public String disableTotp(@RequestParam String code,
                              Authentication auth,
                              RedirectAttributes ra) {
        String username = auth.getName();
        AdminUser user = adminUserRepository.findByUsername(username).orElseThrow();
        if (!user.isTotpEnabled()) {
            ra.addFlashAttribute("info", "El 2FA no estaba activado.");
            return "redirect:/admin/settings";
        }
        String secret = encryptionService.decrypt(user.getTotpSecretEncrypted());
        if (!totpService.verify(secret, code)) {
            ra.addFlashAttribute("error", "Código incorrecto — el 2FA sigue activo.");
            return "redirect:/admin/settings";
        }
        user.disableTotp();
        adminUserRepository.save(user);
        ra.addFlashAttribute("success", "2FA desactivado.");
        return "redirect:/admin/settings";
    }

    private static boolean isFullAdmin(Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
}
