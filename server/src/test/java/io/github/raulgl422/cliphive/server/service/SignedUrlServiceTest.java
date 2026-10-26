package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.server.properties.ClipHiveProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignedUrlServiceTest {

    private SignedUrlService service;

    @BeforeEach
    void setUp() {
        ClipHiveProperties props = new ClipHiveProperties();
        props.setUrlSigningKey("test-signing-key-32-bytes-long!!");
        props.setMediaPublicUrl("https://media.example.com");
        service = new SignedUrlService(props);
    }

    // ── Media tokens ────────────────────────────────────────────────────────

    @Test
    void generateMediaUrl_produces_absolute_url_with_token() {
        UUID clipId = UUID.randomUUID();
        String url = service.generateMediaUrl(clipId);
        assertThat(url).startsWith("https://media.example.com/media/");
    }

    @Test
    void validateMediaToken_accepts_fresh_token() {
        UUID clipId = UUID.randomUUID();
        String url = service.generateMediaUrl(clipId);
        String token = url.substring(url.lastIndexOf('/') + 1);

        UUID result = service.validateMediaToken(token);

        assertThat(result).isEqualTo(clipId);
    }

    @Test
    void validateMediaToken_rejects_tampered_token() {
        UUID clipId = UUID.randomUUID();
        String url = service.generateMediaUrl(clipId);
        String token = url.substring(url.lastIndexOf('/') + 1);
        String tampered = token.substring(0, token.length() - 4) + "XXXX";

        assertThat(service.validateMediaToken(tampered)).isNull();
    }

    @Test
    void validateMediaToken_rejects_garbage() {
        assertThat(service.validateMediaToken("not-a-token")).isNull();
        assertThat(service.validateMediaToken("")).isNull();
    }

    // ── Worker file URLs ─────────────────────────────────────────────────────

    @Test
    void generateWorkerUrl_contains_task_path_and_token() {
        UUID taskId = UUID.randomUUID();
        String url = service.generateWorkerUrl("http://100.0.0.1:8082", taskId, 1, "/tasks/" + taskId + "/source.mp4");
        assertThat(url).startsWith("http://100.0.0.1:8082/tasks/");
        assertThat(url).contains("?token=");
    }

    @Test
    void validateWorkerToken_accepts_valid_token() {
        UUID taskId = UUID.randomUUID();
        String path = "/tasks/" + taskId + "/source.mp4";
        String url = service.generateWorkerUrl("http://100.0.0.1:8082", taskId, 2, path);
        String token = url.substring(url.indexOf("?token=") + 7);

        assertThat(service.validateWorkerToken(token, taskId, 2, path)).isTrue();
    }

    @Test
    void validateWorkerToken_rejects_wrong_attempt() {
        UUID taskId = UUID.randomUUID();
        String path = "/tasks/" + taskId + "/source.mp4";
        String url = service.generateWorkerUrl("http://100.0.0.1:8082", taskId, 1, path);
        String token = url.substring(url.indexOf("?token=") + 7);

        assertThat(service.validateWorkerToken(token, taskId, 2, path)).isFalse();
    }

    @Test
    void validateWorkerToken_rejects_wrong_task() {
        UUID taskId = UUID.randomUUID();
        String path = "/tasks/" + taskId + "/source.mp4";
        String url = service.generateWorkerUrl("http://100.0.0.1:8082", taskId, 1, path);
        String token = url.substring(url.indexOf("?token=") + 7);

        assertThat(service.validateWorkerToken(token, UUID.randomUUID(), 1, path)).isFalse();
    }

    @Test
    void validateWorkerToken_rejects_tampered_token() {
        UUID taskId = UUID.randomUUID();
        String path = "/tasks/" + taskId + "/source.mp4";
        String url = service.generateWorkerUrl("http://100.0.0.1:8082", taskId, 1, path);
        String token = url.substring(url.indexOf("?token=") + 7);
        String tampered = token.substring(0, token.length() - 4) + "XXXX";

        assertThat(service.validateWorkerToken(tampered, taskId, 1, path)).isFalse();
    }

    // ── Missing key guard ───────────────────────────────────────────────────

    @Test
    void constructor_throws_when_signing_key_is_blank() {
        ClipHiveProperties props = new ClipHiveProperties();
        props.setUrlSigningKey("");
        assertThatThrownBy(() -> new SignedUrlService(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLIPHIVE_URL_SIGNING_KEY");
    }
}
