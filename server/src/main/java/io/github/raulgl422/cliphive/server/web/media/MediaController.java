package io.github.raulgl422.cliphive.server.web.media;

import io.github.raulgl422.cliphive.server.model.Clip;
import io.github.raulgl422.cliphive.server.repository.ClipRepository;
import io.github.raulgl422.cliphive.server.service.JobFiles;
import io.github.raulgl422.cliphive.server.service.SignedUrlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Serves rendered clips to Instagram (and any other public HTTP client) via a
 * signed, short-lived token — docs/DESIGN.md §3.2, §13.
 *
 * <p>This endpoint lives on the media connector ({@code CLIPHIVE_MEDIA_PORT},
 * default 8081) which is only reachable from the Cloudflare Tunnel hostname
 * that has <em>no</em> Cloudflare Access gate (so Instagram can actually
 * fetch the file). The token itself acts as the sole auth mechanism:
 * 1-hour TTL, HMAC-SHA256-signed, tied to a specific clip id. Byte ranges are
 * supported, so a client can fetch a large clip in pieces.
 */
@RestController
@RequestMapping("/media")
public class MediaController {

    private static final Logger log = LoggerFactory.getLogger(MediaController.class);

    private final SignedUrlService signedUrlService;
    private final ClipRepository clipRepository;
    private final JobFiles files;

    public MediaController(SignedUrlService signedUrlService, ClipRepository clipRepository, JobFiles files) {
        this.signedUrlService = signedUrlService;
        this.clipRepository = clipRepository;
        this.files = files;
    }

    @GetMapping("/{token}")
    public ResponseEntity<Resource> serveClip(@PathVariable String token) {
        UUID clipId = signedUrlService.validateMediaToken(token);
        if (clipId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Clip clip = clipRepository.findById(clipId).orElse(null);
        if (clip == null) return ResponseEntity.notFound().build();
        Path file = files.renderedClip(clip.getVideoJob().getId(), clip.getClipIndex());
        if (!Files.isRegularFile(file)) {
            log.warn("Media request for clip {} but its file is missing: {}", clipId, file);
            return ResponseEntity.notFound().build();
        }
        log.debug("Serving clip {} on the media route", clipId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .body(new FileSystemResource(file));
    }
}
