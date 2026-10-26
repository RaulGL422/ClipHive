package io.github.raulgl422.cliphive.server.web.admin;

import io.github.raulgl422.cliphive.server.service.JobFiles;
import io.github.raulgl422.cliphive.server.service.ManualSelectionService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Manual clip mode (DESIGN.md §4.1): the source video for the player, and
 * marking, removing and confirming clip ranges.
 */
@Controller
@RequestMapping("/admin/jobs/{jobId}")
public class AdminSelectionController {

    private final ManualSelectionService selection;
    private final JobFiles files;

    public AdminSelectionController(ManualSelectionService selection, JobFiles files) {
        this.selection = selection;
        this.files = files;
    }

    /** The downloaded source; byte ranges let the player seek through a long video. */
    @GetMapping("/source")
    public ResponseEntity<Resource> source(@PathVariable UUID jobId) throws IOException {
        Path file = files.sourceVideo(jobId);
        if (file == null) return ResponseEntity.notFound().build();
        String name = file.getFileName().toString().toLowerCase();
        MediaType type = name.endsWith(".webm") ? MediaType.parseMediaType("video/webm")
                : MediaType.parseMediaType("video/mp4");
        return ResponseEntity.ok().contentType(type).body(new FileSystemResource(file));
    }

    /** Seconds come from the form ("12.5"); parsed with '.' whatever the server locale. */
    @PostMapping("/ranges")
    public String add(@PathVariable UUID jobId,
                      @RequestParam String startS,
                      @RequestParam String endS,
                      @RequestParam(required = false) String resumeAt,
                      RedirectAttributes ra) {
        try {
            long start = Math.round(parseSeconds(startS) * 1000);
            long end = Math.round(parseSeconds(endS) * 1000);
            selection.addRange(jobId, start, end);
            ra.addFlashAttribute("success", "Clip añadido.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return back(jobId, resumeAt);
    }

    @PostMapping("/ranges/{index}/delete")
    public String remove(@PathVariable UUID jobId, @PathVariable int index, RedirectAttributes ra) {
        try {
            selection.removeRange(jobId, index);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return back(jobId, null);
    }

    @PostMapping("/ranges/confirm")
    public String confirm(@PathVariable UUID jobId, RedirectAttributes ra) {
        try {
            selection.confirm(jobId);
            ra.addFlashAttribute("success", "Cortes confirmados.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return back(jobId, null);
    }

    static double parseSeconds(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Falta el inicio o el fin.");
        try {
            // Accept "1:23.4" as well as "83.4"; a decimal comma is fine too
            String v = value.strip().replace(',', '.');
            if (v.contains(":")) {
                String[] parts = v.split(":");
                double total = 0;
                for (String p : parts) total = total * 60 + Double.parseDouble(p);
                return total;
            }
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Tiempo no válido: " + value);
        }
    }

    private static String back(UUID jobId, String resumeAt) {
        String t = "";
        if (resumeAt != null && resumeAt.matches("\\d+(\\.\\d+)?")) t = "?t=" + resumeAt;
        return "redirect:/admin/jobs/" + jobId + t + "#seleccion";
    }
}
