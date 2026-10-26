package io.github.raulgl422.cliphive.server.config;

import io.github.raulgl422.cliphive.server.properties.ClipHiveProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Enforces per-connector path restrictions so misconfigured tunnel routing or
 * a stray request can't reach the wrong surface (docs/DESIGN.md §3.2, §13):
 *
 * <ul>
 *   <li>Media port ({@code CLIPHIVE_MEDIA_PORT}) — only {@code /media/**} allowed.
 *   <li>Worker port ({@code CLIPHIVE_WORKER_PORT}) — {@code /ws/**} (WebSocket) and
 *       {@code /tasks/**} (signed file download/upload) allowed.
 *   <li>Admin port (all others) — {@code /media/**}, {@code /ws/**} and
 *       {@code /tasks/**} blocked.
 * </ul>
 *
 * <p>This is a second defence-in-depth layer; the primary protection is the
 * interface binding in {@link TomcatConnectorConfig}. Any violation returns
 * 404 (not 403) to avoid leaking which paths exist on other connectors.
 */
// First of all filters, before Spring Security (-100): otherwise the security
// chain answered POST /login on the public media port before this ran, and
// passwords could be tried there without Cloudflare Access (DESIGN.md §13).
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
public class PortRestrictionFilter extends OncePerRequestFilter {

    private final int mediaPort;
    private final int workerPort;

    public PortRestrictionFilter(ClipHiveProperties props) {
        this.mediaPort = props.getMediaPort();
        this.workerPort = props.getWorkerPort();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        int port = request.getLocalPort();
        String path = request.getRequestURI();

        if (port == mediaPort && !path.startsWith("/media/")) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (port == workerPort && !path.startsWith("/ws/") && !path.startsWith("/tasks/")) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        if (port != mediaPort && port != workerPort
                && (path.startsWith("/media/") || path.startsWith("/ws/") || path.startsWith("/tasks/"))) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        chain.doFilter(request, response);
    }
}
