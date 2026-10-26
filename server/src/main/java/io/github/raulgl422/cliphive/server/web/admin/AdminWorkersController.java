package io.github.raulgl422.cliphive.server.web.admin;

import io.github.raulgl422.cliphive.protocol.state.TaskState;
import io.github.raulgl422.cliphive.server.model.Task;
import io.github.raulgl422.cliphive.server.repository.TaskRepository;
import io.github.raulgl422.cliphive.server.service.WorkerAdminService;
import io.github.raulgl422.cliphive.server.service.worker.ConnectedWorker;
import io.github.raulgl422.cliphive.server.service.worker.WorkerSessionRegistry;
import jakarta.validation.constraints.NotBlank;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Worker registration, revocation and live status — docs/DESIGN.md §12.2.
 */
@Controller
@RequestMapping("/admin/workers")
public class AdminWorkersController {

    private static final List<TaskState> ACTIVE_STATES =
            List.of(TaskState.ASSIGNED, TaskState.RUNNING);

    /** A running task with no PROGRESS for this long is shown as possibly stuck. */
    static final long STUCK_AFTER_SECONDS = 120;

    private final WorkerAdminService workerAdminService;
    private final WorkerSessionRegistry workerRegistry;
    private final TaskRepository taskRepository;

    public AdminWorkersController(WorkerAdminService workerAdminService,
                                  WorkerSessionRegistry workerRegistry,
                                  TaskRepository taskRepository) {
        this.workerAdminService = workerAdminService;
        this.workerRegistry = workerRegistry;
        this.taskRepository = taskRepository;
    }

    @GetMapping
    public String list(Model model) {
        List<ConnectedWorker> connected = List.copyOf(workerRegistry.all());

        // Active task (ASSIGNED or RUNNING) per connected worker. Idle workers
        // are simply absent — Collectors.toMap rejects null values, which made
        // the page 500 as soon as an idle worker was connected.
        Map<UUID, Task> activeTasks = new HashMap<>();
        for (ConnectedWorker cw : connected) {
            taskRepository.findByAssignedWorkerIdAndStateIn(cw.workerId(), ACTIVE_STATES)
                    .stream().findFirst()
                    .ifPresent(task -> activeTasks.put(cw.workerId(), task));
        }

        model.addAttribute("workers", workerAdminService.listAll());
        model.addAttribute("connected", connected);
        model.addAttribute("activeTasks", activeTasks);

        // Seconds since each active task last reported progress, so the page
        // can flag a task that's stuck (no PROGRESS for a while).
        Instant now = Instant.now();
        Map<UUID, Long> progressAge = new HashMap<>();
        activeTasks.forEach((workerId, task) -> {
            Instant last = task.getLastProgressAt() != null ? task.getLastProgressAt() : task.getUpdatedAt();
            if (last != null) progressAge.put(workerId, Duration.between(last, now).toSeconds());
        });
        model.addAttribute("progressAge", progressAge);
        model.addAttribute("stuckAfterSeconds", STUCK_AFTER_SECONDS);
        return "admin/workers";
    }

    @PostMapping
    public String register(@RequestParam @NotBlank String name, RedirectAttributes ra) {
        var result = workerAdminService.register(name);
        ra.addFlashAttribute("newToken", result.token());
        ra.addFlashAttribute("newWorkerId", result.id());
        ra.addFlashAttribute("newWorkerName", result.name());
        ra.addFlashAttribute("success", "Worker \"" + name + "\" registrado. "
                + "Copia el token — no se mostrará de nuevo.");
        return "redirect:/admin/workers";
    }

    @PostMapping("/{id}/revoke")
    public String revoke(@PathVariable UUID id, RedirectAttributes ra) {
        workerAdminService.revoke(id);
        ra.addFlashAttribute("success", "Worker revocado. La sesión activa, si la había, ha sido cerrada.");
        return "redirect:/admin/workers";
    }
}
