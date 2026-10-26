package io.github.raulgl422.cliphive.server.publish;

import io.github.raulgl422.cliphive.server.model.enums.Platform;

import java.time.Duration;

/**
 * OAuth for one platform: connect an account from the superadmin and keep
 * its tokens fresh (DESIGN.md §11.2). One implementation per platform.
 */
public interface OAuthProvider {

    Platform platform();

    /** True when the app credentials (client id/secret) are set in {@code .env}. */
    boolean isConfigured();

    /** Where to send the browser to authorize; {@code state} comes back unchanged to the callback. */
    String authorizationUrl(String state, String redirectUri);

    /** Exchanges the code from the callback for tokens and identifies the account. */
    OAuthTokens exchangeCode(String code, String redirectUri) throws PublishException;

    /** Gets a new access token from a refresh token. */
    OAuthTokens refresh(String refreshToken) throws PublishException;

    /**
     * Refresh the token once it's this close to expiring. Short for tokens that
     * refresh with a long-lived refresh token (Google); long for tokens that
     * can only be refreshed while still valid (Instagram's 60-day token).
     */
    default Duration refreshMargin() {
        return Duration.ofMinutes(5);
    }
}
