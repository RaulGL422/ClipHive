package io.github.raulgl422.cliphive.server.web.admin;

import io.github.raulgl422.cliphive.protocol.state.ScheduledPostState;
import io.github.raulgl422.cliphive.protocol.state.TaskState;
import io.github.raulgl422.cliphive.protocol.state.VideoJobState;
import io.github.raulgl422.cliphive.server.model.ScheduledPost;
import io.github.raulgl422.cliphive.server.model.Task;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.model.enums.ClipMode;
import io.github.raulgl422.cliphive.server.model.enums.PublishMode;
import io.github.raulgl422.cliphive.server.publish.PublicationService;
import io.github.raulgl422.cliphive.server.repository.ClipRepository;
import io.github.raulgl422.cliphive.server.repository.ContentAnalysisRepository;
import io.github.raulgl422.cliphive.server.repository.ScheduledPostRepository;
import io.github.raulgl422.cliphive.server.repository.TaskRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import io.github.raulgl422.cliphive.server.repository.WorkerRepository;
import io.github.raulgl422.cliphive.server.service.ClipMetadataService;
import io.github.raulgl422.cliphive.server.service.JobControlService;
import io.github.raulgl422.cliphive.server.service.JobFiles;
import io.github.raulgl422.cliphive.server.service.ManualSelectionService;
import io.github.raulgl422.cliphive.server.service.SettingService;
import io.github.raulgl422.cliphive.server.service.VideoJobService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Job submission, list and detail pages — docs/DESIGN.md §12.2.
 */
@Controller
@RequestMapping("/admin/jobs")
public class AdminJobsController {

    private final VideoJobService jobService;
    private final VideoJobRepository jobRepository;
    private final ClipRepository clipRepository;
    private final TaskRepository taskRepository;
    private final ContentAnalysisRepository analysisRepository;
    private final SettingService settingService;
    private final WorkerRepository workerRepository;
    private final JobControlService jobControl;
    private final ScheduledPostRepository postRepository;
    private final JobFiles jobFiles;
    private final PublicationService publications;
    private final ClipMetadataService clipMetadataService;

    private static final Logger log = LoggerFactory.getLogger(AdminJobsController.class);

    public AdminJobsController(VideoJobService jobService,
                               VideoJobRepository jobRepository,
                               ClipRepository clipRepository,
                               TaskRepository taskRepository,
                               ContentAnalysisRepository analysisRepository,
                               SettingService settingService,
                               WorkerRepository workerRepository,
                               JobControlService jobControl,
                               ScheduledPostRepository postRepository,
                               PublicationService publications,
                               JobFiles jobFiles,
                               ClipMetadataService clipMetadataService) {
        this.jobService = jobService;
        this.jobRepository = jobRepository;
        this.clipRepository = clipRepository;
        this.taskRepository = taskRepository;
        this.analysisRepository = analysisRepository;
        this.settingService = settingService;
        this.workerRepository = workerRepository;
        this.jobControl = jobControl;
        this.postRepository = postRepository;
        this.jobFiles = jobFiles;
        this.publications = publications;
        this.clipMetadataService = clipMetadataService;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int page, Model model) {
        Page<VideoJob> jobs = jobRepository.findAll(
                PageRequest.of(page, 25, Sort.by("createdAt").descending()));
        model.addAttribute("jobs", jobs);
        model.addAttribute("defaultLanguage", settingService.defaultLanguage());
        model.addAttribute("defaultFpsMode", settingService.defaultFpsMode());
        model.addAttribute("defaultReviewMode", settingService.defaultReviewMode());
        return "admin/jobs";
    }

    @PostMapping
    public String submit(@Valid SubmitForm form, BindingResult binding,
                         RedirectAttributes ra) {
        if (binding.hasErrors()) {
            ra.addFlashAttribute("error", "La URL no puede estar vacía.");
            return "redirect:/admin/jobs";
        }
        String language = (form.language() == null || form.language().isBlank())
                ? settingService.defaultLanguage() : form.language();
        String fpsMode = (form.fpsMode() == null || form.fpsMode().isBlank())
                ? settingService.defaultFpsMode() : form.fpsMode();
        // The jobs.html form sends a "_reviewMode" field marker, so an unticked
        // box binds as false; null only means a client that didn't send the field.
        boolean reviewMode = form.reviewMode() != null
                ? form.reviewMode() : settingService.defaultReviewMode();
        ClipMode clipMode = "MANUAL".equalsIgnoreCase(form.clipMode()) ? ClipMode.MANUAL : ClipMode.AUTO;
        String audioLanguage = (form.audioLanguage() == null || form.audioLanguage().isBlank())
                ? null : form.audioLanguage().strip();
        VideoJob job;
        try {
            job = jobService.submit(form.url(), language, fpsMode, reviewMode, clipMode, audioLanguage);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/jobs";
        }
        ra.addFlashAttribute("success", "Trabajo enviado: " + job.getId());
        return "redirect:/admin/jobs/" + job.getId();
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable UUID id, Model model) {
        VideoJob job = jobRepository.findById(id).orElse(null);
        if (job == null) return "redirect:/admin/jobs";
        model.addAttribute("job", job);
        model.addAttribute("clips", clipRepository.findByVideoJobIdOrderByClipIndex(id));
        // All tasks: TRANSCRIBE chunks hang off the job, RENDER_CLIP off each clip.
        List<Task> tasks = taskRepository.findAllForJob(id);
        model.addAttribute("tasks", tasks);
        // Worker name per task. Only the worker's id is read from the lazy
        // association (no session here — open-in-view is off), names come
        // from one query.
        Map<UUID, String> workerNames = new HashMap<>();
        workerRepository.findAll().forEach(w -> workerNames.put(w.getId(), w.getName()));
        Map<UUID, String> taskWorker = new HashMap<>();
        for (Task t : tasks) {
            if (t.getAssignedWorker() != null) {
                taskWorker.put(t.getId(), workerNames.getOrDefault(t.getAssignedWorker().getId(), "?"));
            }
        }
        model.addAttribute("taskWorker", taskWorker);
        model.addAttribute("failedTasks", tasks.stream().filter(t -> t.getState() == TaskState.FAILED).count());

        List<ScheduledPost> posts = postRepository.findWithClipByJobId(id);
        Map<UUID, String> postLinks = publications.publicUrls(posts);
        model.addAttribute("posts", posts);
        model.addAttribute("publishWait", publishWait(job));

        // Manual clip mode (DESIGN.md §4.1)
        if (job.isManual()) {
            boolean sourceReady;
            try {
                sourceReady = jobFiles.sourceVideo(id) != null;
            } catch (IOException e) {
                sourceReady = false;
            }
            model.addAttribute("sourceReady", sourceReady);
            model.addAttribute("selectionEditable", ManualSelectionService.isEditable(job));
            model.addAttribute("clipMinS", settingService.clipMinMs() / 1000);
            model.addAttribute("clipMaxS", settingService.clipMaxMs() / 1000);
        }
        model.addAttribute("postLinks", postLinks);
        model.addAttribute("failedPosts", posts.stream().filter(p -> p.getState() == ScheduledPostState.FAILED).count());
        model.addAttribute("analysis", analysisRepository.findByVideoJobId(id).orElse(null));
        return "admin/job-detail";
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable UUID id, RedirectAttributes ra) {
        if (jobControl.cancel(id)) {
            ra.addFlashAttribute("success", "Trabajo cancelado.");
        } else {
            ra.addFlashAttribute("error", "El trabajo ya había terminado.");
        }
        return "redirect:/admin/jobs/" + id;
    }

    /** Deletes the job from the database and its files from disk (DESIGN.md §12.2). */
    @PostMapping("/{id}/delete")
    public String delete(@PathVariable UUID id, RedirectAttributes ra) {
        try {
            if (jobControl.delete(id)) {
                ra.addFlashAttribute("success", "Trabajo eliminado, con sus clips y ficheros.");
            } else {
                ra.addFlashAttribute("error", "Ese trabajo ya no existe.");
            }
            return "redirect:/admin/jobs";
        } catch (JobControlService.DeleteRefusedException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/jobs/" + id;
        }
    }

    @PostMapping("/{id}/retry")
    public String retryFailed(@PathVariable UUID id, RedirectAttributes ra) {
        int retried = jobControl.retryFailedTasks(id);
        if (retried > 0) {
            ra.addFlashAttribute("success", retried + " tarea(s) devueltas a la cola.");
        } else {
            ra.addFlashAttribute("error", "No hay tareas fallidas que reintentar.");
        }
        return "redirect:/admin/jobs/" + id;
    }

    @PostMapping("/{id}/retry-posts")
    public String retryPosts(@PathVariable UUID id, RedirectAttributes ra) {
        int retried = publications.retryFailedForJob(id);
        if (retried > 0) ra.addFlashAttribute("success", retried + " publicación(es) reintentadas.");
        else ra.addFlashAttribute("error", "No hay publicaciones fallidas.");
        return "redirect:/admin/jobs/" + id;
    }

    @PostMapping("/{id}/relaunch")
    public String relaunch(@PathVariable UUID id, RedirectAttributes ra) {
        VideoJob fresh = jobControl.relaunch(id);
        if (fresh == null) {
            ra.addFlashAttribute("error", "Solo se puede volver a lanzar un trabajo terminado.");
            return "redirect:/admin/jobs/" + id;
        }
        ra.addFlashAttribute("success", "Trabajo relanzado.");
        return "redirect:/admin/jobs/" + fresh.getId();
    }

    // ── Inner form record ─────────────────────────────────────────────────────

    record SubmitForm(@NotBlank String url, String language, String fpsMode, Boolean reviewMode,
                      String clipMode, String audioLanguage) {}

    /**
     * "Regenerar textos" (DESIGN.md §10.3): new titles and descriptions for the
     * clips that haven't started uploading. Claude takes a while per clip, so it
     * runs in the background and the page shows the result as it lands.
     */
    @PostMapping("/{id}/metadata")
    public String regenerateMetadata(@PathVariable UUID id, RedirectAttributes ra) {
        Thread.ofVirtual().name("metadata-" + id).start(() -> {
            int n = clipMetadataService.regenerate(id);
            log.info("Job {}: regenerated metadata for {} clip(s)", id, n);
        });
        ra.addFlashAttribute("success", "Regenerando títulos y descripciones con Claude: tardará un poco por clip.");
        return "redirect:/admin/jobs/" + id;
    }

    /** Why a RENDERED job hasn't started publishing (DESIGN.md §10.4), or null. */
    private String publishWait(VideoJob job) {
        if (job.getState() != VideoJobState.RENDERED) return null;
        if (publications.connectedPlatforms().isEmpty()) {
            return "Esperando a que conectes alguna cuenta en Cuentas para publicar.";
        }
        if (settingService.publishMode() == PublishMode.CLAUDE) {
            return "Esperando al plan de Claude: se hace a las 07:00 y se rehace cuando entran 3 clips nuevos. "
                    + "Puedes forzarlo con \"Replanificar\" en Publicaciones.";
        }
        return "Programando la publicación (menos de un minuto).";
    }
}
