package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.protocol.state.VideoJobState;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.properties.ClipHiveProperties;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import io.github.raulgl422.cliphive.server.service.tool.AudioExtractor;
import io.github.raulgl422.cliphive.server.service.tool.SponsorBlockClient;
import io.github.raulgl422.cliphive.server.service.tool.SponsorSegment;
import io.github.raulgl422.cliphive.server.service.tool.VideoDownloader;
import io.github.raulgl422.cliphive.server.service.TranscribeSetupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VideoJobDownloadServiceTest {

    private VideoJobRepository jobRepository;
    private TransactionTemplate txTemplate;
    private VideoDownloader downloader;
    private SponsorBlockClient sponsorBlock;
    private AudioExtractor audioExtractor;
    private TranscribeSetupService transcribeSetup;
    private VideoJobDownloadService service;
    private JobFiles files;

    @TempDir
    Path tempDir;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jobRepository  = mock(VideoJobRepository.class);
        txTemplate     = mock(TransactionTemplate.class);
        downloader      = mock(VideoDownloader.class);
        sponsorBlock    = mock(SponsorBlockClient.class);
        audioExtractor  = mock(AudioExtractor.class);
        transcribeSetup = mock(TranscribeSetupService.class);

        // Default: txTemplate.execute() calls the callback with null tx.
        when(txTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });

        ClipHiveProperties props = new ClipHiveProperties();
        props.setDataDir(tempDir.toString());
        files = new JobFiles(props);

        service = new VideoJobDownloadService(
                jobRepository, txTemplate, downloader, sponsorBlock,
                audioExtractor, new ObjectMapper(), files, transcribeSetup);
    }

    @Test
    void happy_path_transitions_job_to_transcribing() throws Exception {
        UUID jobId = UUID.randomUUID();
        VideoJob job = downloadingJob(jobId, "https://www.youtube.com/watch?v=abc123", "youtube");
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        Path fakeSource = Files.createFile(tempDir.resolve("source.mp4"));
        Files.writeString(fakeSource, "fake-video-data");

        when(downloader.download(any(), any(), any())).thenReturn(fakeSource);
        when(audioExtractor.extract(any(), any())).thenReturn(tempDir.resolve("audio.wav"));
        when(sponsorBlock.fetchSegments(any())).thenReturn(List.of());

        service.startDownload(jobId);

        assertThat(job.getState()).isEqualTo(VideoJobState.TRANSCRIBING);
        assertThat(job.getFileHash()).isNotBlank().hasSize(64).matches("[0-9a-f]+");
        verify(jobRepository).save(job);
    }

    @Test
    void download_failure_sets_job_to_failed() throws Exception {
        UUID jobId = UUID.randomUUID();
        VideoJob job = downloadingJob(jobId, "https://youtu.be/xyz", "youtube");
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        when(downloader.download(any(), any(), any())).thenThrow(new IOException("yt-dlp exited with code 1"));

        service.startDownload(jobId);

        assertThat(job.getState()).isEqualTo(VideoJobState.FAILED);
        assertThat(job.getRejectionReason()).contains("Download failed");
    }

    @Test
    void audio_extraction_failure_sets_job_to_failed() throws Exception {
        UUID jobId = UUID.randomUUID();
        VideoJob job = downloadingJob(jobId, "https://youtu.be/xyz", "youtube");
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        Path fakeSource = Files.createFile(tempDir.resolve("source.mp4"));
        Files.writeString(fakeSource, "fake");

        when(downloader.download(any(), any(), any())).thenReturn(fakeSource);
        when(audioExtractor.extract(any(), any())).thenThrow(new IOException("ffmpeg failed"));

        service.startDownload(jobId);

        assertThat(job.getState()).isEqualTo(VideoJobState.FAILED);
        assertThat(job.getRejectionReason()).contains("Audio extraction failed");
    }

    @Test
    void non_youtube_job_skips_sponsorblock() throws Exception {
        UUID jobId = UUID.randomUUID();
        VideoJob job = downloadingJob(jobId, "https://www.twitch.tv/somestream/v/123", "twitch");
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        Path fakeSource = Files.createFile(tempDir.resolve("source.mp4"));
        Files.writeString(fakeSource, "fake");

        when(downloader.download(any(), any(), any())).thenReturn(fakeSource);
        when(audioExtractor.extract(any(), any())).thenReturn(tempDir.resolve("audio.wav"));

        service.startDownload(jobId);

        verify(sponsorBlock, never()).fetchSegments(any());
        assertThat(job.getState()).isEqualTo(VideoJobState.TRANSCRIBING);
    }

    @Test
    void sponsorblock_data_written_to_file_for_youtube_jobs() throws Exception {
        UUID jobId = UUID.randomUUID();
        VideoJob job = downloadingJob(jobId, "https://www.youtube.com/watch?v=dQw4w9WgXcQ", "youtube");
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));

        Path fakeSource = Files.createFile(tempDir.resolve("source.mp4"));
        Files.writeString(fakeSource, "fake");

        when(downloader.download(any(), any(), any())).thenReturn(fakeSource);
        when(audioExtractor.extract(any(), any())).thenReturn(tempDir.resolve("audio.wav"));
        when(sponsorBlock.fetchSegments(any())).thenReturn(
                List.of(new SponsorSegment(10.0, 30.0, "sponsor")));

        service.startDownload(jobId);

        Path sponsorFile = files.jobDir(jobId).resolve("sponsorblock.json");
        assertThat(Files.exists(sponsorFile)).isTrue();
        String content = Files.readString(sponsorFile);
        assertThat(content).contains("sponsor");
    }

    @Test
    void sha256_hex_is_deterministic() throws Exception {
        Path file = Files.createFile(tempDir.resolve("test.bin"));
        Files.writeString(file, "hello world");

        String hash1 = VideoJobDownloadService.sha256Hex(file);
        String hash2 = VideoJobDownloadService.sha256Hex(file);

        assertThat(hash1).isEqualTo(hash2).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void jobDir_uses_configured_data_dir() {
        UUID jobId = UUID.randomUUID();
        Path expected = tempDir.resolve("jobs").resolve(jobId.toString());
        assertThat(files.jobDir(jobId)).isEqualTo(expected);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static VideoJob downloadingJob(UUID id, String url, String platform) {
        VideoJob job = new VideoJob(url, "es", null, false);
        setField(job, "id", id);
        job.startDownloading("Test Video", platform, 600, 30, "1920x1080");
        return job;
    }

    private static void setField(Object obj, String name, Object value) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
