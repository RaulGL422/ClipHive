package io.github.raulgl422.cliphive.server.service.tool;

import io.github.raulgl422.cliphive.server.service.LogEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Keeps yt-dlp current (docs/DESIGN.md §16.6): the sites it downloads from
 * change often, and an old yt-dlp is the usual reason a link stops working.
 * Runs {@code yt-dlp -U} ten minutes after startup and then once a day, and
 * logs the version. A yt-dlp that can't update itself (installed with pip or
 * apt, or a binary the service user can't write) is reported, not fatal.
 */
@Component
public class YtDlpUpdater {

    private static final Logger log = LoggerFactory.getLogger(YtDlpUpdater.class);
    private static final Duration TIMEOUT = Duration.ofMinutes(3);

    private final ExternalToolLocator tools;
    private final LogEventService logEvents;

    public YtDlpUpdater(ExternalToolLocator tools, LogEventService logEvents) {
        this.tools = tools;
        this.logEvents = logEvents;
    }

    @Scheduled(initialDelay = 600_000, fixedDelay = 86_400_000)
    public void updateDaily() {
        Path ytdlp;
        try {
            ytdlp = tools.ytdlp();
        } catch (IllegalStateException e) {
            log.warn("yt-dlp update skipped: {}", e.getMessage());
            return;
        }
        update(ytdlp);
    }

    /** @return {@code true} when yt-dlp updated (or already was current) */
    boolean update(Path ytdlp) {
        try {
            ProcessRunner.Result result = ProcessRunner.run(
                    new ProcessBuilder(ytdlp.toString(), "-U").redirectErrorStream(true), TIMEOUT);
            String output = result.stdout().strip();
            if (!result.ok()) {
                log.warn("yt-dlp -U failed (exit {}): {}", result.exitCode(), output);
                logEvents.warn("herramientas", "No se pudo actualizar yt-dlp (" + lastLine(output)
                        + "). Si está instalado con pip o apt, actualízalo por esa vía.", null);
                return false;
            }
            log.info("yt-dlp -U: {}", lastLine(output));
            logEvents.info("herramientas", "yt-dlp al día: " + version(ytdlp));
            return true;
        } catch (IOException e) {
            log.warn("yt-dlp -U could not run: {}", e.getMessage());
            logEvents.warn("herramientas", "No se pudo actualizar yt-dlp: " + e.getMessage(), null);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String version(Path ytdlp) throws IOException, InterruptedException {
        ProcessRunner.Result v = ProcessRunner.run(
                new ProcessBuilder(ytdlp.toString(), "--version").redirectErrorStream(true), Duration.ofSeconds(30));
        return v.ok() ? v.stdout().strip() : "versión desconocida";
    }

    private static String lastLine(String output) {
        String[] lines = output.split("\\R");
        return lines.length == 0 ? "" : lines[lines.length - 1];
    }
}
