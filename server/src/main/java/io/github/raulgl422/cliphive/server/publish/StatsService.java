package io.github.raulgl422.cliphive.server.publish;

import io.github.raulgl422.cliphive.protocol.state.ScheduledPostState;
import io.github.raulgl422.cliphive.server.model.AccountStats;
import io.github.raulgl422.cliphive.server.model.PostStats;
import io.github.raulgl422.cliphive.server.model.ScheduledPost;
import io.github.raulgl422.cliphive.server.model.SocialAccount;
import io.github.raulgl422.cliphive.server.model.enums.Platform;
import io.github.raulgl422.cliphive.server.repository.AccountStatsRepository;
import io.github.raulgl422.cliphive.server.repository.PostStatsRepository;
import io.github.raulgl422.cliphive.server.repository.ScheduledPostRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Collects publication and account metrics (DESIGN.md §4 step 14, §12.4):
 * every 6 h during a video's first week, every 24 h up to 90 days, then no
 * more. Network calls run on their own thread, like uploads.
 */
@Service
public class StatsService {

    private static final Logger log = LoggerFactory.getLogger(StatsService.class);

    static final Duration FIRST_WEEK = Duration.ofDays(7);
    static final Duration STOP_AFTER = Duration.ofDays(90);
    static final Duration EARLY_INTERVAL = Duration.ofHours(6);
    static final Duration LATE_INTERVAL = Duration.ofHours(24);
    static final Duration ACCOUNT_INTERVAL = Duration.ofHours(6);

    private final ScheduledPostRepository posts;
    private final PostStatsRepository postStats;
    private final AccountStatsRepository accountStats;
    private final SocialAccountService accounts;
    private final Map<Platform, StatsCollector> collectors = new EnumMap<>(Platform.class);
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("stats").daemon().factory());
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Off only in tests that drive collection by hand. */
    @Value("${cliphive.stats.scheduler-enabled:true}")
    private boolean schedulerEnabled = true;

    public StatsService(ScheduledPostRepository posts,
                        PostStatsRepository postStats,
                        AccountStatsRepository accountStats,
                        SocialAccountService accounts,
                        List<StatsCollector> collectors) {
        this.posts = posts;
        this.postStats = postStats;
        this.accountStats = accountStats;
        this.accounts = accounts;
        collectors.forEach(c -> this.collectors.put(c.platform(), c));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @Scheduled(initialDelay = 120_000, fixedDelay = 3_600_000)
    public void tick() {
        if (!schedulerEnabled) return;
        executor.submit(this::collectDue);
    }

    /**
     * How long to wait between two snapshots of a publication this old, or
     * null when it's too old to keep collecting.
     */
    static Duration intervalFor(Duration age) {
        if (age.compareTo(STOP_AFTER) > 0) return null;
        return age.compareTo(FIRST_WEEK) < 0 ? EARLY_INTERVAL : LATE_INTERVAL;
    }

    /** One collection round: every publication and account whose snapshot is due. */
    public void collectDue() {
        if (!running.compareAndSet(false, true)) return; // a slow round is still going
        try {
            Instant now = Instant.now();
            int collected = 0;
            // Older ones aren't collected any more (§12.4): don't load them at all
            for (ScheduledPost post : posts.findByStateAndPublishedAtAfter(ScheduledPostState.PUBLISHED,
                    now.minus(STOP_AFTER))) {
                if (collectPostIfDue(post, now)) collected++;
            }
            for (SocialAccount account : accounts.all()) {
                collectAccountIfDue(account, now);
            }
            if (collected > 0) log.info("Statistics collected for {} publication(s)", collected);
        } finally {
            running.set(false);
        }
    }

    private boolean collectPostIfDue(ScheduledPost post, Instant now) {
        StatsCollector collector = collectors.get(post.getPlatform());
        if (collector == null || post.getExternalId() == null || post.getPublishedAt() == null) return false;

        Duration interval = intervalFor(Duration.between(post.getPublishedAt(), now));
        if (interval == null) return false;
        Instant last = postStats.findTopByScheduledPostIdOrderByCapturedAtDesc(post.getId())
                .map(PostStats::getCapturedAt).orElse(null);
        if (last != null && Duration.between(last, now).compareTo(interval) < 0) return false;

        try {
            SocialAccount account = accounts.accountFor(post.getPlatform()).orElse(null);
            if (account == null) return false;
            Map<String, Object> metrics = collector.postMetrics(accounts.accessToken(account),
                    post.getExternalId(), post.getPublishedAt().atZone(ZoneOffset.UTC).toLocalDate());
            if (metrics.isEmpty()) return false; // e.g. TikTok drafts: no per-post metrics
            postStats.save(new PostStats(post, metrics));
            return true;
        } catch (PublishException | RuntimeException e) {
            log.warn("Could not collect stats for publication {}: {}", post.getId(), e.getMessage());
            return false;
        }
    }

    private void collectAccountIfDue(SocialAccount account, Instant now) {
        StatsCollector collector = collectors.get(account.getPlatform());
        if (collector == null) return;
        Instant last = accountStats.findTopBySocialAccountIdOrderByCapturedAtDesc(account.getId())
                .map(AccountStats::getCapturedAt).orElse(null);
        if (last != null && Duration.between(last, now).compareTo(ACCOUNT_INTERVAL) < 0) return;
        try {
            accountStats.save(new AccountStats(account, collector.accountMetrics(accounts.accessToken(account))));
        } catch (PublishException | RuntimeException e) {
            log.warn("Could not collect stats for {} account: {}", account.getPlatform(), e.getMessage());
        }
    }
}
