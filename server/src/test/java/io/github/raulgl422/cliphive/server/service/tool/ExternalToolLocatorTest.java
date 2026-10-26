package io.github.raulgl422.cliphive.server.service.tool;

import io.github.raulgl422.cliphive.server.properties.ClipHiveProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalToolLocatorTest {

    private ExternalToolLocator locatorWith(String ytdlpPath, String ffmpegPath,
                                             String ffprobePath, String claudePath) {
        var props = new ClipHiveProperties();
        props.setYtdlpPath(ytdlpPath);
        props.setFfmpegPath(ffmpegPath);
        props.setFfprobePath(ffprobePath);
        props.setClaudePath(claudePath);
        return new ExternalToolLocator(props);
    }

    @Test
    void explicit_path_is_returned_as_is() {
        var locator = locatorWith("/usr/bin/yt-dlp", null, null, null);
        assertThat(locator.ytdlp()).isEqualTo(Path.of("/usr/bin/yt-dlp"));
    }

    @Test
    void explicit_path_with_spaces_is_preserved() {
        var path = "C:\\Program Files\\yt-dlp\\yt-dlp.exe";
        var locator = locatorWith(path, null, null, null);
        assertThat(locator.ytdlp()).isEqualTo(Path.of(path));
    }

    @Test
    void blank_configured_path_falls_through_to_path_search() {
        // An explicit blank value must be treated the same as null (not configured).
        // If PATH doesn't have the tool either, we expect an exception, not a blank path.
        var locator = locatorWith("", null, null, null);
        // We can't assert the exact Path since PATH varies; just verify no blank path is returned.
        // If the tool isn't on PATH, IllegalStateException is the correct outcome.
        try {
            Path result = locator.ytdlp();
            assertThat(result.toString()).isNotBlank();
        } catch (IllegalStateException e) {
            assertThat(e.getMessage()).contains("yt-dlp");
        }
    }

    @Test
    void missing_tool_not_on_path_throws_with_name_in_message() {
        // Use a tool name that certainly won't be on PATH.
        var props = new ClipHiveProperties();
        var locator = new ExternalToolLocator(props);
        // locate() with a name that's definitely not installed
        assertThatThrownBy(() -> locator.locate("no-such-binary-xyzzy-12345", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-such-binary-xyzzy-12345");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windows_path_autodetection_appends_exe() {
        // If java.exe is on PATH (it must be, since we're running with it),
        // locating "java" should return a path ending in java.exe on Windows.
        var props = new ClipHiveProperties();
        var locator = new ExternalToolLocator(props);
        Path java = locator.locate("java", null);
        assertThat(java.toString()).endsWith("java.exe");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void unix_path_autodetection_has_no_exe_suffix() {
        var props = new ClipHiveProperties();
        var locator = new ExternalToolLocator(props);
        Path java = locator.locate("java", null);
        assertThat(java.toString()).doesNotEndWith(".exe");
        assertThat(java.getFileName().toString()).isEqualTo("java");
    }
}
