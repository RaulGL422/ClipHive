package io.github.raulgl422.cliphive.server.service.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SponsorBlockClientTest {

    private final SponsorBlockClient client =
            new SponsorBlockClient(new ObjectMapper());

    // ── extractYouTubeId ────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ, dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ,               dQw4w9WgXcQ",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ,  dQw4w9WgXcQ",
            "https://www.youtube.com/embed/dQw4w9WgXcQ,   dQw4w9WgXcQ",
    })
    void extractYouTubeId_recognises_all_url_formats(String url, String expectedId) {
        assertThat(SponsorBlockClient.extractYouTubeId(url.trim())).isEqualTo(expectedId.trim());
    }

    @Test
    void extractYouTubeId_returns_null_for_non_youtube_url() {
        assertThat(SponsorBlockClient.extractYouTubeId("https://www.twitch.tv/somestream")).isNull();
        assertThat(SponsorBlockClient.extractYouTubeId("https://kick.com/channel")).isNull();
        assertThat(SponsorBlockClient.extractYouTubeId(null)).isNull();
    }

    // ── parseSegments ───────────────────────────────────────────────────────

    @Test
    void parseSegments_returns_all_valid_segments() {
        String json = """
                [
                  {"segment":[10.5,30.0],"category":"sponsor","UUID":"abc"},
                  {"segment":[120.0,135.5],"category":"intro","UUID":"def"}
                ]
                """;
        List<SponsorSegment> segments = client.parseSegments(json, "dQw4w9WgXcQ");

        assertThat(segments).hasSize(2);
        assertThat(segments.get(0).start()).isEqualTo(10.5);
        assertThat(segments.get(0).end()).isEqualTo(30.0);
        assertThat(segments.get(0).category()).isEqualTo("sponsor");
        assertThat(segments.get(1).category()).isEqualTo("intro");
    }

    @Test
    void parseSegments_returns_empty_for_empty_array() {
        assertThat(client.parseSegments("[]", "vid")).isEmpty();
    }

    @Test
    void parseSegments_skips_malformed_entries() {
        // One valid, one missing the segment array, one with wrong size.
        String json = """
                [
                  {"segment":[5.0,10.0],"category":"outro"},
                  {"category":"sponsor"},
                  {"segment":[1.0],"category":"selfpromo"}
                ]
                """;
        List<SponsorSegment> segments = client.parseSegments(json, "vid");
        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).category()).isEqualTo("outro");
    }

    @Test
    void parseSegments_returns_empty_for_garbage_input() {
        assertThat(client.parseSegments("not json", "vid")).isEmpty();
        assertThat(client.parseSegments("{}", "vid")).isEmpty();
    }
}
