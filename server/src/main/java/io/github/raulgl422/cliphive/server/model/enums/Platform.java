package io.github.raulgl422.cliphive.server.model.enums;

/**
 * A platform the server publishes clips to - docs/DESIGN.md section 11.
 * Used by {@code social_account} (connected accounts) and
 * {@code scheduled_post} (per-platform publications).
 *
 * <p>This does NOT live in {@code protocol}: it never appears in a WebSocket
 * message, only in server-side persistence and the superadmin UI.
 */
public enum Platform {
    TIKTOK("TikTok"),
    INSTAGRAM("Instagram"),
    YOUTUBE("YouTube");

    private final String label;

    Platform(String label) {
        this.label = label;
    }

    /** The name shown in the superadmin and in emails. */
    public String label() {
        return label;
    }
}
