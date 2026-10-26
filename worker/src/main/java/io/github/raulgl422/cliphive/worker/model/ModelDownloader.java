package io.github.raulgl422.cliphive.worker.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * Downloads whisper.cpp ggml model files on first run (docs/DESIGN.md §8.5).
 *
 * <p>Models are hosted on Hugging Face. Each download is verified against a
 * known SHA-256 hash before it is moved into the models directory. If the hash
 * does not match, the partial file is deleted and an exception is thrown.
 */
public class ModelDownloader {

    private static final Logger log = LoggerFactory.getLogger(ModelDownloader.class);

    private static final String BASE_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/";

    /**
     * Known SHA-256 hashes for the distributed ggml model files.
     * Source: https://github.com/ggerganov/whisper.cpp/blob/master/models/download-ggml-model.sh
     */
    public static final Map<String, String> MODEL_SHA256 = Map.of(
        "base",             "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe",
        "small",            "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b",
        "medium",           "6c14d5adee5f86394037b4e4e8b59f1673b6cee10e3cf0b11bbdbee79c156208",
        "large-v3-turbo",   "1fc70f774d38eb169993ac391eea357ef47c88757ef72ee5943879b7e8e2bc69"
    );

    /** No byte for this long means a stalled download. */
    static final Duration STALL_TIMEOUT = Duration.ofSeconds(60);

    private static final ScheduledExecutorService STALL_TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "model-download-stall-check");
        t.setDaemon(true);
        return t;
    });

    private final HttpClient http;

    public ModelDownloader() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Ensures {@code model} is present in {@code modelsDir}. Downloads it if
     * absent. Returns the path to the local file.
     *
     * @param model       whisper model name, e.g. {@code "base"} or {@code "large-v3-turbo"}
     * @param modelsDir   directory where model files are stored
     * @param onProgress  callback receiving bytes downloaded so far (may be null)
     * @throws IOException if download or hash verification fails
     */
    public Path ensureModel(String model, Path modelsDir, LongConsumer onProgress)
            throws IOException, InterruptedException {

        Path target = modelFile(modelsDir, model);
        String filename = target.getFileName().toString();

        if (Files.exists(target)) {
            log.debug("Model {} already present at {}", model, target);
            return target;
        }

        String expectedHash = MODEL_SHA256.get(model);
        if (expectedHash == null) {
            throw new IOException("Unknown model '" + model + "' — cannot download without a known hash");
        }

        Files.createDirectories(modelsDir);
        Path tmp = modelsDir.resolve(filename + ".tmp");

        String url = BASE_URL + filename;
        log.info("Downloading model {} from {}", model, url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(30))
                .GET()
                .build();

        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " downloading " + url);
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long totalBytes = 0;

            // The request timeout only covers the headers: a stalled body would
            // block read() forever, before the worker window even exists.
            // Closing the stream from the timer makes read() fail instead.
            AtomicLong lastRead = new AtomicLong(System.nanoTime());
            AtomicBoolean stalled = new AtomicBoolean();
            try (InputStream in = response.body()) {
                ScheduledFuture<?> stallCheck = STALL_TIMER.scheduleWithFixedDelay(() -> {
                    if (System.nanoTime() - lastRead.get() > STALL_TIMEOUT.toNanos()) {
                        stalled.set(true);
                        try { in.close(); } catch (IOException ignored) {}
                    }
                }, 5, 5, TimeUnit.SECONDS);
                byte[] buf = new byte[65536];
                int n;
                try (var out = Files.newOutputStream(tmp)) {
                    while ((n = in.read(buf)) != -1) {
                        lastRead.set(System.nanoTime());
                        out.write(buf, 0, n);
                        digest.update(buf, 0, n);
                        totalBytes += n;
                        if (onProgress != null) onProgress.accept(totalBytes);
                    }
                } catch (IOException e) {
                    if (!stalled.get()) throw e;
                    throw new IOException("Download of model " + model + " stalled: no data for "
                            + STALL_TIMEOUT.toSeconds() + " s after " + totalBytes / 1_000_000 + " MB", e);
                } finally {
                    stallCheck.cancel(false);
                }
            }

            String actualHash = HexFormat.of().formatHex(digest.digest());
            if (!expectedHash.equalsIgnoreCase(actualHash)) {
                Files.deleteIfExists(tmp);
                throw new IOException("Hash mismatch for model " + model +
                        " — expected " + expectedHash + ", got " + actualHash);
            }

            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("Model {} downloaded and verified ({} bytes)", model, totalBytes);
            return target;

        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Where whisper.cpp's {@code model} lives: {@code <modelsDir>/ggml-<model>.bin}. */
    public static Path modelFile(Path modelsDir, String model) {
        return modelsDir.resolve("ggml-" + model + ".bin");
    }

    /**
     * Returns {@code true} if the model file is already present in {@code modelsDir}.
     */
    public static boolean isPresent(String model, Path modelsDir) {
        return Files.exists(modelFile(modelsDir, model));
    }
}
