package io.github.raulgl422.cliphive.server.config;

import io.github.raulgl422.cliphive.server.properties.ClipHiveProperties;
import org.apache.catalina.connector.Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Adds the media and worker connectors to the embedded Tomcat instance.
 *
 * <p>Spring Boot's primary connector (the admin one, {@code server.port}) is
 * bound to {@code localhost} only by this class. It also wires in two additional ones so a single JVM handles three
 * separate entry points without any cross-connector leakage
 * (docs/DESIGN.md §3.2):
 *
 * <ul>
 *   <li><b>Media</b> ({@code CLIPHIVE_MEDIA_PORT}, default 8081) — localhost
 *       only; Cloudflare Tunnel exposes solely {@code /media/**} publicly so
 *       Instagram can fetch clips without going through Cloudflare Access.
 *   <li><b>Workers</b> ({@code CLIPHIVE_WORKER_PORT}, default 8082) — binds
 *       to the Tailscale interface ({@code CLIPHIVE_WORKER_BIND}); skipped and
 *       logged as a warning when {@code workerBind} is blank (e.g. Tailscale
 *       not set up locally).
 * </ul>
 *
 * <p>The {@link PortRestrictionFilter} enforces that requests arriving on the
 * wrong connector are rejected at the servlet level, providing a second
 * defence-in-depth layer beyond the interface binding.
 */
@Configuration
public class TomcatConnectorConfig {

    private static final Logger log = LoggerFactory.getLogger(TomcatConnectorConfig.class);

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> extraConnectors(
            ClipHiveProperties props) {
        return factory -> {
            // Bind the primary (admin) connector to localhost as well.
            factory.setAddress(loopback());

            // Media connector — localhost, public via Cloudflare Tunnel without Access.
            Connector media = new Connector();
            media.setPort(props.getMediaPort());
            media.setProperty("address", "127.0.0.1");
            factory.addAdditionalConnectors(media);
            log.info("Media connector on port {}", props.getMediaPort());

            // Worker connector — Tailscale interface only. Skipped if bind address is blank.
            String workerBind = props.getWorkerBind();
            if (workerBind == null || workerBind.isBlank()) {
                log.warn("CLIPHIVE_WORKER_BIND is not set — worker WebSocket connector skipped. "
                        + "Set it to the Tailscale IP (or 127.0.0.1 for local dev) to enable it.");
                return;
            }
            Connector worker = new Connector();
            worker.setPort(props.getWorkerPort());
            worker.setProperty("address", workerBind);
            factory.addAdditionalConnectors(worker);
            log.info("Worker connector on {}:{}", workerBind, props.getWorkerPort());
        };
    }

    private static InetAddress loopback() {
        try {
            return InetAddress.getByName("127.0.0.1");
        } catch (UnknownHostException e) {
            throw new IllegalStateException("Cannot resolve 127.0.0.1", e);
        }
    }
}
