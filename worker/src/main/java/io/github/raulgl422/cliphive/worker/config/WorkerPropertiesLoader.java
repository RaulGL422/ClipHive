package io.github.raulgl422.cliphive.worker.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Loads {@link WorkerProperties} from {@code worker.yml} (in the OS data
 * directory) and then applies environment-variable overrides.
 *
 * <p>Environment variables take the form {@code CLIPHIVE_WORKER_<FIELD>}
 * where {@code <FIELD>} is the upper-snake-case field name, e.g.:
 * <ul>
 *   <li>{@code CLIPHIVE_WORKER_SERVER_URL}
 *   <li>{@code CLIPHIVE_WORKER_TOKEN}
 *   <li>{@code CLIPHIVE_WORKER_DATA_DIR}
 *   <li>{@code CLIPHIVE_WORKER_MAX_CONCURRENT_TASKS}
 *   <li>{@code CLIPHIVE_WORKER_WHISPER_MODEL}
 *   <li>{@code CLIPHIVE_WORKER_WHISPER_PATH}
 *   <li>{@code CLIPHIVE_WORKER_FFMPEG_PATH}
 *   <li>{@code CLIPHIVE_WORKER_FFPROBE_PATH}
 *   <li>{@code CLIPHIVE_WORKER_FORCED_ENCODER}
 *   <li>{@code CLIPHIVE_WORKER_WORK_SCHEDULE}
 * </ul>
 */
public class WorkerPropertiesLoader {

    private static final Logger log = LoggerFactory.getLogger(WorkerPropertiesLoader.class);

    /** The keys {@code worker.yml} may contain — anything else is probably a typo. */
    private static final Set<String> KNOWN_KEYS = Set.of(
            "serverUrl", "workerToken", "dataDir", "maxConcurrentTasks", "whisperModel", "whisperPath",
            "ffmpegPath", "ffprobePath", "forcedEncoder", "onlyWhenPluggedIn", "workSchedule", "startWithSystem");

    /**
     * Loads properties. If no YAML file exists the defaults in
     * {@link WorkerProperties} are used (env vars still applied on top).
     *
     * @throws ConfigException if the file exists but can't be read or parsed, or
     *                         a value can't be used. Falling back to defaults would
     *                         look like a first run, and the setup window would
     *                         then overwrite the user's file.
     */
    public WorkerProperties load(Path yamlFile) {
        WorkerProperties props = new WorkerProperties();

        if (Files.exists(yamlFile)) {
            try (InputStream is = Files.newInputStream(yamlFile)) {
                applyYaml(props, is, yamlFile);
                log.info("Loaded config from {}", yamlFile);
            } catch (IOException e) {
                throw new ConfigException("No se puede leer " + yamlFile + ": " + e.getMessage(), e);
            }
        } else {
            log.info("No worker.yml found at {} — using defaults + env vars", yamlFile);
        }

        applyEnv(props);
        return props;
    }

    /**
     * Persists {@code props} to {@code yamlFile}, overwriting it. Used by
     * {@link io.github.raulgl422.cliphive.worker.test.WorkerAutotest} after GPU fallback
     * and by the JavaFX config editor.
     */
    public void save(WorkerProperties props, Path yamlFile) throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("serverUrl",           props.getServerUrl());
        map.put("workerToken",         props.getWorkerToken());
        map.put("dataDir",             props.getDataDir());
        map.put("maxConcurrentTasks",  props.getMaxConcurrentTasks());
        map.put("whisperModel",        props.getWhisperModel());
        map.put("whisperPath",         props.getWhisperPath());
        map.put("ffmpegPath",          props.getFfmpegPath());
        map.put("ffprobePath",         props.getFfprobePath());
        map.put("forcedEncoder",       props.getForcedEncoder());
        map.put("onlyWhenPluggedIn",   props.isOnlyWhenPluggedIn());
        map.put("workSchedule",        props.getWorkSchedule());
        map.put("startWithSystem",     props.isStartWithSystem());

        DumperOptions opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setPrettyFlow(true);

        Files.createDirectories(yamlFile.getParent());
        // Written next to it and moved over it: a crash or power cut mid-write
        // must not leave a truncated worker.yml that stops the next start.
        Path tmp = yamlFile.resolveSibling(yamlFile.getFileName() + ".tmp");
        Files.writeString(tmp, new Yaml(opts).dump(map));
        try {
            Files.move(tmp, yamlFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, yamlFile, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("Config saved to {}", yamlFile);
    }

    @SuppressWarnings("unchecked")
    private void applyYaml(WorkerProperties props, InputStream is, Path file) {
        Object loaded;
        try {
            loaded = new Yaml().load(is);
        } catch (YAMLException e) {
            throw new ConfigException(file + " no es un YAML válido: " + e.getMessage(), e);
        }
        if (loaded == null) return;
        if (!(loaded instanceof Map<?, ?> raw)) {
            throw new ConfigException(file + " debe ser una lista de clave: valor (ver worker.yml.example)");
        }
        Map<String, Object> map = (Map<String, Object>) raw;
        for (Object key : map.keySet()) {
            if (!KNOWN_KEYS.contains(String.valueOf(key))) {
                log.warn("Unknown key '{}' in {} — ignored (typo?)", key, file);
            }
        }

        // A key left blank ("ffmpegPath:") is null in YAML: keep the default, not the text "null"
        set(map, "serverUrl",          props::setServerUrl);
        set(map, "workerToken",        props::setWorkerToken);
        set(map, "dataDir",            props::setDataDir);
        if (map.get("maxConcurrentTasks") != null) {
            props.setMaxConcurrentTasks(intVal(map.get("maxConcurrentTasks"), "maxConcurrentTasks in " + file));
        }
        set(map, "whisperModel",       props::setWhisperModel);
        set(map, "whisperPath",        props::setWhisperPath);
        set(map, "ffmpegPath",         props::setFfmpegPath);
        set(map, "ffprobePath",        props::setFfprobePath);
        set(map, "forcedEncoder",      props::setForcedEncoder);
        if (map.get("onlyWhenPluggedIn") != null) props.setOnlyWhenPluggedIn(boolVal(map.get("onlyWhenPluggedIn")));
        set(map, "workSchedule",       props::setWorkSchedule);
        if (map.get("startWithSystem") != null)   props.setStartWithSystem(boolVal(map.get("startWithSystem")));
    }

    private void applyEnv(WorkerProperties props) {
        env("CLIPHIVE_WORKER_SERVER_URL",          props::setServerUrl);
        env("CLIPHIVE_WORKER_TOKEN",               props::setWorkerToken);
        env("CLIPHIVE_WORKER_DATA_DIR",            props::setDataDir);
        env("CLIPHIVE_WORKER_MAX_CONCURRENT_TASKS",
            v -> props.setMaxConcurrentTasks(intVal(v, "CLIPHIVE_WORKER_MAX_CONCURRENT_TASKS")));
        env("CLIPHIVE_WORKER_WHISPER_MODEL",       props::setWhisperModel);
        env("CLIPHIVE_WORKER_WHISPER_PATH",        props::setWhisperPath);
        env("CLIPHIVE_WORKER_FFMPEG_PATH",         props::setFfmpegPath);
        env("CLIPHIVE_WORKER_FFPROBE_PATH",        props::setFfprobePath);
        env("CLIPHIVE_WORKER_FORCED_ENCODER",      props::setForcedEncoder);
        env("CLIPHIVE_WORKER_WORK_SCHEDULE",       props::setWorkSchedule);
    }

    private static void env(String key, Consumer<String> setter) {
        String val = System.getenv(key);
        if (val != null && !val.isBlank()) setter.accept(val);
    }

    private static void set(Map<String, Object> m, String k, Consumer<String> setter) {
        Object v = m.get(k);
        if (v != null) setter.accept(String.valueOf(v));
    }

    /** A positive whole number, or a ConfigException naming {@code where}. */
    private static int intVal(Object v, String where) {
        int n;
        try {
            n = v instanceof Number num ? num.intValue() : Integer.parseInt(String.valueOf(v).strip());
        } catch (NumberFormatException e) {
            throw new ConfigException(where + " debe ser un número entero (es '" + v + "')", e);
        }
        if (n < 1) throw new ConfigException(where + " debe ser 1 o más (es " + n + ")");
        return n;
    }

    private static boolean boolVal(Object v) {
        return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
    }
}
