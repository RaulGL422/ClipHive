package io.github.raulgl422.cliphive.server.config;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.io.IOException;

/**
 * Provides a volatile DataSource backed by an embedded Postgres binary when
 * the "embedded" profile is active. Spring Boot's DataSourceAutoConfiguration
 * backs off via @ConditionalOnMissingBean(DataSource.class), so Flyway and
 * Hibernate pick this DataSource up transparently.
 *
 * Data is discarded on JVM exit. Intended for quick local runs and smoke tests
 * that don't need a real Postgres instance.
 */
@Configuration
@Profile("embedded")
class EmbeddedDataSourceConfig {

    @Bean(destroyMethod = "close")
    EmbeddedPostgres embeddedPostgres() throws IOException {
        return EmbeddedPostgres.builder().start();
    }

    @Bean
    DataSource dataSource(EmbeddedPostgres pg) {
        return pg.getPostgresDatabase();
    }
}
