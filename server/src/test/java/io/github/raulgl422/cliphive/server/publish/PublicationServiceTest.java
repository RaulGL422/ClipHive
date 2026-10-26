package io.github.raulgl422.cliphive.server.publish;

import io.github.raulgl422.cliphive.protocol.state.ClipState;
import io.github.raulgl422.cliphive.protocol.state.ScheduledPostState;
import io.github.raulgl422.cliphive.protocol.state.VideoJobState;
import io.github.raulgl422.cliphive.server.model.Clip;
import io.github.raulgl422.cliphive.server.model.ScheduledPost;
import io.github.raulgl422.cliphive.server.model.SocialAccount;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.model.enums.Platform;
import io.github.raulgl422.cliphive.server.model.enums.ScheduledPostOrigin;
import io.github.raulgl422.cliphive.server.repository.ClipRepository;
import io.github.raulgl422.cliphive.server.repository.ScheduledPostRepository;
import io.github.raulgl422.cliphive.server.repository.SocialAccountRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import io.github.raulgl422.cliphive.server.security.EncryptionService;
import io.github.raulgl422.cliphive.server.service.JobFiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.FileSystemUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Immediate-mode publishing end to end on the embedded DB, with a fake
 * publisher standing in for a platform. The scheduler is switched off so the
 * test drives each step.
 */
@SpringBootTest(properties = {"cliphive.publishing.scheduler-enabled=false",
        "spring.main.allow-bean-definition-overriding=true"})
@ActiveProfiles("embedded")
class PublicationServiceTest {

    /** A controllable platform, registered under the real TikTok publisher's bean name so it replaces it. */
    static class FakePublisher implements Publisher {
        final AtomicReference<PublishException> uploadError = new AtomicReference<>();
        volatile Error crash;
        volatile ProcessingResult status = ProcessingResult.done();
        volatile RuntimeException statusCrash;
        final AtomicInteger uploads = new AtomicInteger();
        volatile String lastTitle;
        /** Delivers drafts (TikTok before its audit): uploads wait for confirmation. */
        volatile boolean draft;

        @Override public boolean deliversAsDraft() { return draft; }

        @Override public Platform platform() { return Platform.TIKTOK; }

        @Override
        public UploadResult upload(String accessToken, PublishItem item) throws PublishException {
            if (crash != null) throw crash;
            PublishException e = uploadError.get();
            if (e != null) throw e;
            lastTitle = item.metadata().title();
            uploads.incrementAndGet();
            return new UploadResult("ext-" + item.file().getParent().getFileName(), null);
        }

        @Override
        public ProcessingResult status(String accessToken, String externalId) {
            if (statusCrash != null) throw statusCrash;
            return status;
        }

        @Override
        public String publicUrl(String externalId) { return "https://example.com/" + externalId; }

        /** Playlist calls: "playlistId|videoId", in order. */
        final List<String> playlistCalls = new CopyOnWriteArrayList<>();

        @Override
        public boolean supportsPlaylists() { return true; }

        @Override
        public String addToPlaylist(String accessToken, String playlistId, PlaylistInfo info, String externalId) {
            playlistCalls.add(playlistId + "|" + externalId);
            return playlistId == null ? "PL-" + info.title() : playlistId;
        }
    }

    @TestConfiguration
    static class Config {
        @Bean FakePublisher tikTokPublisher() { return new FakePublisher(); }
    }

    @Autowired PublicationService publications;
    @Autowired FakePublisher fake;
    @Autowired ScheduledPostRepository posts;
    @Autowired ClipRepository clips;
    @Autowired VideoJobRepository jobs;
    @Autowired SocialAccountRepository accounts;
    @Autowired EncryptionService encryption;
    @Autowired JobFiles files;
    @Autowired JdbcTemplate jdbc;

    private VideoJob job;

    @BeforeEach
    void connectAccount() {
        accounts.save(new SocialAccount(Platform.TIKTOK, "acct-1", encryption.encrypt("token")));
        fake.uploadError.set(null);
        fake.crash = null;
        fake.draft = false;
        fake.playlistCalls.clear();
        fake.status = ProcessingResult.done();
        fake.statusCrash = null;
        fake.uploads.set(0);
    }

    @AfterEach
    void tearDown() throws Exception {
        posts.deleteAll();
        clips.deleteAll();
        jobs.deleteAll();
        accounts.deleteAll();
        if (job != null) FileSystemUtils.deleteRecursively(files.dataDir().resolve("jobs").resolve(job.getId().toString()));
    }

    /** A RENDERED job with {@code n} rendered clips, each with its clip.mp4 on disk. */
    private VideoJob renderedJob(int n) throws Exception {
        VideoJob j = jobs.save(new VideoJob("https://example.com/v", "es", "auto", false));
        jdbc.update("UPDATE video_job SET state = 'RENDERED', title = 'Mi vídeo' WHERE id = ?", j.getId());
        for (int i = 0; i < n; i++) {
            Clip c = clips.save(new Clip(j, i, List.of()));
            jdbc.update("UPDATE clip SET state = 'RENDERED' WHERE id = ?", c.getId());
            Path f = files.renderedClip(j.getId(), i);
            Files.createDirectories(f.getParent());
            Files.write(f, new byte[16]);
        }
        return jobs.findById(j.getId()).orElseThrow();
    }

    private VideoJobState jobState() { return jobs.findById(job.getId()).orElseThrow().getState(); }

    private List<ScheduledPost> jobPosts() { return posts.findByClipVideoJobId(job.getId()); }

    /** The publication of clip {@code index} of the job. */
    private ScheduledPost post(int index) {
        return posts.findWithClipByJobId(job.getId()).stream()
                .filter(p -> p.getClip().getClipIndex() == index).findFirst().orElseThrow();
    }

    /** The upload thread is asynchronous: wait for a condition, up to 5 s. */
    private static void await(Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.get() && System.currentTimeMillis() < deadline) Thread.sleep(25);
        assertThat(condition.get()).as("condition reached in time").isTrue();
    }

    @Test
    void rendered_job_is_published_on_every_connected_platform_and_completes() throws Exception {
        job = renderedJob(2);

        publications.planImmediate();
        assertThat(jobPosts()).hasSize(2).allMatch(p -> p.getState() == ScheduledPostState.SCHEDULED);
        assertThat(jobState()).isEqualTo(VideoJobState.SCHEDULED);

        // Part 2 waits until part 1 is out on the platform (DESIGN.md §11.3)
        publications.startDueUploads();
        assertThat(jobState()).isEqualTo(VideoJobState.PUBLISHING);
        await(() -> post(0).getState() == ScheduledPostState.PROCESSING);
        assertThat(post(1).getState()).isEqualTo(ScheduledPostState.SCHEDULED);
        assertThat(fake.lastTitle).startsWith("Mi vídeo ("); // fallback title: no Claude title on these clips

        publications.checkProcessing();
        await(() -> post(0).getState() == ScheduledPostState.PUBLISHED);
        publications.startDueUploads();
        await(() -> post(1).getState() == ScheduledPostState.PROCESSING);
        assertThat(jobPosts()).allMatch(p -> p.getExternalId() != null);
        publications.checkProcessing();
        await(() -> jobState() == VideoJobState.COMPLETED);
        assertThat(jobPosts()).allMatch(p -> p.getState() == ScheduledPostState.PUBLISHED);
        assertThat(clips.findByVideoJobIdOrderByClipIndex(job.getId()))
                .allMatch(c -> c.getState() == ClipState.PUBLISHED);
    }

    @Test
    void temporary_errors_are_retried_later_and_final_ones_fail_until_retried_by_hand() throws Exception {
        job = renderedJob(1);
        publications.planImmediate();

        fake.uploadError.set(new PublishException("timeout", true));
        publications.startDueUploads();
        await(() -> jobPosts().getFirst().getState() == ScheduledPostState.SCHEDULED
                && jobPosts().getFirst().getAttempts() == 1);
        ScheduledPost retried = jobPosts().getFirst();
        assertThat(retried.getScheduledAt()).isAfter(Instant.now());
        assertThat(retried.getErrorMessage()).isEqualTo("timeout");

        // Make it due again and fail for good
        jdbc.update("UPDATE scheduled_post SET scheduled_at = now() WHERE id = ?", retried.getId());
        fake.uploadError.set(new PublishException("rejected", false));
        publications.startDueUploads();
        await(() -> jobPosts().getFirst().getState() == ScheduledPostState.FAILED);
        assertThat(jobState()).as("a failed publication keeps the job open").isEqualTo(VideoJobState.PUBLISHING);

        fake.uploadError.set(null);
        assertThat(publications.retryFailedForJob(job.getId())).isEqualTo(1);
        assertThat(jobPosts().getFirst().getState()).isEqualTo(ScheduledPostState.SCHEDULED);
    }

    @Test
    void a_platform_limit_postpones_an_hour_without_spending_an_attempt() throws Exception {
        job = renderedJob(1);
        publications.planImmediate();

        fake.uploadError.set(PublishException.limitReached("demasiados borradores", Duration.ofHours(1)));
        publications.startDueUploads();
        await(() -> jobPosts().getFirst().getState() == ScheduledPostState.SCHEDULED
                && "demasiados borradores".equals(jobPosts().getFirst().getErrorMessage()));
        ScheduledPost postponed = jobPosts().getFirst();
        assertThat(postponed.getAttempts()).isZero();
        assertThat(postponed.getScheduledAt()).isAfter(Instant.now().plus(Duration.ofMinutes(59)));
    }

    @Test
    void nothing_is_planned_without_a_connected_account() throws Exception {
        accounts.deleteAll();
        job = renderedJob(1);

        publications.planImmediate();

        assertThat(jobPosts()).isEmpty();
        assertThat(jobState()).isEqualTo(VideoJobState.RENDERED);
    }

    @Test
    void after_a_restart_an_upload_without_id_fails_and_one_with_id_is_checked_again() throws Exception {
        job = renderedJob(2);
        List<Clip> jobClips = clips.findByVideoJobIdOrderByClipIndex(job.getId());
        ScheduledPost noId = posts.save(new ScheduledPost(jobClips.get(0), Platform.TIKTOK, Instant.now(), ScheduledPostOrigin.IMMEDIATE));
        ScheduledPost withId = posts.save(new ScheduledPost(jobClips.get(1), Platform.TIKTOK, Instant.now(), ScheduledPostOrigin.IMMEDIATE));
        jdbc.update("UPDATE scheduled_post SET state = 'UPLOADING' WHERE id IN (?, ?)", noId.getId(), withId.getId());
        jdbc.update("UPDATE scheduled_post SET external_id = 'ext-known' WHERE id = ?", withId.getId());

        publications.recoverInterruptedUploads();

        assertThat(posts.findById(noId.getId()).orElseThrow().getState()).isEqualTo(ScheduledPostState.FAILED);
        assertThat(posts.findById(withId.getId()).orElseThrow().getState()).isEqualTo(ScheduledPostState.PROCESSING);
    }

    @Test
    void an_error_after_the_upload_never_uploads_the_video_again() throws Exception {
        job = renderedJob(1);
        fake.status = ProcessingResult.processing();
        publications.planImmediate();
        publications.startDueUploads();
        await(() -> jobPosts().getFirst().getState() == ScheduledPostState.PROCESSING);

        fake.statusCrash = new IllegalStateException("token store down");
        publications.checkProcessing();
        Thread.sleep(300);
        publications.startDueUploads();
        Thread.sleep(300);

        ScheduledPost post = jobPosts().getFirst();
        assertThat(post.getState()).isEqualTo(ScheduledPostState.PROCESSING);
        assertThat(post.getAttempts()).isZero();
        assertThat(fake.uploads.get()).as("uploaded once").isEqualTo(1);
    }

    @Test
    void a_publication_still_processing_after_a_day_fails() throws Exception {
        job = renderedJob(1);
        ScheduledPost stuck = posts.save(new ScheduledPost(clips.findByVideoJobIdOrderByClipIndex(job.getId()).getFirst(),
                Platform.TIKTOK, Instant.now(), ScheduledPostOrigin.IMMEDIATE));
        jdbc.update("UPDATE scheduled_post SET state = 'PROCESSING', external_id = 'ext-old', "
                + "updated_at = now() - interval '25 hours' WHERE id = ?", stuck.getId());
        fake.status = ProcessingResult.processing();

        publications.checkProcessing();

        await(() -> posts.findById(stuck.getId()).orElseThrow().getState() == ScheduledPostState.FAILED);
        assertThat(posts.findById(stuck.getId()).orElseThrow().getErrorMessage()).contains("24 h");
    }

    @Test
    void an_old_publication_the_platform_finished_meanwhile_is_published_not_failed() throws Exception {
        job = renderedJob(1);
        ScheduledPost late = posts.save(new ScheduledPost(clips.findByVideoJobIdOrderByClipIndex(job.getId()).getFirst(),
                Platform.TIKTOK, Instant.now(), ScheduledPostOrigin.IMMEDIATE));
        jdbc.update("UPDATE scheduled_post SET state = 'PROCESSING', external_id = 'ext-late', "
                + "updated_at = now() - interval '30 hours' WHERE id = ?", late.getId());
        fake.status = ProcessingResult.done();

        publications.checkProcessing();

        await(() -> posts.findById(late.getId()).orElseThrow().getState() == ScheduledPostState.PUBLISHED);
    }

    @Test
    void retry_delay_grows_and_caps_at_an_hour() {
        assertThat(PublicationService.backoff(0).toMinutes()).isEqualTo(1);
        assertThat(PublicationService.backoff(3).toMinutes()).isEqualTo(8);
        assertThat(PublicationService.backoff(20).toMinutes()).isEqualTo(60);
    }

    @Test
    void an_upload_that_crashes_is_logged_and_retried_not_left_frozen() throws Exception {
        job = renderedJob(1);
        fake.crash = new AssertionError("boom");
        publications.planImmediate();

        publications.startDueUploads();

        await(() -> jobPosts().getFirst().getState() == ScheduledPostState.SCHEDULED);
        ScheduledPost post = jobPosts().getFirst();
        assertThat(post.getAttempts()).isEqualTo(1);
        assertThat(post.getErrorMessage()).contains("boom");
    }

    @Test
    void an_upload_left_uploading_with_nothing_running_is_recovered() throws Exception {
        job = renderedJob(1);
        Clip clip = clips.findByVideoJobIdOrderByClipIndex(job.getId()).getFirst();
        ScheduledPost post = posts.save(new ScheduledPost(clip, Platform.TIKTOK, Instant.now(), ScheduledPostOrigin.MANUAL));
        jdbc.update("UPDATE scheduled_post SET state = 'UPLOADING', updated_at = now() - interval '10 minutes' WHERE id = ?",
                post.getId());

        publications.recoverOrphanUploads();

        assertThat(posts.findById(post.getId()).orElseThrow().getState()).isEqualTo(ScheduledPostState.FAILED);
    }

    @Test
    void a_publication_waiting_out_a_limit_shows_as_postponed() throws Exception {
        job = renderedJob(1);
        fake.uploadError.set(PublishException.limitReached("TikTok: hay demasiados borradores", Duration.ofHours(1)));
        publications.planImmediate();

        publications.startDueUploads();

        await(() -> jobPosts().getFirst().isPostponed());
        ScheduledPost post = jobPosts().getFirst();
        assertThat(post.getState()).isEqualTo(ScheduledPostState.SCHEDULED);
        assertThat(post.getAttempts()).isZero();
        assertThat(publications.stateLabel(post)).isEqualTo("APLAZADA");
    }

    @Test
    void a_postponed_part_holds_the_next_one_back_and_can_be_marked_as_uploaded_by_hand() throws Exception {
        job = renderedJob(2);
        fake.uploadError.set(PublishException.limitReached("TikTok: hay demasiados borradores", Duration.ofHours(1)));
        publications.planImmediate();

        publications.startDueUploads();
        await(() -> post(0).isPostponed());
        // Part 2 is due too, but part 1 goes first
        fake.uploadError.set(null);
        publications.startDueUploads();
        assertThat(post(1).getState()).isEqualTo(ScheduledPostState.SCHEDULED);

        // You upload part 1 yourself and paste its link: it stops being tried, and part 2 can go
        assertThat(publications.markUploadedByHand(post(0).getId(), "https://www.tiktok.com/@me/video/123")).isTrue();
        ScheduledPost first = post(0);
        assertThat(first.getState()).isEqualTo(ScheduledPostState.PUBLISHED);
        assertThat(first.isPublishedManually()).isTrue();
        assertThat(first.getExternalUrl()).isEqualTo("https://www.tiktok.com/@me/video/123");
        assertThat(publications.stateLabel(first)).isEqualTo("SUBIDA A MANO");

        publications.startDueUploads();
        await(() -> post(1).getState() == ScheduledPostState.PROCESSING);
    }

    @Test
    void the_video_id_comes_out_of_a_youtube_link() {
        assertThat(PublicationService.youtubeId("https://youtube.com/shorts/dQw4w9WgXcQ?feature=share")).isEqualTo("dQw4w9WgXcQ");
        assertThat(PublicationService.youtubeId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=3")).isEqualTo("dQw4w9WgXcQ");
        assertThat(PublicationService.youtubeId("https://youtu.be/dQw4w9WgXcQ")).isEqualTo("dQw4w9WgXcQ");
        assertThat(PublicationService.youtubeId("https://www.instagram.com/reel/abc/")).isNull();
        assertThat(PublicationService.youtubeId(null)).isNull();
    }

    @Test
    void a_videos_clips_go_into_one_playlist_in_order() throws Exception {
        job = renderedJob(2);
        publications.planImmediate();

        publications.startDueUploads();
        await(() -> post(0).getState() == ScheduledPostState.PROCESSING);
        publications.checkProcessing();
        await(() -> post(0).getState() == ScheduledPostState.PUBLISHED);
        publications.startDueUploads();
        await(() -> post(1).getState() == ScheduledPostState.PROCESSING);

        String first = post(0).getExternalId();
        String second = post(1).getExternalId();
        await(() -> fake.playlistCalls.size() == 2);
        // The first clip creates the playlist (named after the video), the second goes into it
        assertThat(fake.playlistCalls).containsExactly("null|" + first, "PL-Mi vídeo|" + second);
        assertThat(jobs.findById(job.getId()).orElseThrow().getPlaylistIdsJson()).containsEntry("TIKTOK", "PL-Mi vídeo");
    }

    @Test
    void a_draft_waits_for_your_confirmation_before_the_job_completes() throws Exception {
        job = renderedJob(1);
        fake.draft = true;
        publications.planImmediate();

        publications.startDueUploads();
        await(() -> post(0).getState() == ScheduledPostState.PROCESSING);
        publications.checkProcessing();
        await(() -> post(0).getState() == ScheduledPostState.PUBLISHED);

        assertThat(post(0).isAwaitingConfirmation()).isTrue();
        assertThat(publications.stateLabel(post(0))).isEqualTo("EN BORRADORES · FALTA PUBLICAR");
        assertThat(posts.findAwaitingConfirmation()).extracting(ScheduledPost::getId).containsExactly(post(0).getId());
        assertThat(jobState()).as("not finished until you confirm").isNotEqualTo(VideoJobState.COMPLETED);

        assertThat(publications.confirmPublished(post(0).getId(), "https://www.tiktok.com/@yo/video/1")).isTrue();
        assertThat(post(0).isAwaitingConfirmation()).isFalse();
        assertThat(publications.publicUrl(post(0))).isEqualTo("https://www.tiktok.com/@yo/video/1");
        assertThat(jobState()).isEqualTo(VideoJobState.COMPLETED);
        assertThat(publications.confirmPublished(post(0).getId(), null)).as("only once").isFalse();
    }

    @Test
    void a_lost_draft_is_sent_again_while_its_file_exists() throws Exception {
        job = renderedJob(1);
        fake.draft = true;
        publications.planImmediate();
        publications.startDueUploads();
        await(() -> post(0).getState() == ScheduledPostState.PROCESSING);
        publications.checkProcessing();
        await(() -> post(0).getState() == ScheduledPostState.PUBLISHED);
        String firstId = post(0).getExternalId();

        assertThat(publications.canResend(post(0))).isTrue();
        assertThat(publications.resendDraft(post(0).getId())).isTrue();
        assertThat(post(0).getState()).isEqualTo(ScheduledPostState.SCHEDULED);
        assertThat(post(0).isAwaitingConfirmation()).isFalse();
        assertThat(clips.findById(post(0).getClip().getId()).orElseThrow().getState()).isEqualTo(ClipState.SCHEDULED);

        publications.startDueUploads();
        await(() -> post(0).getState() == ScheduledPostState.PROCESSING);
        publications.checkProcessing();
        await(() -> post(0).getState() == ScheduledPostState.PUBLISHED);
        assertThat(post(0).isAwaitingConfirmation()).isTrue();
        assertThat(post(0).getExternalId()).isEqualTo(firstId); // the fake reuses the id; a real resend gets a new one

        // Without the file there's nothing to send
        Files.delete(files.renderedClip(job.getId(), 0));
        assertThat(publications.canResend(post(0))).isFalse();
        assertThat(publications.resendDraft(post(0).getId())).isFalse();
    }

    @Test
    void a_platform_that_publishes_directly_needs_no_confirmation() throws Exception {
        job = renderedJob(1);
        publications.planImmediate();
        publications.startDueUploads();
        await(() -> post(0).getState() == ScheduledPostState.PROCESSING);
        publications.checkProcessing();
        await(() -> post(0).getState() == ScheduledPostState.PUBLISHED);

        assertThat(post(0).isAwaitingConfirmation()).isFalse();
        assertThat(publications.canResend(post(0))).isFalse();
        assertThat(jobState()).isEqualTo(VideoJobState.COMPLETED);
    }
}
