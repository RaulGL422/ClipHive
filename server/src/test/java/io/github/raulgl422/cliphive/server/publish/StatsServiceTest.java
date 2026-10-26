package io.github.raulgl422.cliphive.server.publish;

import io.github.raulgl422.cliphive.server.model.Clip;
import io.github.raulgl422.cliphive.server.model.ScheduledPost;
import io.github.raulgl422.cliphive.server.model.SocialAccount;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.model.enums.Platform;
import io.github.raulgl422.cliphive.server.model.enums.ScheduledPostOrigin;
import io.github.raulgl422.cliphive.server.repository.AccountStatsRepository;
import io.github.raulgl422.cliphive.server.repository.ClipRepository;
import io.github.raulgl422.cliphive.server.repository.PostStatsRepository;
import io.github.raulgl422.cliphive.server.repository.ScheduledPostRepository;
import io.github.raulgl422.cliphive.server.repository.SocialAccountRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import io.github.raulgl422.cliphive.server.security.EncryptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stats collection cadence and the dashboard (DESIGN.md §12.4) on the
 * embedded DB, with a fake collector registered under the real TikTok
 * collector's bean name (so it replaces it) and the scheduler switched off.
 */
@SpringBootTest(properties = {"cliphive.stats.scheduler-enabled=false", "cliphive.publishing.scheduler-enabled=false",
        "spring.main.allow-bean-definition-overriding=true"})
@ActiveProfiles("embedded")
class StatsServiceTest {

    static class FakeCollector implements StatsCollector {
        final AtomicInteger postCalls = new AtomicInteger();
        @Override public Platform platform() { return Platform.TIKTOK; }
        @Override public Map<String, Object> postMetrics(String token, String id, LocalDate publishedOn) {
            postCalls.incrementAndGet();
            return Map.of("views", id.equals("v-hot") ? 900L : 100L, "averageViewPercentage", 55.0);
        }
        @Override public Map<String, Object> accountMetrics(String token) {
            return Map.of("subscribers", 120L, "views", 5000L);
        }
    }

    @TestConfiguration
    static class Config {
        @Bean FakeCollector tikTokStatsCollector() { return new FakeCollector(); }
    }

    @Autowired StatsService stats;
    @Autowired StatsDashboard dashboard;
    @Autowired FakeCollector fake;
    @Autowired ScheduledPostRepository posts;
    @Autowired PostStatsRepository postStats;
    @Autowired AccountStatsRepository accountStats;
    @Autowired SocialAccountRepository accounts;
    @Autowired ClipRepository clips;
    @Autowired VideoJobRepository jobs;
    @Autowired EncryptionService encryption;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext wac;

    private VideoJob job;

    @BeforeEach
    void setUp() {
        accounts.save(new SocialAccount(Platform.TIKTOK, "acct", encryption.encrypt("tok")));
        job = jobs.save(new VideoJob("https://example.com/v", "es", "auto", false));
        fake.postCalls.set(0);
    }

    @AfterEach
    void tearDown() {
        postStats.deleteAll();
        accountStats.deleteAll();
        posts.deleteAll();
        clips.deleteAll();
        jobs.deleteAll();
        accounts.deleteAll();
    }

    /** A PUBLISHED post published at {@code publishedAt}. */
    private ScheduledPost published(int index, String externalId, Instant publishedAt) {
        Clip clip = clips.save(new Clip(job, index, List.of()));
        jdbc.update("UPDATE clip SET title = ? WHERE id = ?", "Clip " + externalId, clip.getId());
        ScheduledPost p = posts.save(new ScheduledPost(clip, Platform.TIKTOK, publishedAt, ScheduledPostOrigin.IMMEDIATE));
        jdbc.update("UPDATE scheduled_post SET state = 'PUBLISHED', external_id = ?, published_at = ? WHERE id = ?",
                externalId, Timestamp.from(publishedAt), p.getId());
        return p;
    }

    @Test
    void collection_slows_down_after_a_week_and_stops_after_90_days() {
        assertThat(StatsService.intervalFor(Duration.ofDays(1))).isEqualTo(Duration.ofHours(6));
        assertThat(StatsService.intervalFor(Duration.ofDays(30))).isEqualTo(Duration.ofHours(24));
        assertThat(StatsService.intervalFor(Duration.ofDays(91))).isNull();
    }

    @Test
    void due_publications_and_accounts_are_collected_once_per_interval() {
        published(0, "v-new", Instant.now().minus(Duration.ofHours(2)));
        published(1, "v-ancient", Instant.now().minus(Duration.ofDays(120)));

        stats.collectDue();
        assertThat(fake.postCalls.get()).as("only the recent one").isEqualTo(1);
        assertThat(accountStats.count()).isEqualTo(1);

        stats.collectDue();
        assertThat(fake.postCalls.get()).as("not due again within 6 h").isEqualTo(1);
        assertThat(accountStats.count()).isEqualTo(1);
    }

    @Test
    void dashboard_ranks_clips_and_averages_views_by_publication_hour() {
        Instant at13 = ZonedDateTime.of(2026, 10, 5, 13, 10, 0, 0, ZoneId.of("Europe/Madrid")).toInstant();
        Instant at20 = ZonedDateTime.of(2026, 10, 5, 20, 0, 0, 0, ZoneId.of("Europe/Madrid")).toInstant();
        published(0, "v-cold", at13);
        published(1, "v-hot", at20);
        // Old enough to be outside the first week but within 90 days → collected once
        jdbc.update("UPDATE scheduled_post SET published_at = now() - interval '10 days'");
        stats.collectDue();
        jdbc.update("UPDATE scheduled_post SET published_at = ? WHERE external_id = 'v-cold'", Timestamp.from(at13));
        jdbc.update("UPDATE scheduled_post SET published_at = ? WHERE external_id = 'v-hot'", Timestamp.from(at20));

        StatsDashboard.Dashboard d = dashboard.build(Platform.TIKTOK, "views");

        assertThat(d.rows()).extracting(StatsDashboard.PostRow::title).containsExactly("Clip v-hot", "Clip v-cold");
        assertThat(d.top().getFirst().views()).isEqualTo(900L);
        assertThat(d.byHour()).extracting(StatsDashboard.HourBucket::hour).containsExactly(13, 20);
        assertThat(d.kpis().subscribers()).isEqualTo(120L);
        assertThat(d.kpis().averageViewsPerClip()).isEqualTo(500L);
    }

    @Test
    void stats_page_renders() throws Exception {
        // Only a TikTok account in this test: no tab with stats, so it offers to connect one
        MockMvcBuilders.webAppContextSetup(wac).build()
                .perform(get("/admin/stats").requestAttr("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "t")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Conectar una cuenta")));
    }
}
