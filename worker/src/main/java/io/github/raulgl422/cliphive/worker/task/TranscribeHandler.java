package io.github.raulgl422.cliphive.worker.task;

import io.github.raulgl422.cliphive.protocol.message.TaskAssign;
import io.github.raulgl422.cliphive.protocol.message.TaskDone;
import io.github.raulgl422.cliphive.protocol.message.TaskFailed;
import io.github.raulgl422.cliphive.worker.config.WorkerProperties;
import io.github.raulgl422.cliphive.worker.net.ServerConnection;
import io.github.raulgl422.cliphive.worker.tool.WhisperRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Handles one TRANSCRIBE task end-to-end (docs/DESIGN.md §9.1):
 * <ol>
 *   <li>Download audio chunk via signed URL.
 *   <li>Run whisper-cli, streaming PROGRESS messages.
 *   <li>Upload the resulting JSON transcript via signed URL.
 *   <li>Send TASK_DONE; on any failure send TASK_FAILED and clean up.
 * </ol>
 */
public class TranscribeHandler {

    private static final Logger log = LoggerFactory.getLogger(TranscribeHandler.class);

    private static final Pattern MODEL_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    private final WorkerProperties props;
    private final WhisperRunner whisperRunner;
    private final String defaultModel;

    /**
     * @param defaultModel the model resolved at startup and known to be downloaded:
     *                     the configured one, or the stand-in used when its
     *                     download failed. Used unless the task asks for another.
     */
    public TranscribeHandler(WorkerProperties props, WhisperRunner whisperRunner, String defaultModel) {
        this.props = props;
        this.whisperRunner = whisperRunner;
        this.defaultModel = defaultModel;
    }

    /**
     * Runs the full TRANSCRIBE pipeline for {@code assign}.
     * Sends protocol messages via {@code conn}.
     */
    public void handle(TaskAssign assign, ServerConnection conn) {
        Path workDir = TaskIo.workDir(props, assign);

        try {
            Files.createDirectories(workDir);

            // Step 1: download audio chunk
            TaskIo.progress(conn, assign, 0, "downloading");
            Path audioFile = workDir.resolve("audio.wav");
            TaskIo.download(assign.downloadUrls().get("audioChunk"), audioFile,
                    Duration.ofMinutes(10), "audio chunk");
            log.info("Task {}: audio chunk downloaded ({} bytes)",
                     assign.taskId(), Files.size(audioFile));

            // Step 2: transcribe
            TaskIo.progress(conn, assign, 5, "transcribing");
            Map<String, Object> params = assign.parameters();
            String language = params.containsKey("language")
                    ? String.valueOf(params.get("language")) : "es";
            String model = resolveModel(params);

            Path transcriptJson = workDir.resolve("transcript.json");
            whisperRunner.run(audioFile, transcriptJson, language, model,
                    pct -> TaskIo.progress(conn, assign, 5 + pct * 90 / 100, "transcribing"));

            log.info("Task {}: transcription done ({} bytes)",
                     assign.taskId(), Files.size(transcriptJson));

            // Step 3: upload transcript
            TaskIo.progress(conn, assign, 95, "uploading");
            TaskIo.upload(assign.uploadUrls().get("transcript"), transcriptJson,
                    "application/json", Duration.ofMinutes(5), "transcript");

            // Step 4: done — report the transcript key so the server can match it
            conn.sendMessage(new TaskDone(assign.taskId(), assign.attempt(),
                    Map.of("transcript", assign.uploadUrls().getOrDefault("transcript", ""))));
            log.info("Task {} DONE", assign.taskId());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Task {} interrupted", assign.taskId());
        } catch (Exception e) {
            log.error("Task {} failed: {}", assign.taskId(), e.getMessage(), e);
            conn.sendMessage(new TaskFailed(assign.taskId(), assign.attempt(), e.getMessage()));
        } finally {
            TaskIo.deleteRecursively(workDir);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String resolveModel(Map<String, Object> params) {
        Object forced = params.get("whisperModel");
        if (forced != null && !String.valueOf(forced).isBlank()
                && !"auto".equals(String.valueOf(forced))) {
            String name = String.valueOf(forced).strip();
            // A model name, never a path: the server must not point whisper at
            // arbitrary files here (DESIGN.md §13)
            if (MODEL_NAME.matcher(name).matches()) return name;
            log.warn("Task asks for whisper model '{}', which isn't a model name — using {}", name, defaultModel);
        }
        // Not props.getWhisperModel(): when the configured model failed to download,
        // WorkerApplication picked an already-downloaded stand-in, and that's this one.
        return defaultModel;
    }
}
