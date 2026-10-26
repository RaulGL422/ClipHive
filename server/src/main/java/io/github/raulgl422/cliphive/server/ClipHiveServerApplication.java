package io.github.raulgl422.cliphive.server;

import io.github.raulgl422.cliphive.server.config.StartupConfigCheck;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point. See docs/DESIGN.md sections 3, 12 and 13 for what this
 * process does: link validation/download, the task queue, Claude analysis,
 * the publication scheduler, the platform publishers, and the superadmin
 * web UI - all in one Spring Boot application with three separate network
 * listeners (admin/media/workers, see TomcatConnectorConfig).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ClipHiveServerApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(ClipHiveServerApplication.class);
        // Stops with a readable list when .env is incomplete (DESIGN.md §13.1)
        app.addListeners(new StartupConfigCheck());
        app.run(args);
    }
}
