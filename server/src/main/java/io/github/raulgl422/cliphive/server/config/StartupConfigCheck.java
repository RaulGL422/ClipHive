package io.github.raulgl422.cliphive.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.util.ClassUtils;

import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Checks the server's configuration before anything starts (docs/DESIGN.md
 * §13.1): a missing or malformed variable stops startup with the full list of
 * what's wrong, instead of a Spring error about a missing JDBC driver or a key
 * that only fails when the first OAuth token is stored. Things the server can
 * run without are only warned about. The {@code embedded} profile is skipped,
 * but only when this build actually carries it ({@code -Pembedded}): the
 * production jar leaves {@code src/embedded} out, so activating the profile
 * there would start against no database at all, and that is an error instead.
 *
 * <p>Registered in {@code main()}: it runs once the environment is ready,
 * before any bean (and so before the datasource) is created.
 */
public class StartupConfigCheck implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartupConfigCheck.class);

    static final int MIN_SIGNING_KEY_LENGTH = 32;

    /** Present only in builds made with {@code -Pembedded} (it lives in {@code src/embedded}). */
    static final String EMBEDDED_CONFIG_CLASS = "io.github.raulgl422.cliphive.server.config.EmbeddedDataSourceConfig";

    private final String embeddedConfigClass;

    public StartupConfigCheck() {
        this(EMBEDDED_CONFIG_CLASS);
    }

    /** Test seam: the class whose presence means the embedded profile is in this build. */
    StartupConfigCheck(String embeddedConfigClass) {
        this.embeddedConfigClass = embeddedConfigClass;
    }

    /** What a check found: errors stop startup, warnings are only logged. */
    record Findings(List<String> errors, List<String> warnings) {}

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        Environment env = event.getEnvironment();
        // The demo's made-up data belongs only in the throwaway embedded database (§13.1)
        if (env.acceptsProfiles(Profiles.of("demo & !embedded"))) {
            throw new IllegalStateException("The 'demo' profile only runs together with 'embedded' "
                    + "(--spring.profiles.active=embedded,demo): its made-up data must never reach a real database");
        }
        if (env.acceptsProfiles(Profiles.of("embedded"))) {
            if (ClassUtils.isPresent(embeddedConfigClass, StartupConfigCheck.class.getClassLoader())) return;
            throw new IllegalStateException("The 'embedded' profile is not in this build: it is only for local tries, "
                    + "build the jar with -Pembedded (./mvnw -pl server package -Pembedded), "
                    + "or run without that profile and a configured .env");
        }
        Findings f = check(env);
        f.warnings().forEach(w -> log.warn("Configuration: {}", w));
        if (!f.errors().isEmpty()) {
            throw new IllegalStateException("ClipHive can't start, fix .env (see .env.example and docs/DESIGN.md §13.1):\n  - "
                    + String.join("\n  - ", f.errors()));
        }
    }

    static Findings check(Environment env) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        String dbUrl = value(env, "spring.datasource.url");
        if (dbUrl.isEmpty()) errors.add("CLIPHIVE_DB_URL is not set");
        else if (!dbUrl.startsWith("jdbc:postgresql:")) errors.add("CLIPHIVE_DB_URL must be a jdbc:postgresql: URL");
        if (value(env, "spring.datasource.username").isEmpty()) errors.add("CLIPHIVE_DB_USER is not set");

        String dataDir = value(env, "cliphive.data-dir");
        if (dataDir.isEmpty()) {
            errors.add("CLIPHIVE_DATA_DIR is not set");
        } else {
            try {
                if (!Path.of(dataDir).isAbsolute()) {
                    errors.add("CLIPHIVE_DATA_DIR must be an absolute path (got '" + dataDir + "')");
                }
            } catch (InvalidPathException e) {
                errors.add("CLIPHIVE_DATA_DIR is not a valid path: " + e.getMessage());
            }
        }

        if (value(env, "cliphive.url-signing-key").length() < MIN_SIGNING_KEY_LENGTH) {
            errors.add("CLIPHIVE_URL_SIGNING_KEY must be at least " + MIN_SIGNING_KEY_LENGTH
                       + " characters (openssl rand -hex 32)");
        }

        String encryptionKey = value(env, "cliphive.encryption-key");
        if (encryptionKey.isEmpty()) {
            errors.add("CLIPHIVE_ENCRYPTION_KEY is not set (openssl rand -base64 32)");
        } else if (!isBase64Of32Bytes(encryptionKey)) {
            errors.add("CLIPHIVE_ENCRYPTION_KEY must be Base64 of exactly 32 bytes (openssl rand -base64 32)");
        }

        for (String[] url : new String[][]{
                {"cliphive.admin-public-url", "CLIPHIVE_ADMIN_PUBLIC_URL", "platform accounts can't be connected"},
                {"cliphive.media-public-url", "CLIPHIVE_MEDIA_PUBLIC_URL", "Instagram can't fetch the clips"}}) {
            String v = value(env, url[0]);
            if (v.isEmpty()) warnings.add(url[1] + " is not set: " + url[2]);
            else if (!isWebUrl(v)) errors.add(url[1] + " must be an http(s) URL (got '" + v + "')");
        }

        if (value(env, "cliphive.operator-name").isEmpty() || value(env, "cliphive.operator-email").isEmpty()) {
            warnings.add("CLIPHIVE_OPERATOR_NAME / CLIPHIVE_OPERATOR_EMAIL are not set: the /legal pages don't say who runs this installation");
        }
        return new Findings(errors, warnings);
    }

    private static String value(Environment env, String key) {
        String v = env.getProperty(key);
        return v == null ? "" : v.strip();
    }

    private static boolean isBase64Of32Bytes(String key) {
        try {
            return Base64.getDecoder().decode(key).length == 32;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isWebUrl(String url) {
        try {
            URI uri = new URI(url);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (Exception e) {
            return false;
        }
    }
}
