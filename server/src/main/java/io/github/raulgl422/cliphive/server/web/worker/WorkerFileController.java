package io.github.raulgl422.cliphive.server.web.worker;

import io.github.raulgl422.cliphive.protocol.state.TaskState;
import io.github.raulgl422.cliphive.server.model.Task;
import io.github.raulgl422.cliphive.server.repository.TaskRepository;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * File transfer endpoints for workers (docs/DESIGN.md §7).
 *
 * <p>Workers fetch their input ({@code GET /tasks/{taskId}/{attempt}/audio}
 * or {@code /source}) and upload their result ({@code PUT .../transcript} or
 * {@code .../clip}). Every path is protected by an HMAC-SHA256 signed token
 * tied to {@code (taskId, attempt, path)}, and the attempt must still be the
 * task's live one (DESIGN.md §6.3): a worker presumed dead, whose task was
 * reassigned or cancelled since, gets 409 and can't read or overwrite files.
 *
 * <p>Served exclusively on the worker connector (Tailscale interface, port
 * 8082). {@link io.github.raulgl422.cliphive.server.config.PortRestrictionFilter}
 * blocks {@code /tasks/**} on any other connector as a second defence layer.
 */
@RestController
@RequestMapping("/tasks")
public class WorkerFileController {

    private static final Logger log = LoggerFactory.getLogger(WorkerFileController.class);

    private final TaskRepository taskRepository;
    private final SignedUrlService signedUrlService;
    private final JobFiles files;

    public WorkerFileController(TaskRepository taskRepository,
                                SignedUrlService signedUrlService,
                                JobFiles files) {
        this.taskRepository = taskRepository;
        this.signedUrlService = signedUrlService;
        this.files = files;
    }

    /**
     * Worker downloads the audio chunk for a TRANSCRIBE task.
     * Returns 401 for an invalid/expired token; 404 if the chunk file is missing.
     */
    @GetMapping("/{taskId}/{attempt}/audio")
    public ResponseEntity<Resource> downloadAudio(
            @PathVariable("taskId") UUID taskId,
            @PathVariable("attempt") int attempt,
            @RequestParam("token") String token) {

        String path = "/tasks/" + taskId + "/" + attempt + "/audio";
        if (!signedUrlService.validateWorkerToken(token, taskId, attempt, path)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Task task = taskRepository.findById(taskId).orElse(null);
        if (task == null) return ResponseEntity.notFound().build();
        if (!isLiveAttempt(task, attempt)) return ResponseEntity.status(HttpStatus.CONFLICT).build();

        Object rawPath = task.getParametersJson().get("chunkPath");
        if (rawPath == null) return ResponseEntity.notFound().build();

        Path chunkFile = files.fromStored(rawPath.toString());
        if (!Files.exists(chunkFile)) {
            log.warn("Chunk file missing for task {}: {}", taskId, chunkFile);
            return ResponseEntity.notFound().build();
        }

        log.debug("Worker downloading chunk for task {}/{}", taskId, attempt);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/wav"))
                .body(new FileSystemResource(chunkFile));
    }

    /**
     * Worker uploads the whisper.cpp JSON transcript for a TRANSCRIBE task.
     * Returns 401 for invalid token; 204 on success.
     * The file is stored as {@code jobs/<jobId>/chunks/chunk_NNN.json}.
     */
    @PutMapping("/{taskId}/{attempt}/transcript")
    public ResponseEntity<Void> uploadTranscript(
            @PathVariable("taskId") UUID taskId,
            @PathVariable("attempt") int attempt,
            @RequestParam("token") String token,
            @RequestBody byte[] body) throws IOException {

        String path = "/tasks/" + taskId + "/" + attempt + "/transcript";
        if (!signedUrlService.validateWorkerToken(token, taskId, attempt, path)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Task task = taskRepository.findById(taskId).orElse(null);
        if (task == null) return ResponseEntity.notFound().build();
        if (!isLiveAttempt(task, attempt)) return ResponseEntity.status(HttpStatus.CONFLICT).build();

        Map<String, Object> params = task.getParametersJson();
        Object rawPath  = params.get("chunkPath");
        Object rawIndex = params.get("chunkIndex");
        if (rawPath == null) return ResponseEntity.notFound().build();

        // Store transcript next to the chunk: chunk_000.json
        Path chunkWav    = files.fromStored(rawPath.toString());
        String baseName  = chunkWav.getFileName().toString().replace(".wav", "");
        int index        = rawIndex != null ? ((Number) rawIndex).intValue() : 0;
        Path transcriptFile = chunkWav.getParent().resolve("chunk_%03d.json".formatted(index));

        Files.createDirectories(transcriptFile.getParent());
        Files.write(transcriptFile, body);
        log.info("Task {}/{}: transcript stored ({} bytes)", taskId, attempt, body.length);

        return ResponseEntity.noContent().build();
    }

    /**
     * Worker downloads the per-clip video source piece for a RENDER_CLIP task.
     * Returns 401 for an invalid/expired token; 404 if the file is missing.
     */
    @GetMapping("/{taskId}/{attempt}/source")
    public ResponseEntity<Resource> downloadSource(
            @PathVariable("taskId") UUID taskId,
            @PathVariable("attempt") int attempt,
            @RequestParam("token") String token) {

        String path = "/tasks/" + taskId + "/" + attempt + "/source";
        if (!signedUrlService.validateWorkerToken(token, taskId, attempt, path)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Task task = taskRepository.findById(taskId).orElse(null);
        if (task == null) return ResponseEntity.notFound().build();
        if (!isLiveAttempt(task, attempt)) return ResponseEntity.status(HttpStatus.CONFLICT).build();

        Object rawPath = task.getParametersJson().get("sourceClipPath");
        if (rawPath == null || rawPath.toString().isBlank()) {
            log.warn("Task {} has no source clip yet (sourceClipPath not set) — dispatched too early?", taskId);
            return ResponseEntity.notFound().build();
        }

        Path sourceFile = files.fromStored(rawPath.toString());
        if (!Files.exists(sourceFile)) {
            log.warn("Source clip missing for task {}: {}", taskId, sourceFile);
            return ResponseEntity.notFound().build();
        }

        log.debug("Worker downloading source clip for task {}/{}", taskId, attempt);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("video/mp4"))
                .body(new FileSystemResource(sourceFile));
    }

    /**
     * Worker uploads the rendered clip for a RENDER_CLIP task.
     * Returns 401 for invalid token; 204 on success.
     * The file is stored at {@code jobs/<jobId>/clips/<index>/clip.mp4}.
     */
    @PutMapping("/{taskId}/{attempt}/clip")
    public ResponseEntity<Void> uploadClip(
            @PathVariable("taskId") UUID taskId,
            @PathVariable("attempt") int attempt,
            @RequestParam("token") String token,
            @RequestBody byte[] body) throws IOException {

        String path = "/tasks/" + taskId + "/" + attempt + "/clip";
        if (!signedUrlService.validateWorkerToken(token, taskId, attempt, path)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Task task = taskRepository.findById(taskId).orElse(null);
        if (task == null) return ResponseEntity.notFound().build();
        if (!isLiveAttempt(task, attempt)) return ResponseEntity.status(HttpStatus.CONFLICT).build();

        Map<String, Object> params = task.getParametersJson();
        Object rawSourcePath = params.get("sourceClipPath");
        if (rawSourcePath == null) return ResponseEntity.notFound().build();

        // Store the rendered clip next to the source piece: source.mp4 → clip.mp4
        Path sourceFile = files.fromStored(rawSourcePath.toString());
        Path clipFile   = sourceFile.getParent().resolve("clip.mp4");

        Files.createDirectories(clipFile.getParent());
        Files.write(clipFile, body);
        log.info("Task {}/{}: rendered clip stored ({} bytes)", taskId, attempt, body.length);

        return ResponseEntity.noContent().build();
    }

    /**
     * {@code attempt} is the one the task is running now. Reassigning or
     * cancelling bumps the attempt, and a finished or failed task takes no
     * more files, so anything else comes from a superseded worker.
     */
    private static boolean isLiveAttempt(Task task, int attempt) {
        if (task.getAttempt() == attempt
                && (task.getState() == TaskState.ASSIGNED || task.getState() == TaskState.RUNNING)) {
            return true;
        }
        log.warn("Task {}: refused files for attempt {} (task is {} on attempt {})",
                task.getId(), attempt, task.getState(), task.getAttempt());
        return false;
    }
}
