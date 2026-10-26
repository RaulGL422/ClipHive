package io.github.raulgl422.cliphive.server.web.admin;

import io.github.raulgl422.cliphive.protocol.state.VideoJobState;
import io.github.raulgl422.cliphive.server.model.Clip;
import io.github.raulgl422.cliphive.server.model.ContentAnalysis;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.model.enums.ReviewStatus;
import io.github.raulgl422.cliphive.server.repository.ClipRepository;
import io.github.raulgl422.cliphive.server.repository.ContentAnalysisRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.UUID;

/**
 * Clip-review screen for jobs in AWAITING_REVIEW — docs/DESIGN.md §12.2
 * ("Revisión: ver los cortes propuestos por Claude sobre la transcripción,
 * aprobarlos o editarlos; editar títulos y descripciones").
 *
 * <p>Flow:
 * <ol>
 *   <li>GET /admin/jobs/{id}/review — renders the review page.
 *   <li>Admin edits titles/descriptions, clicks "Aprobar".
 *   <li>POST /admin/jobs/{id}/review/approve — saves edits,
 *       marks ContentAnalysis as APPROVED, returns job to ANALYZING.
 *   <li>RenderTriggerScheduler picks up the ANALYZING job and RenderSetupService
 *       continues from step 4 (video cutting), skipping already-done steps.
 * </ol>
 */
@Controller
@RequestMapping("/admin/jobs/{id}/review")
public class AdminClipReviewController {

    private final VideoJobRepository jobRepository;
    private final ClipRepository clipRepository;
    private final ContentAnalysisRepository analysisRepository;

    public AdminClipReviewController(VideoJobRepository jobRepository,
                                     ClipRepository clipRepository,
                                     ContentAnalysisRepository analysisRepository) {
        this.jobRepository = jobRepository;
        this.clipRepository = clipRepository;
        this.analysisRepository = analysisRepository;
    }

    @GetMapping
    public String review(@PathVariable UUID id, Model model, RedirectAttributes ra) {
        VideoJob job = jobRepository.findById(id).orElse(null);
        if (job == null) return "redirect:/admin/jobs";
        if (job.getState() != VideoJobState.AWAITING_REVIEW) {
            ra.addFlashAttribute("error", "El trabajo no está pendiente de revisión.");
            return "redirect:/admin/jobs/" + id;
        }
        ContentAnalysis analysis = analysisRepository.findByVideoJobId(id).orElse(null);
        List<Clip> clips = clipRepository.findByVideoJobIdOrderByClipIndex(id);

        model.addAttribute("job", job);
        model.addAttribute("analysis", analysis);
        model.addAttribute("clips", clips);
        return "admin/clip-review";
    }

    @PostMapping("/approve")
    @Transactional
    public String approve(@PathVariable UUID id,
                          @RequestParam(value = "clipId", required = false) List<String> clipIds,
                          @RequestParam(value = "title",  required = false) List<String> titles,
                          @RequestParam(value = "description", required = false) List<String> descriptions,
                          RedirectAttributes ra) {

        VideoJob job = jobRepository.findById(id).orElse(null);
        if (job == null) return "redirect:/admin/jobs";
        if (job.getState() != VideoJobState.AWAITING_REVIEW) {
            ra.addFlashAttribute("error", "El trabajo ya no está en revisión.");
            return "redirect:/admin/jobs/" + id;
        }

        // Save edited titles and descriptions
        if (clipIds != null) {
            for (int i = 0; i < clipIds.size(); i++) {
                UUID clipId = UUID.fromString(clipIds.get(i));
                String title       = (titles != null && i < titles.size()) ? titles.get(i) : null;
                String description = (descriptions != null && i < descriptions.size())
                        ? descriptions.get(i) : null;
                clipRepository.findById(clipId).ifPresent(clip -> {
                    if (title != null && !title.isBlank()) clip.setTitle(title.strip());
                    if (description != null && !description.isBlank()) clip.setDescription(description.strip());
                    clipRepository.save(clip);
                });
            }
        }

        // Mark analysis as approved
        analysisRepository.findByVideoJobId(id).ifPresent(ca -> {
            ca.setReviewStatus(ReviewStatus.APPROVED);
            analysisRepository.save(ca);
        });

        // Return job to ANALYZING → RenderTriggerScheduler continues the pipeline
        job.setState(VideoJobState.ANALYZING);
        jobRepository.save(job);

        ra.addFlashAttribute("success", "Revisión aprobada. El renderizado comenzará en breve.");
        return "redirect:/admin/jobs/" + id;
    }
}
