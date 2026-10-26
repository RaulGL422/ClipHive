package io.github.raulgl422.cliphive.server.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for {@code CLIPHIVE_*} environment variables.
 * See docs/DESIGN.md section 13.1 and .env.example for the full list.
 * Non-secret runtime config goes in the superadmin {@code setting} table, not here.
 */
@ConfigurationProperties(prefix = "cliphive")
public class ClipHiveProperties {

    /** Absolute path to the data directory (jobs/&lt;id&gt;/source, audio, chunks, clips). */
    private String dataDir;

    // ── Network connectors (docs/DESIGN.md §3.2) ──────────────────────────

    /**
     * Port for the media connector (localhost, only {@code /media/**}).
     * Published via a Cloudflare Tunnel hostname without Access so Instagram
     * can fetch clips. Defaults to 8081.
     */
    private int mediaPort = 8081;

    /**
     * Public base URL of the media hostname, e.g.
     * {@code https://cliphive-media.example.com}. Needed to build the absolute
     * URLs handed to Instagram.
     */
    private String mediaPublicUrl;

    /**
     * IP address of the Tailscale interface to bind the worker connector to.
     * Blank = skip the worker connector (useful when Tailscale isn't set up
     * locally). Set to {@code 127.0.0.1} for dev/testing.
     */
    private String workerBind;

    /** Port for the worker WebSocket connector (Tailscale interface only). Defaults to 8082. */
    private int workerPort = 8082;

    /**
     * HMAC-SHA256 key used to sign worker file URLs and media clip tokens.
     * Required; the application will fail to start if this is blank.
     * See docs/DESIGN.md §13.
     */
    private String urlSigningKey;

    // ── Superadmin account — only used by AdminUserInitializer on first boot ─

    /** Username for the single superadmin account. Created on first boot; env var only. */
    private String adminUser;

    /** Plain-text password for the superadmin. BCrypt-hashed before storage; env var only. */
    private String adminPassword;

    /**
     * Base64-encoded 32-byte AES-256 key for encrypting OAuth tokens and the TOTP secret
     * in the DB. Required for 2FA and social account connections. Env var only.
     */
    private String encryptionKey;

    // ── Operator shown on the public /legal pages (docs/DESIGN.md §11.5) ───

    /** Who runs this installation — the data controller named on the privacy and terms pages. */
    private String operatorName;

    /** Contact and data-deletion address shown on the privacy and terms pages. */
    private String operatorEmail;

    // ── External tool paths ────────────────────────────────────────────────

    /** Path to the {@code yt-dlp} binary; null/blank = autodetect from PATH. */
    private String ytdlpPath;

    /** Path to the {@code ffmpeg} binary; null/blank = autodetect from PATH. */
    private String ffmpegPath;

    /** Path to the {@code ffprobe} binary; null/blank = autodetect from PATH. */
    private String ffprobePath;

    /** Path to the {@code claude} binary; null/blank = autodetect from PATH. */
    private String claudePath;

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }

    public int getMediaPort() { return mediaPort; }
    public void setMediaPort(int mediaPort) { this.mediaPort = mediaPort; }

    public String getMediaPublicUrl() { return mediaPublicUrl; }
    public void setMediaPublicUrl(String mediaPublicUrl) { this.mediaPublicUrl = mediaPublicUrl; }

    public String getWorkerBind() { return workerBind; }
    public void setWorkerBind(String workerBind) { this.workerBind = workerBind; }

    public int getWorkerPort() { return workerPort; }
    public void setWorkerPort(int workerPort) { this.workerPort = workerPort; }

    public String getUrlSigningKey() { return urlSigningKey; }
    public void setUrlSigningKey(String urlSigningKey) { this.urlSigningKey = urlSigningKey; }

    public String getYtdlpPath() { return ytdlpPath; }
    public void setYtdlpPath(String ytdlpPath) { this.ytdlpPath = ytdlpPath; }

    public String getFfmpegPath() { return ffmpegPath; }
    public void setFfmpegPath(String ffmpegPath) { this.ffmpegPath = ffmpegPath; }

    public String getFfprobePath() { return ffprobePath; }
    public void setFfprobePath(String ffprobePath) { this.ffprobePath = ffprobePath; }

    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }

    public String getOperatorEmail() { return operatorEmail; }
    public void setOperatorEmail(String operatorEmail) { this.operatorEmail = operatorEmail; }

    public String getClaudePath() { return claudePath; }
    public void setClaudePath(String claudePath) { this.claudePath = claudePath; }

    public String getAdminUser() { return adminUser; }
    public void setAdminUser(String adminUser) { this.adminUser = adminUser; }

    public String getAdminPassword() { return adminPassword; }
    public void setAdminPassword(String adminPassword) { this.adminPassword = adminPassword; }

    public String getEncryptionKey() { return encryptionKey; }
    public void setEncryptionKey(String encryptionKey) { this.encryptionKey = encryptionKey; }

    // ── Publishing (docs/DESIGN.md §11) ───────────────────────────────────

    /**
     * Public base URL of the superadmin, e.g. {@code https://cliphive.example.com}.
     * OAuth redirect URIs are built from it: {@code <url>/admin/accounts/<platform>/callback}.
     */
    private String adminPublicUrl;

    /** Google Cloud OAuth client (YouTube Data API v3). */
    private String googleClientId;
    private String googleClientSecret;

    public String getAdminPublicUrl() { return adminPublicUrl; }
    public void setAdminPublicUrl(String adminPublicUrl) { this.adminPublicUrl = adminPublicUrl; }

    public String getGoogleClientId() { return googleClientId; }
    public void setGoogleClientId(String googleClientId) { this.googleClientId = googleClientId; }

    public String getGoogleClientSecret() { return googleClientSecret; }
    public void setGoogleClientSecret(String googleClientSecret) { this.googleClientSecret = googleClientSecret; }

    /** Meta app with "Instagram API with Instagram login" (Instagram Reels). */
    private String metaAppId;
    private String metaAppSecret;

    public String getMetaAppId() { return metaAppId; }
    public void setMetaAppId(String metaAppId) { this.metaAppId = metaAppId; }

    public String getMetaAppSecret() { return metaAppSecret; }
    public void setMetaAppSecret(String metaAppSecret) { this.metaAppSecret = metaAppSecret; }

    /** TikTok app (Login Kit + Content Posting API); the sandbox's keys while it isn't reviewed. */
    private String tiktokClientKey;
    private String tiktokClientSecret;

    public String getTiktokClientKey() { return tiktokClientKey; }
    public void setTiktokClientKey(String tiktokClientKey) { this.tiktokClientKey = tiktokClientKey; }

    public String getTiktokClientSecret() { return tiktokClientSecret; }
    public void setTiktokClientSecret(String tiktokClientSecret) { this.tiktokClientSecret = tiktokClientSecret; }
}
