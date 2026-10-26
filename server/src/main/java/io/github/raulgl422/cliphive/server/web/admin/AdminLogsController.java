package io.github.raulgl422.cliphive.server.web.admin;

import io.github.raulgl422.cliphive.server.service.LogBroadcaster;
import io.github.raulgl422.cliphive.server.service.LogEventService;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live log stream and log history — docs/DESIGN.md §12.2 ("Logs … en directo por SSE").
 */
@Controller
@RequestMapping("/admin/logs")
public class AdminLogsController {

    private final LogEventService logEventService;
    private final LogBroadcaster broadcaster;

    public AdminLogsController(LogEventService logEventService, LogBroadcaster broadcaster) {
        this.logEventService = logEventService;
        this.broadcaster = broadcaster;
    }

    @GetMapping
    public String logsPage(@RequestParam(required = false) String level,
                           @RequestParam(required = false) String component,
                           @RequestParam(required = false) String jobId,
                           Model model) {
        boolean filtered = (level != null && !level.isBlank())
                || (component != null && !component.isBlank())
                || (jobId != null && !jobId.isBlank());
        model.addAttribute("logs", filtered
                ? logEventService.filtered(level, component, jobId, 500)
                : logEventService.recent(200));
        model.addAttribute("filterLevel",     level     != null ? level     : "");
        model.addAttribute("filterComponent", component != null ? component : "");
        model.addAttribute("filterJobId",     jobId     != null ? jobId     : "");
        return "admin/logs";
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return broadcaster.subscribe();
    }
}
