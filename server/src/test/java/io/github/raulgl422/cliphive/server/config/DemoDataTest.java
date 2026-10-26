package io.github.raulgl422.cliphive.server.config;

import io.github.raulgl422.cliphive.server.model.enums.Platform;
import io.github.raulgl422.cliphive.server.publish.StatsDashboard;
import io.github.raulgl422.cliphive.server.repository.ClipRepository;
import io.github.raulgl422.cliphive.server.repository.LogEventRepository;
import io.github.raulgl422.cliphive.server.repository.ScheduledPostRepository;
import io.github.raulgl422.cliphive.server.repository.SocialAccountRepository;
import io.github.raulgl422.cliphive.server.repository.TaskRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import io.github.raulgl422.cliphive.server.repository.WorkerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code demo} profile (DESIGN.md §13.1) loads its made-up data on top of
 * the real migrations and keeps every schedule that could act on it off.
 * Loading each entity through JPA catches a state or column the seed no
 * longer matches after a schema change.
 */
@SpringBootTest
@ActiveProfiles({"embedded", "demo"})
class DemoDataTest {

    @Autowired VideoJobRepository jobs;
    @Autowired ClipRepository clips;
    @Autowired TaskRepository tasks;
    @Autowired ScheduledPostRepository posts;
    @Autowired SocialAccountRepository accounts;
    @Autowired WorkerRepository workers;
    @Autowired LogEventRepository logs;
    @Autowired StatsDashboard stats;
    @Autowired Environment env;

    @Test
    void the_demo_data_loads_as_entities() {
        assertThat(jobs.findAll()).hasSize(5);
        assertThat(clips.findAll()).hasSize(9);
        assertThat(tasks.findAll()).hasSize(5);
        assertThat(posts.findAll()).hasSize(20);
        assertThat(accounts.findAll()).hasSize(3);
        assertThat(workers.findAll()).hasSize(2);
        assertThat(logs.findAll()).isNotEmpty();
    }

    @Test
    void the_statistics_page_has_something_to_show() {
        StatsDashboard.Dashboard youtube = stats.build(Platform.YOUTUBE, "views");
        assertThat(youtube.accountName()).isEqualTo("Canal de ejemplo");
        assertThat(youtube.kpis().clipsPublished()).isEqualTo(5);
        assertThat(youtube.kpis().subscribers()).isNotNull();
        assertThat(youtube.subscribers()).isNotEmpty();
    }

    @Test
    void no_schedule_can_act_on_the_made_up_data() {
        for (String schedule : new String[]{"planner", "publishing", "stats", "alerts", "cleanup"}) {
            assertThat(env.getProperty("cliphive." + schedule + ".scheduler-enabled")).as(schedule).isEqualTo("false");
        }
    }
}
