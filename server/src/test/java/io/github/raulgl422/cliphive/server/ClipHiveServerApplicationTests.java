package io.github.raulgl422.cliphive.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the full Spring context against a real embedded PostgreSQL via the
 * "embedded" profile (EmbeddedDataSourceConfig). A green run here means all
 * 15 Flyway migrations applied cleanly against real Postgres syntax (jsonb,
 * num_nonnulls, FOR UPDATE SKIP LOCKED) and Hibernate's ddl-auto=validate
 * confirmed every entity matches the resulting schema.
 */
@SpringBootTest
@ActiveProfiles("embedded")
class ClipHiveServerApplicationTests {

    @Test
    void contextLoads() {
        // Intentionally empty: the assertion is that the context starts at
        // all — see the class Javadoc for what that actually proves.
    }
}
