package io.github.raulgl422.cliphive.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The startup configuration check (DESIGN.md §13.1). */
class StartupConfigCheckTest {

    private static final String KEY_32_BYTES = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    /** A complete production configuration. */
    private static MockEnvironment complete() {
        return new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/cliphive")
                .withProperty("spring.datasource.username", "cliphive")
                .withProperty("cliphive.data-dir", Path.of("/var/lib/cliphive").toAbsolutePath().toString())
                .withProperty("cliphive.url-signing-key", "x".repeat(64))
                .withProperty("cliphive.encryption-key", KEY_32_BYTES)
                .withProperty("cliphive.admin-public-url", "https://cliphive.example.com")
                .withProperty("cliphive.media-public-url", "https://cliphive-media.example.com")
                .withProperty("cliphive.operator-name", "Op")
                .withProperty("cliphive.operator-email", "op@example.com");
    }

    @Test
    void a_complete_configuration_has_no_findings() {
        var f = StartupConfigCheck.check(complete());
        assertThat(f.errors()).isEmpty();
        assertThat(f.warnings()).isEmpty();
    }

    @Test
    void an_empty_configuration_lists_every_required_variable() {
        var f = StartupConfigCheck.check(new MockEnvironment());
        assertThat(String.join("\n", f.errors()))
                .contains("CLIPHIVE_DB_URL", "CLIPHIVE_DB_USER", "CLIPHIVE_DATA_DIR",
                          "CLIPHIVE_URL_SIGNING_KEY", "CLIPHIVE_ENCRYPTION_KEY");
        assertThat(String.join("\n", f.warnings()))
                .contains("CLIPHIVE_ADMIN_PUBLIC_URL", "CLIPHIVE_MEDIA_PUBLIC_URL", "CLIPHIVE_OPERATOR_NAME");
    }

    @Test
    void malformed_values_are_errors() {
        var env = complete()
                .withProperty("spring.datasource.url", "jdbc:mysql://x/db")
                .withProperty("cliphive.data-dir", "data")
                .withProperty("cliphive.url-signing-key", "short")
                .withProperty("cliphive.encryption-key", "a".repeat(64))   // hex, not Base64 of 32 bytes
                .withProperty("cliphive.admin-public-url", "cliphive.example.com");
        assertThat(StartupConfigCheck.check(env).errors())
                .anyMatch(e -> e.contains("jdbc:postgresql"))
                .anyMatch(e -> e.contains("absolute"))
                .anyMatch(e -> e.contains("at least 32"))
                .anyMatch(e -> e.contains("Base64 of exactly 32 bytes"))
                .anyMatch(e -> e.contains("CLIPHIVE_ADMIN_PUBLIC_URL must be an http(s) URL"));
    }

    @Test
    void errors_stop_startup_and_the_embedded_profile_is_skipped_when_built_in() {
        var check = new StartupConfigCheck();
        assertThatThrownBy(() -> check.onApplicationEvent(event(new MockEnvironment())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLIPHIVE_DB_URL is not set");

        var embedded = new MockEnvironment();
        embedded.setActiveProfiles("embedded");
        assertThatCode(() -> check.onApplicationEvent(event(embedded))).doesNotThrowAnyException();
        assertThatCode(() -> check.onApplicationEvent(event(complete()))).doesNotThrowAnyException();
    }

    @Test
    void the_embedded_profile_fails_when_the_build_does_not_carry_it() {
        var check = new StartupConfigCheck("io.github.raulgl422.cliphive.server.config.NotInThisBuild");
        var embedded = complete();
        embedded.setActiveProfiles("embedded");
        assertThatThrownBy(() -> check.onApplicationEvent(event(embedded)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not in this build")
                .hasMessageContaining("-Pembedded");
    }

    @Test
    void the_demo_profile_only_runs_with_the_embedded_one() {
        var check = new StartupConfigCheck();
        var demoOnRealDb = complete();
        demoOnRealDb.setActiveProfiles("dev", "demo");
        assertThatThrownBy(() -> check.onApplicationEvent(event(demoOnRealDb)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only runs together with 'embedded'");

        var demo = new MockEnvironment();
        demo.setActiveProfiles("embedded", "demo");
        assertThatCode(() -> check.onApplicationEvent(event(demo))).doesNotThrowAnyException();
    }

    private static ApplicationEnvironmentPreparedEvent event(MockEnvironment env) {
        return new ApplicationEnvironmentPreparedEvent(new DefaultBootstrapContext(),
                new SpringApplication(), new String[0], env);
    }
}
