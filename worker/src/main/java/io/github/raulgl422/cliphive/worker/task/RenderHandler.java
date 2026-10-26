package io.github.raulgl422.cliphive.worker.task;

import io.github.raulgl422.cliphive.protocol.message.TaskAssign;
import io.github.raulgl422.cliphive.protocol.message.TaskDone;
import io.github.raulgl422.cliphive.protocol.message.TaskFailed;
import io.github.raulgl422.cliphive.protocol.message.WorkerCapabilities;
import io.github.raulgl422.cliphive.worker.config.WorkerProperties;
import io.github.raulgl422.cliphive.worker.net.ServerConnection;
import io.github.raulgl422.cliphive.worker.render.ASSGenerator;
import io.github.raulgl422.cliphive.worker.render.Encoder;
import io.github.raulgl422.cliphive.worker.render.EncoderFactory;
import io.github.raulgl422.cliphive.worker.render.FpsDecider;
import io.github.raulgl422.cliphive.worker.tool.ToolProcess;
import io.github.raulgl422.cliphive.worker.tool.WorkerToolLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Handles one RENDER_CLIP task end-to-end (docs/DESIGN.md §9.2):
 * <ol>
 *   <li>Download the per-clip video source piece via signed URL.
 *   <li>Generate the ASS karaoke subtitle file from the word list.
 *   <li>Decide fps.
 *   <li>Run ffmpeg: trim + 9:16 blur-background + ASS overlay + encode.
 *   <li>Upload the rendered clip via signed URL.
 *   <li>Send TASK_DONE.
 * </ol>
 *
 * <p>The ffmpeg filter-complex uses <b>relative paths</b> and the task's
 * working directory so Windows drive-letter escaping in the {@code ass}
 * filter is avoided (docs/DESIGN.md §2.2).
 */
public class RenderHandler {

    private static final Logger log = LoggerFactory.getLogger(RenderHandler.class);
    private static final int RENDER_TIMEOUT_MINUTES = 30;

    /**
     * Turns the {@code [a][b]} split of the trimmed video into the 9:16 frame:
     * blurred, cropped background + the full-width video on top + subtitles,
     * then the encoder's {@link Encoder#filterTail()} and {@code [vout]}.
     * gblur, not boxblur: boxblur is GPL-only and missing from the LGPL ffmpeg
     * the installer bundles (DESIGN.md §9.2, §20).
     */
    private static final String VERTICAL_LAYOUT =
            "[a]scale=270:480:force_original_aspect_ratio=increase,crop=270:480," +
            "gblur=sigma=9,scale=1080:1920[bg];" +
            "[b]scale=1080:-2[fg];" +
            "[bg][fg]overlay=(W-w)/2:(H-h)/2,ass=clip.ass";

    private final WorkerProperties props;
    private final WorkerToolLocator toolLocator;
    private final WorkerCapabilities caps;
    private final ASSGenerator assGenerator;

    public RenderHandler(WorkerProperties props,
                         WorkerToolLocator toolLocator,
                         WorkerCapabilities caps) {
        this.props = props;
        this.toolLocator = toolLocator;
        this.caps = caps;
        this.assGenerator = new ASSGenerator();
    }

    /**
     * Runs the full RENDER_CLIP pipeline for {@code assign}.
     * Sends protocol messages via {@code conn}.
     */
    @SuppressWarnings("unchecked")
    public void handle(TaskAssign assign, ServerConnection conn) {
        Path workDir = TaskIo.workDir(props, assign);

        try {
            Files.createDirectories(workDir);
            Map<String, Object> params = assign.parameters();

            // Step 1: download source clip piece
            TaskIo.progress(conn, assign, 0, "downloading");
            Path sourceFile = workDir.resolve("source.mp4");
            TaskIo.download(assign.downloadUrls().get("sourceClip"), sourceFile,
                    Duration.ofMinutes(15), "source clip");
            log.info("Task {}: source clip downloaded ({} bytes)",
                     assign.taskId(), Files.size(sourceFile));

            // Step 2: generate ASS subtitles
            TaskIo.progress(conn, assign, 10, "subtitles");
            List<Map<String, Object>> words =
                    (List<Map<String, Object>>) params.getOrDefault("words", List.of());
            List<Map<String, Object>> keptRanges =
                    (List<Map<String, Object>>) params.getOrDefault("keptRanges", List.of());

            long clipStartMs = keptRanges.isEmpty() ? 0
                    : ((Number) keptRanges.get(0).get("startMs")).longValue();

            Path assFile = workDir.resolve("clip.ass");
            int marginV = params.get("subtitleMarginV") instanceof Number n
                    ? n.intValue() : ASSGenerator.DEFAULT_MARGIN_V;
            String overlayTitle = params.get("overlayTitle") instanceof String s ? s : null;
            assGenerator.generate(words, clipStartMs, assFile, marginV, overlayTitle);
            log.debug("Task {}: ASS generated ({} words)", assign.taskId(), words.size());

            // Step 3: decide fps and pick encoder
            String fpsMode = params.getOrDefault("fpsMode", "auto").toString();
            int fps = FpsDecider.decide(fpsMode, 0);
            // Read on every render: a change in the config window applies without a restart (§8.4)
            Encoder encoder = EncoderFactory.from(caps.encoders(), props.getForcedEncoder(), fps);
            TaskIo.progress(conn, assign, 15, "rendering");

            // Step 4: run ffmpeg
            double pieceStartSeconds = ((Number) params.getOrDefault("pieceStartSeconds", 0.0)).doubleValue();
            Path outputFile = workDir.resolve("clip.mp4");
            runFfmpeg(sourceFile, assFile, outputFile, fps, keptRanges, pieceStartSeconds, workDir, encoder);
            log.info("Task {}: render done ({} bytes)", assign.taskId(), Files.size(outputFile));

            // Step 5: upload rendered clip
            TaskIo.progress(conn, assign, 95, "uploading");
            String uploadUrl = assign.uploadUrls().get("renderedClip");
            TaskIo.upload(uploadUrl, outputFile, "video/mp4", Duration.ofMinutes(15), "rendered clip");

            // Step 6: done
            conn.sendMessage(new TaskDone(assign.taskId(), assign.attempt(),
                    Map.of("renderedClip", uploadUrl != null ? uploadUrl : "")));
            log.info("Task {} (RENDER_CLIP) DONE", assign.taskId());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Task {} interrupted", assign.taskId());
        } catch (Exception e) {
            log.error("Task {} (RENDER_CLIP) failed: {}", assign.taskId(), e.getMessage(), e);
            conn.sendMessage(new TaskFailed(assign.taskId(), assign.attempt(), e.getMessage()));
        } finally {
            TaskIo.deleteRecursively(workDir);
        }
    }

    // ── ffmpeg ────────────────────────────────────────────────────────────────

    private void runFfmpeg(Path sourceFile, Path assFile, Path outputFile,
                           int fps, List<Map<String, Object>> keptRanges,
                           double pieceStartSeconds, Path workDir, Encoder encoder)
            throws IOException, InterruptedException {

        if (keptRanges.isEmpty()) throw new IOException("No kept ranges in RENDER_CLIP parameters");

        // Build trim expressions relative to the source piece
        // The piece starts at pieceStartSeconds in the original video.
        // keptRanges are in original video ms → convert to piece-relative seconds.
        List<double[]> trimRanges = new ArrayList<>();
        for (Map<String, Object> r : keptRanges) {
            double startS = ((Number) r.get("startMs")).longValue() / 1000.0 - pieceStartSeconds;
            double endS   = ((Number) r.get("endMs")).longValue()   / 1000.0 - pieceStartSeconds;
            trimRanges.add(new double[]{Math.max(0, startS), Math.max(0, endS)});
        }

        String filterComplex = buildFilterComplex(trimRanges, fps, encoder.filterTail());

        List<String> cmd = new ArrayList<>();
        cmd.add(toolLocator.ffmpeg().toString());
        cmd.add("-y");
        cmd.add("-hide_banner"); // the banner would push the real error out of TASK_FAILED
        cmd.add("-nostats");     // the per-frame stats line would fill the output log
        // Pre-input args (e.g. -vaapi_device for VAAPI)
        cmd.addAll(encoder.preInputArgs());
        cmd.addAll(List.of("-i", "source.mp4")); // relative path (workDir is set below)
        cmd.addAll(List.of("-filter_complex", filterComplex));
        // Pre-codec args (e.g. -preset veryfast for libx264)
        cmd.addAll(encoder.preCodecArgs());
        cmd.addAll(List.of(
                "-map", "[vout]",
                "-map", "[aout]",
                "-c:v", encoder.codecName(),
                "-b:v", String.valueOf(encoder.bitrate()),
                "-c:a", "aac",
                "-b:a", "160k",
                "clip.mp4"  // relative path
        ));

        // all paths in the filter are relative to workDir
        ToolProcess.runLogged(cmd, workDir, workDir.resolve("ffmpeg.log"),
                Duration.ofMinutes(RENDER_TIMEOUT_MINUTES), "ffmpeg render");
    }

    /**
     * Builds the filter_complex string for the render.
     *
     * <p>For a single kept range (the most common case):
     * <pre>
     * [0:v]trim=start:end,setpts=PTS-STARTPTS,fps=N,split[a][b];
     * [a]scale=270:480:force_original_aspect_ratio=increase,crop=270:480,
     *    gblur=sigma=9,scale=1080:1920[bg];
     * [b]scale=1080:-2[fg];
     * [bg][fg]overlay=(W-w)/2:(H-h)/2,ass=clip.ass[vout];
     * [0:a]atrim=start:end,asetpts=PTS-STARTPTS[aout]
     * </pre>
     *
     * <p>For multiple ranges: separate trim/atrim segments are concat'd.
     */
    static String buildFilterComplex(List<double[]> trimRanges, int fps) {
        return buildFilterComplex(trimRanges, fps, "");
    }

    /** Same, with {@code filterTail} (e.g. VAAPI's upload) at the end of the video chain. */
    static String buildFilterComplex(List<double[]> trimRanges, int fps, String filterTail) {
        String layout = VERTICAL_LAYOUT + (filterTail.isEmpty() ? "" : "," + filterTail) + "[vout];";
        if (trimRanges.size() == 1) {
            double s = trimRanges.get(0)[0];
            double e = trimRanges.get(0)[1];
            // Locale.ROOT everywhere: with a Spanish locale %.3f prints "5,000",
            // and ffmpeg reads the comma as a filter separator.
            return String.format(Locale.ROOT, "[0:v]trim=%.3f:%.3f,setpts=PTS-STARTPTS,fps=%d,split[a][b];" +
                    layout +
                    "[0:a]atrim=%.3f:%.3f,asetpts=PTS-STARTPTS[aout]",
                    s, e, fps, s, e);
        }

        // Multi-range: concat
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < trimRanges.size(); i++) {
            double s = trimRanges.get(i)[0];
            double e = trimRanges.get(i)[1];
            sb.append(String.format(Locale.ROOT, "[0:v]trim=%.3f:%.3f,setpts=PTS-STARTPTS[v%d];", s, e, i));
            sb.append(String.format(Locale.ROOT, "[0:a]atrim=%.3f:%.3f,asetpts=PTS-STARTPTS[a%d];", s, e, i));
        }
        // Concat the video and audio segments
        for (int i = 0; i < trimRanges.size(); i++) sb.append("[v%d][a%d]".formatted(i, i));
        sb.append("concat=n=%d:v=1:a=1[vtmp][atmp];".formatted(trimRanges.size()));
        // Apply fps, 9:16, subtitles
        sb.append("[vtmp]fps=%d,split[a][b];".formatted(fps));
        sb.append(layout);
        sb.append("[atmp]asetpts=PTS-STARTPTS[aout]");
        return sb.toString();
    }
}
