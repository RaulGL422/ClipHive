package io.github.raulgl422.cliphive.server.web.admin;

import io.github.raulgl422.cliphive.protocol.state.ScheduledPostState;
import io.github.raulgl422.cliphive.server.model.ScheduledPost;
import io.github.raulgl422.cliphive.server.publish.PublicationPlanner;
import io.github.raulgl422.cliphive.server.publish.PublicationService;
import io.github.raulgl422.cliphive.server.publish.SocialAccountService;
import io.github.raulgl422.cliphive.server.repository.ScheduledPostRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Publications (DESIGN.md §12.2): what's queued and when, what went out
 * recently, and per-publication retry/cancel.
 */
@Controller
@RequestMapping("/admin/publications")
public class AdminPublicationsController {

    private static final EnumSet<ScheduledPostState> UPCOMING = EnumSet.of(
            ScheduledPostState.SCHEDULED, ScheduledPostState.UPLOADING, ScheduledPostState.PROCESSING);
    private static final EnumSet<ScheduledPostState> FINISHED = EnumSet.of(
            ScheduledPostState.PUBLISHED, ScheduledPostState.FAILED, ScheduledPostState.CANCELLED);

    private final ScheduledPostRepository posts;
    private final PublicationService publications;
    private final SocialAccountService accounts;
    private final PublicationPlanner planner;

    public AdminPublicationsController(ScheduledPostRepository posts,
                                       PublicationService publications,
                                       SocialAccountService accounts,
                                       PublicationPlanner planner) {
        this.posts = posts;
        this.publications = publications;
        this.accounts = accounts;
        this.planner = planner;
    }

    @GetMapping
    public String list(Model model) {
        List<ScheduledPost> upcoming = posts.findWithClipByStateIn(UPCOMING);
        List<ScheduledPost> recent = posts.findRecentWithClipByStateIn(FINISHED, PageRequest.of(0, 50));
        List<ScheduledPost> awaiting = posts.findAwaitingConfirmation();
        model.addAttribute("upcoming", upcoming);
        model.addAttribute("recent", recent);
        model.addAttribute("awaiting", awaiting);
        model.addAttribute("links", publications.publicUrls(upcoming, recent, awaiting));
        model.addAttribute("accounts", accounts.all());
        model.addAttribute("plannerActive", planner.isActive());
        model.addAttribute("plan", planner.today().orElse(null));
        return "admin/publications";
    }

    /** "Replanificar": plans the rest of today again with Claude (DESIGN.md §10.4). */
    @PostMapping("/plan")
    public String replan(RedirectAttributes ra) {
        if (!planner.isActive()) {
            ra.addFlashAttribute("error", "El planificador solo funciona con el modo de publicación \"Claude\" (Ajustes).");
            return "redirect:/admin/publications";
        }
        if (publications.connectedPlatforms().isEmpty()) {
            ra.addFlashAttribute("error", "No hay ninguna cuenta conectada: conecta alguna en Cuentas.");
            return "redirect:/admin/publications";
        }
        int n = planner.plan("replanificación manual");
        ra.addFlashAttribute("success", "Plan de hoy rehecho: " + n + " publicación(es) programadas.");
        return "redirect:/admin/publications";
    }

    @PostMapping("/{id}/now")
    public String publishNow(@PathVariable UUID id, RedirectAttributes ra) {
        if (publications.reschedule(id, Instant.now())) ra.addFlashAttribute("success", "Se publicará en menos de un minuto en todas sus plataformas.");
        else ra.addFlashAttribute("error", "Ya ha empezado a subirse: no se puede adelantar.");
        return "redirect:/admin/publications";
    }

    /** New time from a {@code datetime-local} field, in Madrid time. */
    @PostMapping("/{id}/reschedule")
    public String reschedule(@PathVariable UUID id, @RequestParam String at, RedirectAttributes ra) {
        Instant when;
        try {
            when = LocalDateTime.parse(at).atZone(MADRID).toInstant();
        } catch (DateTimeParseException e) {
            ra.addFlashAttribute("error", "Hora no válida.");
            return "redirect:/admin/publications";
        }
        if (when.isBefore(Instant.now())) {
            ra.addFlashAttribute("error", "La hora ya ha pasado; usa \"Publicar ahora\" para adelantarla.");
        } else if (publications.reschedule(id, when)) {
            ra.addFlashAttribute("success", "Hora cambiada en todas sus plataformas pendientes.");
        } else {
            ra.addFlashAttribute("error", "Ya ha empezado a subirse: no se puede cambiar la hora.");
        }
        return "redirect:/admin/publications";
    }

    @PostMapping("/{id}/retry")
    public String retry(@PathVariable UUID id, @RequestParam(required = false) String back, RedirectAttributes ra) {
        if (publications.retry(id)) ra.addFlashAttribute("success", "Publicación reintentada.");
        else ra.addFlashAttribute("error", "Solo se puede reintentar una publicación fallida.");
        return "redirect:" + safeBack(back);
    }

    /** "Ya lo subí a mano" (DESIGN.md §11.3), with the publication's link if you have it. */
    @PostMapping("/{id}/manual")
    public String uploadedByHand(@PathVariable UUID id, @RequestParam(required = false) String url,
                                 @RequestParam(required = false) String back, RedirectAttributes ra) {
        if (invalidLink(url)) {
            ra.addFlashAttribute("error", "El enlace debe empezar por http:// o https://.");
        } else if (publications.markUploadedByHand(id, url)) {
            ra.addFlashAttribute("success", "Marcada como subida a mano: ClipHive ya no la intentará subir.");
        } else {
            ra.addFlashAttribute("error", "Solo se puede marcar una publicación programada o fallida.");
        }
        return "redirect:" + safeBack(back);
    }

    /** "Ya está publicado" (DESIGN.md §11.3): a draft or private upload you finished in the app. */
    @PostMapping("/{id}/confirm")
    public String confirm(@PathVariable UUID id, @RequestParam(required = false) String url,
                          @RequestParam(required = false) String back, RedirectAttributes ra) {
        if (invalidLink(url)) {
            ra.addFlashAttribute("error", "El enlace debe empezar por http:// o https://.");
        } else if (publications.confirmPublished(id, url)) {
            ra.addFlashAttribute("success", "Confirmada como publicada.");
        } else {
            ra.addFlashAttribute("error", "Esa publicación no está pendiente de confirmar.");
        }
        return "redirect:" + safeBack(back);
    }

    /** "Reenviar borrador" (DESIGN.md §11.3): the TikTok draft got lost, send it again. */
    @PostMapping("/{id}/resend")
    public String resend(@PathVariable UUID id, @RequestParam(required = false) String back, RedirectAttributes ra) {
        if (publications.resendDraft(id)) ra.addFlashAttribute("success", "Borrador reenviado: llegará a la bandeja de TikTok en unos minutos.");
        else ra.addFlashAttribute("error", "No se puede reenviar: ya está confirmada o el fichero del clip ya se borró.");
        return "redirect:" + safeBack(back);
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable UUID id, @RequestParam(required = false) String back, RedirectAttributes ra) {
        if (publications.cancel(id)) ra.addFlashAttribute("success", "Publicación cancelada.");
        else ra.addFlashAttribute("error", "Ya ha empezado a subirse: no se puede cancelar.");
        return "redirect:" + safeBack(back);
    }

    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");

    /** A link was typed but isn't an http(s) URL (it's optional, so blank is fine). */
    private static boolean invalidLink(String url) {
        return url != null && !url.isBlank() && !url.strip().matches("(?i)https?://\\S+");
    }

    /** Only redirect back inside the superadmin. */
    private static String safeBack(String back) {
        return (back != null && back.startsWith("/admin/") && !back.startsWith("//")) ? back : "/admin/publications";
    }
}
