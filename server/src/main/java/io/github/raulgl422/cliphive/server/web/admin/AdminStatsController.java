package io.github.raulgl422.cliphive.server.web.admin;

import io.github.raulgl422.cliphive.server.model.enums.Platform;
import io.github.raulgl422.cliphive.server.publish.SocialAccountService;
import io.github.raulgl422.cliphive.server.publish.StatsDashboard;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Statistics page (DESIGN.md §12.4), one tab per connected platform. */
@Controller
@RequestMapping("/admin/stats")
public class AdminStatsController {

    /** Platforms with statistics, in tab order. */
    private static final List<Platform> PLATFORMS = List.of(Platform.YOUTUBE, Platform.INSTAGRAM, Platform.TIKTOK);

    private final StatsDashboard dashboard;
    private final SocialAccountService accounts;
    private final ObjectMapper objectMapper;

    public AdminStatsController(StatsDashboard dashboard, SocialAccountService accounts, ObjectMapper objectMapper) {
        this.dashboard = dashboard;
        this.accounts = accounts;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public String stats(@RequestParam(defaultValue = "views") String sort,
                        @RequestParam(required = false) String platform,
                        Model model) {
        if (!StatsDashboard.SORTS.containsKey(sort)) sort = "views";
        List<Platform> connected = PLATFORMS.stream().filter(p -> accounts.accountFor(p).isPresent()).toList();
        Platform selected = PLATFORMS.stream().filter(p -> p.name().equalsIgnoreCase(platform)).findFirst()
                .orElse(connected.isEmpty() ? Platform.YOUTUBE : connected.getFirst());

        StatsDashboard.Dashboard d = dashboard.build(selected, sort);
        model.addAttribute("d", d);
        model.addAttribute("sort", sort);
        model.addAttribute("platform", selected);
        model.addAttribute("platformLabel", selected.label());
        model.addAttribute("tabs", connected.stream().map(p -> new String[]{p.name().toLowerCase(), p.label()}).toList());

        // Only what the charts draw; the table below is rendered by the template.
        Map<String, Object> charts = new LinkedHashMap<>();
        charts.put("subscribers", d.subscribers());
        charts.put("audience", selected == Platform.YOUTUBE ? "suscriptores" : "seguidores");
        charts.put("top", d.top().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", r.title());
            m.put("value", r.views());
            m.put("url", r.url());
            return m;
        }).toList());
        charts.put("byHour", d.byHour());
        model.addAttribute("chartJson", objectMapper.writeValueAsString(charts));
        model.addAttribute("sorts", List.of(
                new String[]{"views", "Vistas"}, new String[]{"likes", "Likes"},
                new String[]{"comments", "Comentarios"}, new String[]{"retention", "% visto"},
                new String[]{"subscribers", "Subs ganados"}, new String[]{"published", "Publicado"}));
        return "admin/stats";
    }
}
