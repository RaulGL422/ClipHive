package io.github.raulgl422.cliphive.server.publish.instagram;

import io.github.raulgl422.cliphive.server.model.enums.Platform;
import io.github.raulgl422.cliphive.server.properties.ClipHiveProperties;
import io.github.raulgl422.cliphive.server.publish.OAuthProvider;
import io.github.raulgl422.cliphive.server.publish.OAuthTokens;
import io.github.raulgl422.cliphive.server.publish.PublishException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Instagram API with Instagram Login (DESIGN.md §11.2, §11.5): no Facebook
 * Page needed. The code gives a short-lived token that's exchanged right
 * away for a 60-day one; that token is its own "refresh token" — refreshing
 * returns a new 60-day token, but only while the current one is still valid,
 * hence the long refresh margin.
 */
@Component
public class InstagramOAuthProvider implements OAuthProvider {

    static final String SCOPES = "instagram_business_basic,instagram_business_content_publish";

    private final ClipHiveProperties props;
    private final InstagramApi api;

    @Autowired
    public InstagramOAuthProvider(ClipHiveProperties props, ObjectMapper objectMapper) {
        this(props, InstagramApi.production(objectMapper));
    }

    InstagramOAuthProvider(ClipHiveProperties props, InstagramApi api) {
        this.props = props;
        this.api = api;
    }

    @Override
    public Platform platform() {
        return Platform.INSTAGRAM;
    }

    @Override
    public boolean isConfigured() {
        return StringUtils.hasText(props.getMetaAppId()) && StringUtils.hasText(props.getMetaAppSecret());
    }

    /** Renew a week ahead: the 60-day token can't be refreshed once it has expired. */
    @Override
    public Duration refreshMargin() {
        return Duration.ofDays(7);
    }

    @Override
    public String authorizationUrl(String state, String redirectUri) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", props.getMetaAppId());
        params.put("redirect_uri", redirectUri);
        params.put("response_type", "code");
        params.put("scope", SCOPES);
        params.put("state", state);
        params.put("enable_fb_login", "0");
        return api.webBaseUrl + "/oauth/authorize?" + InstagramApi.form(params);
    }

    @Override
    public OAuthTokens exchangeCode(String code, String redirectUri) throws PublishException {
        // Instagram appends "#_" to the redirect; it isn't part of the code
        String cleanCode = code.endsWith("#_") ? code.substring(0, code.length() - 2) : code;

        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", props.getMetaAppId());
        params.put("client_secret", props.getMetaAppSecret());
        params.put("grant_type", "authorization_code");
        params.put("redirect_uri", redirectUri);
        params.put("code", cleanCode);
        JsonNode shortLived = api.postForm(api.apiBaseUrl + "/oauth/access_token", params);
        // The docs show {"data":[{...}]}; older responses were flat {...}
        JsonNode entry = shortLived.has("data") ? shortLived.path("data").path(0) : shortLived;
        String shortToken = entry.path("access_token").asText("");
        if (shortToken.isBlank()) throw new PublishException("Instagram no devolvió un token", false);

        JsonNode longLived = api.get(api.graphBaseUrl + "/access_token", Map.of(
                "grant_type", "ig_exchange_token",
                "client_secret", props.getMetaAppSecret(),
                "access_token", shortToken));
        String token = longLived.path("access_token").asText();
        Instant expiresAt = Instant.now().plusSeconds(longLived.path("expires_in").asLong(60L * 24 * 3600));

        // Only professional (creator/business) accounts can authorize this API at all
        JsonNode me = api.get(api.graph("me"), Map.of(
                "fields", "user_id,username",
                "access_token", token));
        String accountId = me.path("user_id").asText(me.path("id").asText(""));
        if (accountId.isBlank()) throw new PublishException("Instagram no devolvió el id de la cuenta", false);
        // The long-lived token doubles as the refresh token
        return new OAuthTokens(token, token, expiresAt, accountId, "@" + me.path("username").asText(accountId));
    }

    @Override
    public OAuthTokens refresh(String refreshToken) throws PublishException {
        JsonNode r = api.get(api.graphBaseUrl + "/refresh_access_token", Map.of(
                "grant_type", "ig_refresh_token",
                "access_token", refreshToken));
        String token = r.path("access_token").asText();
        return new OAuthTokens(token, token,
                Instant.now().plusSeconds(r.path("expires_in").asLong(60L * 24 * 3600)), null, null);
    }
}
