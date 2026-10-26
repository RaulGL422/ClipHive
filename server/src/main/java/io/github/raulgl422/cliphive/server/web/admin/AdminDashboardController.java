package io.github.raulgl422.cliphive.server.web.admin;

import io.github.raulgl422.cliphive.server.repository.ClipRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import io.github.raulgl422.cliphive.server.service.LogEventService;
import io.github.raulgl422.cliphive.server.service.worker.WorkerSessionRegistry;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Main dashboard — docs/DESIGN.md §12.2. Shows a quick overview of jobs,
 * clips, connected workers and recent log entries.
 */
@Controller
public class AdminDashboardController {

    private final VideoJobRepository jobRepository;
    private final ClipRepository clipRepository;
    private final WorkerSessionRegistry workerRegistry;
    private final LogEventService logEventService;

    public AdminDashboardController(VideoJobRepository jobRepository,
                                    ClipRepository clipRepository,
                                    WorkerSessionRegistry workerRegistry,
                                    LogEventService logEventService) {
        this.jobRepository = jobRepository;
        this.clipRepository = clipRepository;
        this.workerRegistry = workerRegistry;
        this.logEventService = logEventService;
    }

    @GetMapping({"/", "/admin"})
    public String dashboard(Model model) {
        model.addAttribute("totalJobs", jobRepository.count());
        model.addAttribute("totalClips", clipRepository.count());
        model.addAttribute("connectedWorkers", workerRegistry.all().size());
        model.addAttribute("recentJobs", jobRepository.findAll(
                PageRequest.of(0, 10, Sort.by("createdAt").descending())).getContent());
        model.addAttribute("recentLogs", logEventService.recent(20));
        return "admin/dashboard";
    }
}
