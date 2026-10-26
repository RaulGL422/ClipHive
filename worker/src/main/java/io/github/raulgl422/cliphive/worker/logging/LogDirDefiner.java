package io.github.raulgl422.cliphive.worker.logging;

import ch.qos.logback.core.PropertyDefinerBase;
import io.github.raulgl422.cliphive.worker.config.WorkerProperties;

import java.nio.file.Path;

/**
 * Logback property for the worker's log directory: {@code logs/} inside the
 * OS default data directory ({@code %LOCALAPPDATA%\ClipHive\worker} on
 * Windows, {@code ~/.local/share/cliphive/worker} on Linux).
 *
 * <p>Without a log file, a worker launched from the Start menu would lose
 * every log line when it closes, leaving no way to tell why it stopped.
 */
public class LogDirDefiner extends PropertyDefinerBase {

    @Override
    public String getPropertyValue() {
        return Path.of(new WorkerProperties().getDataDir()).resolve("logs").toString();
    }
}
