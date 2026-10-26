package io.github.raulgl422.cliphive.worker.config;

/**
 * The worker's configuration can't be used: an unreadable or malformed
 * {@code worker.yml}, or a bad value in it or in an environment variable. The
 * message names the file or variable and what's wrong, for the user to fix.
 */
public class ConfigException extends RuntimeException {

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }

    public ConfigException(String message) {
        super(message);
    }
}
