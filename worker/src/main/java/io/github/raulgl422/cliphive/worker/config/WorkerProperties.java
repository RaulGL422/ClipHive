package io.github.raulgl422.cliphive.worker.config;

import java.nio.file.Path;

/**
 * Runtime configuration for the worker (docs/DESIGN.md §8.4).
 *
 * <p>Loaded from {@code worker.yml} in the OS data directory, with individual
 * fields overridable by environment variables of the form
 * {@code CLIPHIVE_WORKER_<FIELD>} (upper-snake-case). Environment variables
 * always win so headless / service deployments need no YAML file.
 *
 * <p>All fields are mutable so {@link WorkerPropertiesLoader} can populate
 * them via setters after YAML deserialization.
 */
public class WorkerProperties {

    /** WebSocket URL of the server worker connector, e.g. {@code ws://100.x.x.x:8082/ws/worker}. */
    private String serverUrl = "ws://localhost:8082/ws/worker";

    /** Plain-text worker token (stored as hash on the server — never logged here). */
    private String workerToken = "";

    /** Root data directory for task working files. Defaults to OS convention. */
    private String dataDir = defaultDataDir();

    /** Maximum tasks to accept concurrently. */
    private int maxConcurrentTasks = 1;

    /** Whisper model to use, or {@code "auto"} to pick based on detected hardware. */
    private String whisperModel = "auto";

    /** Explicit path to whisper-cli binary; empty = auto-detect on PATH. */
    private String whisperPath = "";

    /** Explicit path to ffmpeg binary; empty = auto-detect on PATH. */
    private String ffmpegPath = "";

    /** Explicit path to ffprobe binary; empty = auto-detect on PATH. */
    private String ffprobePath = "";

    /**
     * Force a specific encoder: {@code "amf"}, {@code "nvenc"}, {@code "vaapi"},
     * {@code "software"}, or {@code "auto"} (default — HardwareDetector chooses).
     * Set to {@code "software"} automatically by WorkerAutotest when GPU fails.
     * Volatile: the config window writes it and every render reads it (§8.4).
     */
    private volatile String forcedEncoder = "auto";

    /**
     * Only accept tasks when the machine is plugged into AC power. Read and
     * saved, but not enforced (pending in docs/DESIGN.md §8.3).
     */
    private boolean onlyWhenPluggedIn = false;

    /**
     * Comma-separated hourly ranges when the worker accepts tasks,
     * e.g. {@code "09:00-23:00"}; empty means always. Read and saved, but not
     * enforced (pending in docs/DESIGN.md §8.3).
     */
    private String workSchedule = "";

    /** Start (minimized to the tray) when the user logs in — docs/DESIGN.md §8.3. On by default. */
    private boolean startWithSystem = true;

    public String getServerUrl()              { return serverUrl; }
    public void   setServerUrl(String v)      { this.serverUrl = v; }

    public String getWorkerToken()            { return workerToken; }
    public void   setWorkerToken(String v)    { this.workerToken = v; }

    public String getDataDir()                { return dataDir; }
    public void   setDataDir(String v)        { this.dataDir = v; }

    public int    getMaxConcurrentTasks()     { return maxConcurrentTasks; }
    public void   setMaxConcurrentTasks(int v){ this.maxConcurrentTasks = v; }

    public String getWhisperModel()           { return whisperModel; }
    public void   setWhisperModel(String v)   { this.whisperModel = v; }

    public String getWhisperPath()            { return whisperPath; }
    public void   setWhisperPath(String v)    { this.whisperPath = v; }

    public String getFfmpegPath()             { return ffmpegPath; }
    public void   setFfmpegPath(String v)     { this.ffmpegPath = v; }

    public String getFfprobePath()            { return ffprobePath; }
    public void   setFfprobePath(String v)    { this.ffprobePath = v; }

    public String getForcedEncoder()          { return forcedEncoder; }
    public void   setForcedEncoder(String v)  { this.forcedEncoder = v; }

    public boolean isOnlyWhenPluggedIn()      { return onlyWhenPluggedIn; }
    public void    setOnlyWhenPluggedIn(boolean v) { this.onlyWhenPluggedIn = v; }

    public String getWorkSchedule()           { return workSchedule; }
    public void   setWorkSchedule(String v)   { this.workSchedule = v; }

    public boolean isStartWithSystem()        { return startWithSystem; }
    public void    setStartWithSystem(boolean v) { this.startWithSystem = v; }

    /**
     * Copies every setting from {@code other} — the config editor works on a
     * copy and only puts it in place once it has been saved.
     */
    public void copyFrom(WorkerProperties other) {
        serverUrl          = other.serverUrl;
        workerToken        = other.workerToken;
        dataDir            = other.dataDir;
        maxConcurrentTasks = other.maxConcurrentTasks;
        whisperModel       = other.whisperModel;
        whisperPath        = other.whisperPath;
        ffmpegPath         = other.ffmpegPath;
        ffprobePath        = other.ffprobePath;
        forcedEncoder      = other.forcedEncoder;
        onlyWhenPluggedIn  = other.onlyWhenPluggedIn;
        workSchedule       = other.workSchedule;
        startWithSystem    = other.startWithSystem;
    }

    private static String defaultDataDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("windows")) {
            String local = System.getenv("LOCALAPPDATA");
            return Path.of(local != null ? local : System.getProperty("user.home"), "ClipHive", "worker").toString();
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        if (xdg != null && !xdg.isBlank()) return Path.of(xdg, "cliphive", "worker").toString();
        return Path.of(System.getProperty("user.home"), ".local", "share", "cliphive", "worker").toString();
    }
}
