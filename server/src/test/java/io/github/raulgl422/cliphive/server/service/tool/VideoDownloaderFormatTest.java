package io.github.raulgl422.cliphive.server.service.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** yt-dlp format selection with an optional dubbed audio track (DESIGN.md §4 step 3). */
class VideoDownloaderFormatTest {

    @Test
    void original_audio_by_default() {
        assertThat(VideoDownloader.formatSelector(null))
                .isEqualTo("bestvideo[height<=1080]+bestaudio/best[height<=1080]");
        assertThat(VideoDownloader.formatSelector(" ")).doesNotContain("language");
    }

    @Test
    void a_dub_is_tried_first_and_falls_back_to_the_original() {
        assertThat(VideoDownloader.formatSelector("es"))
                .isEqualTo("bestvideo[height<=1080]+bestaudio[language^=es]/"
                        + "bestvideo[height<=1080]+bestaudio/best[height<=1080]");
    }

    @Test
    void the_language_cannot_inject_extra_selectors() {
        assertThat(VideoDownloader.formatSelector("es]/worst[")).contains("language^=esworst");
    }
}
