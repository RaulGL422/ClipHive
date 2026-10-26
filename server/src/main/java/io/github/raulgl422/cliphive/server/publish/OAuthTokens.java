package io.github.raulgl422.cliphive.server.publish;

import java.time.Instant;
import java.util.Objects;

/**
 * Tokens from an OAuth exchange or refresh, plus who they belong to.
 *
 * @param refreshToken null when the platform didn't send a new one (a refresh
 *                     usually keeps the old refresh token)
 * @param accountId    the platform's id of the account (null on a refresh)
 * @param displayName  the account's visible name (null on a refresh)
 */
public record OAuthTokens(String accessToken, String refreshToken, Instant expiresAt,
                          String accountId, String displayName) {

    public OAuthTokens {
        // A platform answer without one would only fail later, encrypting null
        Objects.requireNonNull(accessToken, "the platform returned no access token");
    }
}
