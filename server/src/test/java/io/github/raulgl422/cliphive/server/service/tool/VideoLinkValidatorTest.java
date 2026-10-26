package io.github.raulgl422.cliphive.server.service.tool;

import io.github.raulgl422.cliphive.server.properties.ClipHiveProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the JSON parsing and error-extraction logic in VideoLinkValidator.
 * The actual subprocess invocation is not tested here (it requires yt-dlp on PATH);
 * that is covered by integration or manual smoke-testing.
 */
class VideoLinkValidatorTest {

    private VideoLinkValidatorUnderTest validator;

    @BeforeEach
    void setUp() {
        var props = new ClipHiveProperties();
        var locator = new ExternalToolLocator(props);
        validator = new VideoLinkValidatorUnderTest(locator, JsonMapper.builder().build());
    }

    // --- parseOutput ---

    @Test
    void parseOutput_lists_the_audio_languages_on_offer() {
        String json = """
                {"title":"T","extractor":"youtube","duration":60,"channel":null,"uploader":"Canal X",
                 "formats":[
                   {"format_id":"137","vcodec":"avc1","acodec":"none","language":null},
                   {"format_id":"140-0","vcodec":"none","acodec":"mp4a.40.2","language":"en"},
                   {"format_id":"140-1","vcodec":"none","acodec":"mp4a.40.2","language":"es-419"},
                   {"format_id":"251","vcodec":"none","acodec":"opus","language":"en"}
                 ]}""";

        var result = (ValidationResult.Success) validator.parseOutput(json);

        assertThat(result.audioLanguages()).containsExactly("en", "es-419");
        assertThat(result.hasAudioIn("es")).as("es matches es-419").isTrue();
        assertThat(result.hasAudioIn("en")).isTrue();
        assertThat(result.hasAudioIn("fr")).isFalse();
        assertThat(result.hasAudioIn("e")).as("only whole language codes").isFalse();
        assertThat(result.channel()).as("uploader when there's no channel").isEqualTo("Canal X");
    }

    @Test
    void parseOutput_full_metadata_returns_success() {
        String json = """
                {
                  "title": "My Video",
                  "extractor": "youtube",
                  "duration": 3723.5,
                  "fps": 29.97,
                  "width": 1920,
                  "height": 1080
                }
                """;

        var result = validator.parseOutput(json);

        assertThat(result).isInstanceOf(ValidationResult.Success.class);
        var s = (ValidationResult.Success) result;
        assertThat(s.title()).isEqualTo("My Video");
        assertThat(s.sourcePlatform()).isEqualTo("youtube");
        assertThat(s.durationSeconds()).isEqualTo(3724); // Math.round(3723.5)
        assertThat(s.sourceFps()).isEqualTo(30);         // Math.round(29.97)
        assertThat(s.resolution()).isEqualTo("1920x1080");
    }

    @Test
    void parseOutput_missing_optional_fields_are_null() {
        String json = """
                {
                  "title": "Partial",
                  "extractor": "twitch:stream",
                  "duration": 600
                }
                """;

        var result = (ValidationResult.Success) validator.parseOutput(json);

        assertThat(result.sourceFps()).isNull();
        assertThat(result.resolution()).isNull();
    }

    @Test
    void parseOutput_null_fps_and_dimensions_handled() {
        String json = """
                {
                  "title": "Live",
                  "extractor": "kick:video",
                  "duration": 1800,
                  "fps": null,
                  "width": null,
                  "height": null
                }
                """;

        var result = (ValidationResult.Success) validator.parseOutput(json);

        assertThat(result.sourceFps()).isNull();
        assertThat(result.resolution()).isNull();
    }

    @Test
    void parseOutput_missing_duration_defaults_to_zero() {
        String json = """
                {
                  "title": "Unknown duration",
                  "extractor": "youtube"
                }
                """;

        var result = (ValidationResult.Success) validator.parseOutput(json);

        assertThat(result.durationSeconds()).isEqualTo(0);
    }

    @Test
    void parseOutput_missing_extractor_defaults_to_unknown() {
        String json = """
                {
                  "title": "No extractor"
                }
                """;

        var result = (ValidationResult.Success) validator.parseOutput(json);

        assertThat(result.sourcePlatform()).isEqualTo("unknown");
    }

    @Test
    void parseOutput_invalid_json_returns_failure() {
        var result = validator.parseOutput("not valid json {{{");

        assertThat(result).isInstanceOf(ValidationResult.Failure.class);
        assertThat(((ValidationResult.Failure) result).reason())
                .contains("Failed to parse yt-dlp output");
    }

    // --- extractError ---

    @Test
    void extractError_picks_up_ERROR_prefix_line() {
        String stderr = """
                [youtube] Extracting URL: https://example.com
                ERROR: [youtube] Video unavailable. This video is private.
                """;

        String reason = VideoLinkValidatorUnderTest.extractError(stderr);

        assertThat(reason).isEqualTo("[youtube] Video unavailable. This video is private.");
    }

    @Test
    void extractError_falls_back_to_full_stderr_when_no_ERROR_line() {
        String stderr = "yt-dlp: command not found";

        String reason = VideoLinkValidatorUnderTest.extractError(stderr);

        assertThat(reason).contains("yt-dlp: command not found");
    }

    @Test
    void extractError_returns_default_message_for_blank_stderr() {
        String reason = VideoLinkValidatorUnderTest.extractError("   \n  ");

        assertThat(reason).isEqualTo("yt-dlp exited with error");
    }

    /**
     * Subclass that exposes the package-private parsing and error-extraction
     * helpers so they can be unit-tested without spawning a real subprocess.
     */
    static class VideoLinkValidatorUnderTest extends VideoLinkValidator {

        VideoLinkValidatorUnderTest(ExternalToolLocator toolLocator,
                                    ObjectMapper objectMapper) {
            super(toolLocator, objectMapper);
        }

        ValidationResult parseOutput(String json) {
            // delegate to the private method — same package so access is fine
            return super.parseOutput(json);
        }

        static String extractError(String stderr) {
            return VideoLinkValidator.extractError(stderr);
        }
    }
}
