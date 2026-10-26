package io.github.raulgl422.cliphive.server.web.admin;

import io.github.raulgl422.cliphive.server.service.VideoJobService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admin REST endpoints for video job management (docs/DESIGN.md sections 4 and 12.2).
 * Served on the admin connector (localhost only, published via Cloudflare Tunnel + Access).
 */
@RestController
@RequestMapping("/admin/jobs")
public class JobController {

    private final VideoJobService jobService;

    public JobController(VideoJobService jobService) {
        this.jobService = jobService;
    }

    /**
     * Submits a new video link.
     * Returns immediately with the created job id and state (SUBMITTED).
     * Validation runs asynchronously; poll the job to follow the transition.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public VideoJobResponse submit(@RequestBody @Valid SubmitJobRequest request) {
        try {
            var job = jobService.submit(
                    request.url(), request.language(), request.fpsMode(), request.reviewMode());
            return new VideoJobResponse(job.getId(), job.getState());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
