package io.github.raulgl422.cliphive.server.service.tool;

import io.github.raulgl422.cliphive.server.service.LogEventService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The daily yt-dlp update (DESIGN.md §16.6). The JVM running the tests stands
 * in for yt-dlp: {@code java -U} exits non-zero like a yt-dlp that can't
 * update itself, so the failure path is exercised without the real tool.
 */
class YtDlpUpdaterTest {

    private static final Path JAVA = Path.of(ProcessHandle.current().info().command().orElse("java"));

    @Test
    void a_yt_dlp_that_cant_update_itself_is_reported_not_fatal() {
        LogEventService logEvents = mock(LogEventService.class);
        YtDlpUpdater updater = new YtDlpUpdater(mock(ExternalToolLocator.class), logEvents);

        assertThat(updater.update(JAVA)).isFalse();
        verify(logEvents).warn(anyString(), contains("No se pudo actualizar yt-dlp"), isNull());
    }

    @Test
    void a_missing_binary_is_reported_too() {
        LogEventService logEvents = mock(LogEventService.class);
        YtDlpUpdater updater = new YtDlpUpdater(mock(ExternalToolLocator.class), logEvents);

        assertThat(updater.update(Path.of("no-such-yt-dlp-cliphive-test"))).isFalse();
        verify(logEvents).warn(anyString(), contains("No se pudo actualizar yt-dlp"), isNull());
    }
}
