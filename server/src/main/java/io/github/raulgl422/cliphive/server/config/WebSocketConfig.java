package io.github.raulgl422.cliphive.server.config;

import io.github.raulgl422.cliphive.server.service.worker.WorkerWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the worker WebSocket endpoint at {@code /ws/worker}.
 *
 * <p>Access is limited to the worker connector (Tailscale interface,
 * {@code CLIPHIVE_WORKER_PORT}) at the network level via
 * {@link TomcatConnectorConfig}, and at the servlet level via
 * {@link PortRestrictionFilter}. Workers authenticate by sending a
 * {@code HELLO} message immediately after connecting — the handshake
 * itself carries no credentials.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final WorkerWebSocketHandler handler;

    public WebSocketConfig(WorkerWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/worker")
                .setAllowedOrigins("*"); // Tailscale-only: no browser origin concerns.
    }
}
