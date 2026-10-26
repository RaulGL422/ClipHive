package io.github.raulgl422.cliphive.server.web.admin;

import io.github.raulgl422.cliphive.server.model.SocialAccount;
import io.github.raulgl422.cliphive.server.model.enums.Platform;
import io.github.raulgl422.cliphive.server.publish.PublishException;
import io.github.raulgl422.cliphive.server.publish.SocialAccountService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Connected accounts (DESIGN.md §12.2): connect a platform through OAuth,
 * see which account is connected, disconnect it.
 */
@Controller
@RequestMapping("/admin/accounts")
public class AdminAccountsController {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountsController.class);
    private static final String STATE_ATTR = "oauth-state-";

    private final SocialAccountService accounts;

    public AdminAccountsController(SocialAccountService accounts) {
        this.accounts = accounts;
    }

    /** One row of the accounts page. */
    public record PlatformRow(Platform platform, String label, boolean supported, boolean configured,
                              String redirectUri, SocialAccount account) {}

    @GetMapping
    public String list(Model model) {
        List<PlatformRow> rows = new ArrayList<>();
        for (Platform p : Platform.values()) {
            boolean supported = accounts.isSupported(p);
            String redirect = null;
            try {
                redirect = accounts.redirectUri(p);
            } catch (IllegalStateException ignored) {
                // CLIPHIVE_ADMIN_PUBLIC_URL not set — shown as a hint on the page
            }
            Optional<SocialAccount> account = accounts.accountFor(p);
            rows.add(new PlatformRow(p, p.label(), supported, accounts.isConfigured(p), redirect, account.orElse(null)));
        }
        model.addAttribute("rows", rows);
        return "admin/accounts";
    }

    /** Starts OAuth: remembers a random state in the session and sends the browser to the platform. */
    @PostMapping("/{platform}/connect")
    public String connect(@PathVariable String platform, HttpSession session, RedirectAttributes ra) {
        Platform p = parse(platform);
        if (p == null || !accounts.isConfigured(p)) {
            ra.addFlashAttribute("error", "Esa plataforma no está configurada en el servidor.");
            return "redirect:/admin/accounts";
        }
        String state = UUID.randomUUID().toString();
        session.setAttribute(STATE_ATTR + p, state);
        return "redirect:" + accounts.authorizationUrl(p, state);
    }

    /** The platform sends the browser back here with a code (or an error). */
    @GetMapping("/{platform}/callback")
    public String callback(@PathVariable String platform,
                           @RequestParam(required = false) String code,
                           @RequestParam(required = false) String state,
                           @RequestParam(required = false) String error,
                           HttpSession session, RedirectAttributes ra) {
        Platform p = parse(platform);
        Object expected = p == null ? null : session.getAttribute(STATE_ATTR + p);
        if (p != null) session.removeAttribute(STATE_ATTR + p);

        if (error != null) {
            ra.addFlashAttribute("error", "Conexión cancelada: " + error);
            return "redirect:/admin/accounts";
        }
        // The state must match the one this session started — guards against forged callbacks
        if (p == null || code == null || state == null || !state.equals(expected)) {
            ra.addFlashAttribute("error", "Respuesta de OAuth no válida. Vuelve a intentarlo.");
            return "redirect:/admin/accounts";
        }
        try {
            SocialAccount account = accounts.connect(p, code);
            ra.addFlashAttribute("success", p.label() + " conectado: " + account.getDisplayName());
        } catch (PublishException e) {
            log.warn("{} OAuth callback failed: {}", p, e.getMessage());
            ra.addFlashAttribute("error", "No se pudo conectar " + p.label() + ": " + e.getMessage());
        }
        return "redirect:/admin/accounts";
    }

    @PostMapping("/{id}/disconnect")
    public String disconnect(@PathVariable UUID id, RedirectAttributes ra) {
        accounts.disconnect(id);
        ra.addFlashAttribute("success", "Cuenta desconectada.");
        return "redirect:/admin/accounts";
    }

    private static Platform parse(String path) {
        try {
            return Platform.valueOf(path.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
