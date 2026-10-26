package io.github.raulgl422.cliphive.server.service.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;

/**
 * Splits a WAV audio file into overlapping chunks for parallel transcription
 * (docs/DESIGN.md §4, step 5 / R5).
 *
 * <p>Chunks are ~{@value #CHUNK_DURATION_SECONDS} s long with a
 * {@value #OVERLAP_SECONDS} s overlap at each boundary so whisper.cpp never
 * cuts a sentence. The server stitches the per-chunk transcripts back
 * together, discarding the overlapping words (task {@code parameters_json}
 * carries {@code chunkStartSeconds} so the merge knows where to cut).
 *
 * <p>Uses ProcessBuilder with an explicit argument list — no shell.
 * See docs/DESIGN.md §2.2.
 */
@Component
public class AudioChunker {

    /** Nominal chunk length in seconds (~10 min, as specified in DESIGN.md §4, step 5). */
    public static final int CHUNK_DURATION_SECONDS = 600;

    /** Overlap with the neighbouring chunks on each side. */
    static final int OVERLAP_SECONDS = 10;

    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final Logger log = LoggerFactory.getLogger(AudioChunker.class);

    private final ExternalToolLocator toolLocator;

    public AudioChunker(ExternalToolLocator toolLocator) {
        this.toolLocator = toolLocator;
    }

    /**
     * Splits {@code audioFile} into overlapping WAV chunks stored under
     * {@code chunksDir/chunk_NNN.wav}.
     *
     * @return ordered list of chunk metadata; single-element for short audio
     * @throws IOException          if ffprobe or ffmpeg exits non-zero
     * @throws InterruptedException if interrupted while waiting for a process
     */
    public List<ChunkInfo> chunk(Path audioFile, Path chunksDir)
            throws IOException, InterruptedException {

        Files.createDirectories(chunksDir);

        double totalDuration = probeDuration(audioFile);
        log.debug("Audio duration: {} s, chunkSize={} s, overlap={} s",
                  totalDuration, CHUNK_DURATION_SECONDS, OVERLAP_SECONDS);

        int numChunks = (int) Math.ceil(totalDuration / CHUNK_DURATION_SECONDS);
        if (numChunks == 0) numChunks = 1; // sub-second audio edge case

        List<ChunkInfo> chunks = new ArrayList<>(numChunks);

        for (int i = 0; i < numChunks; i++) {
            double nominalStart = (double) i * CHUNK_DURATION_SECONDS;
            double start = Math.max(0.0, nominalStart - OVERLAP_SECONDS);
            double end   = Math.min(totalDuration, nominalStart + CHUNK_DURATION_SECONDS + OVERLAP_SECONDS);
            double duration = end - start;

            Path chunkFile = chunksDir.resolve("chunk_%03d.wav".formatted(i));
            extractSegment(audioFile, start, duration, chunkFile);

            chunks.add(new ChunkInfo(i, numChunks, start, duration, chunkFile));
            log.debug("Chunk {}/{}: start={} s, duration={} s → {}",
                      i, numChunks, start, duration, chunkFile.getFileName());
        }

        return chunks;
    }

    /** Probes the WAV duration in seconds using ffprobe. */
    double probeDuration(Path audioFile) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                toolLocator.ffprobe().toString(),
                "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                audioFile.toAbsolutePath().toString()
        );
        pb.redirectErrorStream(true);

        ProcessRunner.Result result = ProcessRunner.run(pb, TIMEOUT);
        String output = result.stdout().strip();
        if (!result.ok()) throw new IOException("ffprobe failed: " + output);

        try {
            return Double.parseDouble(output);
        } catch (NumberFormatException e) {
            throw new IOException("ffprobe returned non-numeric duration: '" + output + "'");
        }
    }

    /** Extracts a single segment from {@code source} using ffmpeg. */
    private void extractSegment(Path source, double start, double duration, Path output)
            throws IOException, InterruptedException {

        ProcessBuilder pb = new ProcessBuilder(
                toolLocator.ffmpeg().toString(),
                "-y",
                "-ss", String.format(Locale.ROOT, "%.3f", start),
                "-t",  String.format(Locale.ROOT, "%.3f", duration),
                "-i",  source.toAbsolutePath().toString(),
                "-ar", "16000",
                "-ac", "1",
                output.toAbsolutePath().toString()
        );
        pb.redirectErrorStream(true);

        ProcessRunner.Result result = ProcessRunner.run(pb, TIMEOUT);
        if (!result.ok()) throw new IOException("ffmpeg chunk extraction failed: " + result.stdout().strip());
    }
}
