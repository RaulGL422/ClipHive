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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Builds what the statistics page shows (DESIGN.md §12.4) from the stored snapshots. */
@Service
public class StatsDashboard {

    static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    static final int TOP_N = 10;
    static final Duration SERIES_WINDOW = Duration.ofDays(90);

    /** One published clip with its latest metrics. Metrics are null until collected. */
    public record PostRow(UUID postId, UUID jobId, String title, Platform platform, Instant publishedAt,
                          Long views, Long likes, Long comments, Double averageViewPercentage,
                          Double averageViewDurationSeconds, Double minutesWatched, Long subscribersGained,
                          String url, Instant capturedAt) {}

    public record Kpis(Long subscribers, Long subscribersChange7d, Long channelViews,
                       int clipsPublished, Long averageViewsPerClip) {}

    public record Point(long t, double v) {}

    public record HourBucket(int hour, double averageViews, int clips) {}

    public record Dashboard(String accountName, Kpis kpis, List<Point> subscribers,
                            List<PostRow> top, List<HourBucket> byHour, List<PostRow> rows) {}

    /** Sort keys for the table, each sorting highest first. */
    public static final Map<String, Function<PostRow, Comparable<?>>> SORTS = Map.of(
            "views", PostRow::views,
            "likes", PostRow::likes,
            "comments", PostRow::comments,
            "retention", PostRow::averageViewPercentage,
            "subscribers", PostRow::subscribersGained,
            "published", PostRow::publishedAt);

    private final ScheduledPostRepository posts;
    private final PostStatsRepository postStats;
    private final AccountStatsRepository accountStats;
    private final SocialAccountService accounts;
    private final PublicationService publications;

    public StatsDashboard(ScheduledPostRepository posts, PostStatsRepository postStats,
                          AccountStatsRepository accountStats, SocialAccountService accounts,
                          PublicationService publications) {
        this.posts = posts;
        this.postStats = postStats;
        this.accountStats = accountStats;
        this.accounts = accounts;
        this.publications = publications;
    }

    @Transactional(readOnly = true)
    public Dashboard build(Platform platform, String sort) {
        List<PostRow> rows = rows(platform);
        rows.sort(descendingBy(SORTS.getOrDefault(sort, SORTS.get("views"))));
        List<PostRow> top = rows.stream().filter(r -> r.views() != null)
                .sorted(descendingBy(SORTS.get("views"))).limit(TOP_N).toList();

        SocialAccount account = accounts.accountFor(platform).orElse(null);
        List<Point> subscribers = new ArrayList<>();
        Long subs = null, change = null, channelViews = null;
        if (account != null) {
            Instant now = Instant.now();
            for (AccountStats s : accountStats.findBySocialAccountIdAndCapturedAtAfterOrderByCapturedAtAsc(
                    account.getId(), now.minus(SERIES_WINDOW))) {
                Long v = asLong(s.getMetricsJson().get("subscribers"));
                if (v != null) subscribers.add(new Point(s.getCapturedAt().toEpochMilli(), v));
            }
            Map<String, Object> current = accountStats.findTopBySocialAccountIdOrderByCapturedAtDesc(account.getId())
                    .map(AccountStats::getMetricsJson).orElse(Map.of());
            subs = asLong(current.get("subscribers"));
            channelViews = asLong(current.get("views"));
            Long weekAgo = accountStats.findTopBySocialAccountIdAndCapturedAtBeforeOrderByCapturedAtDesc(
                            account.getId(), now.minus(Duration.ofDays(7)))
                    .map(s -> asLong(s.getMetricsJson().get("subscribers"))).orElse(null);
            if (subs != null && weekAgo != null) change = subs - weekAgo;
        }

        long withViews = rows.stream().filter(r -> r.views() != null).count();
        Long avgViews = withViews == 0 ? null
                : Math.round(rows.stream().filter(r -> r.views() != null).mapToLong(PostRow::views).sum() / (double) withViews);
        Kpis kpis = new Kpis(subs, change, channelViews, rows.size(), avgViews);

        return new Dashboard(account != null ? account.getDisplayName() : null,
                kpis, subscribers, top, byHour(rows), rows);
    }

    /** Average views by publishing hour on {@code platform} — what the planner uses (§10.4). */
    @Transactional(readOnly = true)
    public List<HourBucket> byHour(Platform platform) {
        return byHour(rows(platform));
    }

    /** One row per PUBLISHED publication on {@code platform}, with its latest metrics. */
    private List<PostRow> rows(Platform platform) {
        Map<UUID, Map<String, Object>> latest = new HashMap<>();
        Map<UUID, Instant> captured = new HashMap<>();
        for (PostStats s : postStats.findLatestPerPost()) {
            latest.put(s.getScheduledPost().getId(), s.getMetricsJson());
            captured.put(s.getScheduledPost().getId(), s.getCapturedAt());
        }

        List<PostRow> rows = new ArrayList<>();
        for (ScheduledPost p : posts.findWithClipByStateIn(EnumSet.of(ScheduledPostState.PUBLISHED))) {
            if (p.getPlatform() != platform) continue;
            Map<String, Object> m = latest.getOrDefault(p.getId(), Map.of());
            String title = p.getClip().getTitle() != null ? p.getClip().getTitle()
                    : "Clip " + (p.getClip().getClipIndex() + 1);
            rows.add(new PostRow(p.getId(), p.getClip().getVideoJob().getId(), title, p.getPlatform(),
                    p.getPublishedAt(), asLong(m.get("views")), asLong(m.get("likes")), asLong(m.get("comments")),
                    asDouble(m.get("averageViewPercentage")), asDouble(m.get("averageViewDurationSeconds")),
                    asDouble(m.get("minutesWatched")), asLong(m.get("subscribersGained")),
                    publications.publicUrl(p), captured.get(p.getId())));
        }
        return rows;
    }

    /** Average views of the clips published at each hour (Madrid), for the hours that have any. */
    static List<HourBucket> byHour(List<PostRow> rows) {
        long[] sum = new long[24];
        int[] count = new int[24];
        for (PostRow r : rows) {
            if (r.publishedAt() == null || r.views() == null) continue;
            int h = r.publishedAt().atZone(MADRID).getHour();
            sum[h] += r.views();
            count[h]++;
        }
        List<HourBucket> buckets = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            if (count[h] > 0) buckets.add(new HourBucket(h, sum[h] / (double) count[h], count[h]));
        }
        return buckets;
    }

    /** Highest first; rows without the metric go last. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Comparator<PostRow> descendingBy(Function<PostRow, Comparable<?>> key) {
        return (a, b) -> {
            Comparable ka = key.apply(a), kb = key.apply(b);
            if (ka == null && kb == null) return 0;
            if (ka == null) return 1;
            if (kb == null) return -1;
            return kb.compareTo(ka);
        };
    }

    private static Long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }

    private static Double asDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }
}
