package io.github.raulgl422.cliphive.server.publish.youtube;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.raulgl422.cliphive.server.properties.ClipHiveProperties;
import io.github.raulgl422.cliphive.server.publish.OAuthTokens;
import io.github.raulgl422.cliphive.server.publish.PublishException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Google OAuth flow against a local stand-in for the token and channels endpoints. */
class YouTubeOAuthProviderTest {

    private HttpServer server;
    private YouTubeOAuthProvider provider;
    private final AtomicReference<String> lastTokenRequest = new AtomicReference<>();
    private volatile int tokenStatus = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/token", ex -> {
            lastTokenRequest.set(URLDecoder.decode(
                    new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), StandardCharsets.UTF_8));
            respond(ex, tokenStatus, tokenStatus == 200
                    ? "{\"access_token\":\"acc\",\"refresh_token\":\"ref\",\"expires_in\":3599}"
                    : "{\"error\":\"invalid_grant\"}");
        });
        server.createContext("/api/channels", ex -> respond(ex, 200,
                "{\"items\":[{\"id\":\"UC123\",\"snippet\":{\"title\":\"Mi canal\"}}]}"));
        server.start();

        ClipHiveProperties props = new ClipHiveProperties();
        props.setGoogleClientId("client-id");
        props.setGoogleClientSecret("client-secret");
        provider = new YouTubeOAuthProvider(props, JsonMapper.builder().build(),
                base + "/auth", base + "/token", base + "/api");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Test
    void authorization_url_asks_for_offline_access_and_carries_the_state() {
        String url = provider.authorizationUrl("st4te", "https://cliphive.example.com/admin/accounts/youtube/callback");
        String decoded = URLDecoder.decode(url, StandardCharsets.UTF_8);

        assertThat(decoded).contains("access_type=offline", "prompt=consent", "state=st4te",
                "youtube.upload", "redirect_uri=https://cliphive.example.com/admin/accounts/youtube/callback");
    }

    @Test
    void exchanging_the_code_returns_tokens_and_the_channel() throws Exception {
        OAuthTokens tokens = provider.exchangeCode("the-code", "https://x/cb");

        assertThat(tokens.accessToken()).isEqualTo("acc");
        assertThat(tokens.refreshToken()).isEqualTo("ref");
        assertThat(tokens.accountId()).isEqualTo("UC123");
        assertThat(tokens.displayName()).isEqualTo("Mi canal");
        assertThat(tokens.expiresAt()).isAfter(Instant.now().plusSeconds(3000));
        assertThat(lastTokenRequest.get()).contains("grant_type=authorization_code", "code=the-code");
    }

    @Test
    void a_revoked_refresh_token_is_not_retryable() {
        tokenStatus = 400;
        assertThatThrownBy(() -> provider.refresh("old"))
                .isInstanceOfSatisfying(PublishException.class, e -> assertThat(e.isRetryable()).isFalse());
    }
}
