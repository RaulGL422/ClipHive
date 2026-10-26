package io.github.raulgl422.cliphive.protocol.message;

import java.util.List;

/**
 * Hardware and software capabilities a worker reports in {@link Hello}, so
 * the server can prefer GPU-equipped workers with large models for
 * {@code TRANSCRIBE} tasks while sending {@code RENDER_CLIP} to anyone with
 * free capacity - docs/DESIGN.md sections 6.4 and 8.5.
 *
 * <p>Detected once at worker startup (encoders via {@code ffmpeg -encoders},
 * GPU backend from the whisper.cpp startup log, model autotest) and resent
 * on every reconnect, since a worker can be moved between machines or have
 * its drivers change between runs.
 *
 * @param gpuAvailable whether a usable GPU backend (Vulkan/CUDA) was detected
 * @param encoders video encoders this worker can use, e.g. {@code "h264_amf"},
 *                 {@code "h264_nvenc"}, {@code "h264_vaapi"}, {@code "libx264"}
 *                 - see docs/DESIGN.md section 9.3 for the {@code Encoder}
 *                 implementations this maps to
 * @param whisperBackend which whisper.cpp build this worker is running, e.g.
 *                        {@code "vulkan"}, {@code "cuda"}, {@code "cpu"}
 * @param availableModels Whisper model names already downloaded on this
 *                         worker, e.g. {@code "large-v3-turbo"}, {@code "medium"}
 * @param maxConcurrentTasks how many tasks this worker accepts in parallel
 */
public record WorkerCapabilities(
        boolean gpuAvailable,
        List<String> encoders,
        String whisperBackend,
        List<String> availableModels,
        int maxConcurrentTasks
) {
}
