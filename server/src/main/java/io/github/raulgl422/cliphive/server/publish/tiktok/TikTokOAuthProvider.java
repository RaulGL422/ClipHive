package io.github.raulgl422.cliphive.server.publish.tiktok;

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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TikTok Login Kit for web (DESIGN.md §11.2, §11.5). The access token lasts
 * 24 h and is renewed with a 365-day refresh token, which TikTok may rotate
 * on every refresh.
 */
@Component
public class TikTokOAuthProvider implements OAuthProvider {

    /** Drafts and account stats; {@code video.publish} (Direct Post) waits for the audit. */
    static final String SCOPES = "user.info.basic,user.info.stats,video.upload";

    private final ClipHiveProperties props;
    private final TikTokApi api;

    @Autowired
    public TikTokOAuthProvider(ClipHiveProperties props, ObjectMapper objectMapper) {
        this(props, TikTokApi.production(objectMapper));
    }

    TikTokOAuthProvider(ClipHiveProperties props, TikTokApi api) {
        this.props = props;
        this.api = api;
    }

    @Override
    public Platform platform() {
        return Platform.TIKTOK;
    }

    @Override
    public boolean isConfigured() {
        return StringUtils.hasText(props.getTiktokClientKey()) && StringUtils.hasText(props.getTiktokClientSecret());
    }

    @Override
    public String authorizationUrl(String state, String redirectUri) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_key", props.getTiktokClientKey());
        params.put("scope", SCOPES);
        params.put("response_type", "code");
        params.put("redirect_uri", redirectUri);
        params.put("state", state);
        return api.webBaseUrl + "/v2/auth/authorize/?" + TikTokApi.form(params);
    }

    @Override
    public OAuthTokens exchangeCode(String code, String redirectUri) throws PublishException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_key", props.getTiktokClientKey());
        params.put("client_secret", props.getTiktokClientSecret());
        params.put("code", code);
        params.put("grant_type", "authorization_code");
        params.put("redirect_uri", redirectUri);
        JsonNode t = api.token(params);
        String accessToken = t.path("access_token").asText();
        String openId = t.path("open_id").asText("");
        if (openId.isBlank()) throw new PublishException("TikTok no devolvió el id de la cuenta", false);

        // The visible name, just for the superadmin; not worth failing the connection over
        String displayName = openId;
        try {
            JsonNode user = api.get(api.v2("user/info/?fields=open_id,display_name"), accessToken)
                    .path("data").path("user");
            displayName = user.path("display_name").asText(openId);
        } catch (PublishException ignored) {
            // keep the open_id
        }
        return new OAuthTokens(accessToken, t.path("refresh_token").asText(null), expiresAt(t), openId, displayName);
    }

    @Override
    public OAuthTokens refresh(String refreshToken) throws PublishException {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_key", props.getTiktokClientKey());
        params.put("client_secret", props.getTiktokClientSecret());
        params.put("grant_type", "refresh_token");
        params.put("refresh_token", refreshToken);
        JsonNode t = api.token(params);
        // A new refresh token replaces the old one when TikTok rotates it
        return new OAuthTokens(t.path("access_token").asText(), t.path("refresh_token").asText(null),
                expiresAt(t), null, null);
    }

    private static Instant expiresAt(JsonNode token) {
        return Instant.now().plusSeconds(token.path("expires_in").asLong(86_400));
    }
}
