package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.server.model.Task;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.repository.TaskRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import io.github.raulgl422.cliphive.server.service.tool.AudioChunker;
import io.github.raulgl422.cliphive.server.service.tool.ChunkInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Creates TRANSCRIBE tasks when a job reaches the TRANSCRIBING state
 * (docs/DESIGN.md §4, step 5).
 *
 * <p>Called synchronously from {@link VideoJobDownloadService} (already on an
 * async thread) after the job transitions to TRANSCRIBING. On any failure
 * this method throws so the caller can transition the job to FAILED.
 */
@Service
public class TranscribeSetupService {

    private static final Logger log = LoggerFactory.getLogger(TranscribeSetupService.class);

    private final AudioChunker chunker;
    private final VideoJobRepository jobRepository;
    private final TaskRepository taskRepository;
    private final TransactionTemplate txTemplate;
    private final JobFiles files;

    public TranscribeSetupService(AudioChunker chunker,
                                  VideoJobRepository jobRepository,
                                  TaskRepository taskRepository,
                                  TransactionTemplate txTemplate,
                                  JobFiles files) {
        this.chunker = chunker;
        this.jobRepository = jobRepository;
        this.taskRepository = taskRepository;
        this.txTemplate = txTemplate;
        this.files = files;
    }

    /**
     * Splits the audio file into chunks, then creates one TRANSCRIBE task per chunk.
     *
     * @throws IOException          if audio chunking fails (ffprobe/ffmpeg error)
     * @throws InterruptedException if interrupted during process execution
     */
    public void setup(UUID jobId) throws IOException, InterruptedException {
        if (!jobRepository.existsById(jobId)) throw new IllegalStateException("Job not found: " + jobId);

        Path jobDir    = files.jobDir(jobId);
        Path audioFile = jobDir.resolve("audio").resolve("audio.wav");
        Path chunksDir = jobDir.resolve("chunks");

        log.debug("Creating transcription chunks for job {}", jobId);
        List<ChunkInfo> chunks = chunker.chunk(audioFile, chunksDir);
        log.info("Job {} split into {} chunk(s)", jobId, chunks.size());

        // Persist all TRANSCRIBE tasks in one transaction.
        txTemplate.executeWithoutResult(status -> {
            // Re-fetch inside the transaction so the managed entity is used for FK.
            VideoJob managed = jobRepository.findById(jobId)
                    .orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));

            for (ChunkInfo chunk : chunks) {
                Map<String, Object> params = Map.of(
                        "chunkIndex",          chunk.index(),
                        "totalChunks",         chunk.totalChunks(),
                        "chunkPath",           files.toStored(chunk.file()),
                        "chunkStartSeconds",   chunk.startSeconds(),
                        "chunkDurationSeconds",chunk.durationSeconds(),
                        "language",            managed.getLanguage() != null ? managed.getLanguage() : "es"
                );
                taskRepository.save(Task.forTranscribe(managed, params));
            }
        });

        log.info("Job {}: {} TRANSCRIBE task(s) queued", jobId, chunks.size());
    }
}
