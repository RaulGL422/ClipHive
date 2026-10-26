package io.github.raulgl422.cliphive.server.web.admin;

import io.github.raulgl422.cliphive.server.model.Clip;
import io.github.raulgl422.cliphive.server.repository.ClipRepository;
import io.github.raulgl422.cliphive.server.service.JobFiles;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Watch or download a rendered clip from the superadmin (DESIGN.md §12.2).
 * Behind the admin login like every /admin route; Spring serves byte ranges
 * for a Resource body, so the browser's player can seek.
 */
@Controller
@RequestMapping("/admin/jobs/{jobId}/clips")
public class AdminClipsController {

    private final ClipRepository clipRepository;
    private final JobFiles files;

    public AdminClipsController(ClipRepository clipRepository, JobFiles files) {
        this.clipRepository = clipRepository;
        this.files = files;
    }

    @GetMapping("/{clipId}/video")
    public ResponseEntity<Resource> video(@PathVariable UUID jobId,
                                          @PathVariable UUID clipId,
                                          @RequestParam(defaultValue = "false") boolean download) {
        Clip clip = clipRepository.findById(clipId).orElse(null);
        if (clip == null || !clip.getVideoJob().getId().equals(jobId)) {
            return ResponseEntity.notFound().build();
        }
        Path file = files.renderedClip(jobId, clip.getClipIndex());
        if (!Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }

        String filename = "clip-" + (clip.getClipIndex() + 1) + "-" + jobId.toString().substring(0, 8) + ".mp4";
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(filename)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new FileSystemResource(file));
    }
}
