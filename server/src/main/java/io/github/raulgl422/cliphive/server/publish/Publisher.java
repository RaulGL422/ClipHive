package io.github.raulgl422.cliphive.server.publish;

import io.github.raulgl422.cliphive.server.model.enums.Platform;

/**
 * Publishes a rendered clip on one platform (DESIGN.md §2.3, §11). One
 * implementation per platform, or one for an intermediary (Upload-Post,
 * Ayrshare…) later. Callers never branch on the platform.
 */
public interface Publisher {

    Platform platform();

    /**
     * Publishes {@code item} and returns the platform's id for it — saved
     * right away as the idempotency key (§11.3).
     */
    UploadResult upload(String accessToken, PublishItem item) throws PublishException;

    /** Checks a video that's still being processed by the platform. */
    ProcessingResult status(String accessToken, String externalId) throws PublishException;

    /** Link to the publication for the superadmin when it follows from the id; null otherwise. */
    String publicUrl(String externalId);

    /**
     * True when "done" means a draft waiting to be posted by hand (TikTok
     * before its audit, DESIGN.md §11.4): the superadmin is then emailed the
     * text to paste (§12.3).
     */
    default boolean deliversAsDraft() {
        return false;
    }

    /**
     * True when "done" doesn't mean public yet — a draft or a private video —
     * so the publication waits for the superadmin to confirm it (DESIGN.md §11.3).
     */
    default boolean needsConfirmation() {
        return deliversAsDraft();
    }

    /** True when the platform can keep a video's clips together in a playlist (DESIGN.md §11.2). */
    default boolean supportsPlaylists() {
        return false;
    }

    /**
     * Adds a published video to a playlist, creating it first when
     * {@code playlistId} is null. Returns the playlist's id (the new one or the
     * same). Only called when {@link #supportsPlaylists()}.
     */
    default String addToPlaylist(String accessToken, String playlistId, PlaylistInfo info, String externalId)
            throws PublishException {
        throw new PublishException("Esta plataforma no tiene listas de reproducción", false);
    }

    /** Most publications a day the planner gives this platform (DESIGN.md §10.4). */
    default int dailyLimit() {
        return 10;
    }
}
