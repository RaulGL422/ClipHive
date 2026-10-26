package io.github.raulgl422.cliphive.server.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.github.raulgl422.cliphive.server.security.LoginAttemptService.MAX_ATTEMPTS;
import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptServiceTest {

    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        service = new LoginAttemptService();
    }

    @Test
    void fresh_ip_is_not_blocked() {
        assertThat(service.isBlocked("1.2.3.4")).isFalse();
    }

    @Test
    void below_threshold_does_not_block() {
        for (int i = 0; i < MAX_ATTEMPTS - 1; i++) {
            service.tryAcquire("1.2.3.4");
        }
        assertThat(service.isBlocked("1.2.3.4")).isFalse();
    }

    @Test
    void reaching_threshold_blocks_ip() {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            service.tryAcquire("1.2.3.4");
        }
        assertThat(service.isBlocked("1.2.3.4")).isTrue();
    }

    @Test
    void success_clears_block() {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            service.tryAcquire("1.2.3.4");
        }
        assertThat(service.isBlocked("1.2.3.4")).isTrue();

        service.recordSuccess("1.2.3.4");
        assertThat(service.isBlocked("1.2.3.4")).isFalse();
    }

    @Test
    void different_ips_are_tracked_independently() {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            service.tryAcquire("10.0.0.1");
        }
        assertThat(service.isBlocked("10.0.0.1")).isTrue();
        assertThat(service.isBlocked("10.0.0.2")).isFalse();
    }

    @Test
    void a_blocked_key_gets_no_more_tries() {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            assertThat(service.tryAcquire("1.2.3.4")).isTrue();
        }
        assertThat(service.tryAcquire("1.2.3.4")).isFalse();
    }

    @Test
    void a_burst_of_parallel_tries_still_gets_only_the_limit() throws Exception {
        try (var pool = Executors.newFixedThreadPool(16)) {
            List<Future<Boolean>> tries = new ArrayList<>();
            for (int i = 0; i < 200; i++) tries.add(pool.submit(() -> service.tryAcquire("1.2.3.4")));
            long allowed = 0;
            for (Future<Boolean> t : tries) if (t.get()) allowed++;
            assertThat(allowed).isEqualTo(MAX_ATTEMPTS);
        }
    }

    @Test
    void success_on_untracked_ip_does_not_throw() {
        service.recordSuccess("9.9.9.9");
        assertThat(service.isBlocked("9.9.9.9")).isFalse();
    }
}
