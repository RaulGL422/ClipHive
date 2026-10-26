package io.github.raulgl422.cliphive.server.config;

import io.github.raulgl422.cliphive.server.properties.ClipHiveProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PortRestrictionFilterTest {

    private static final int ADMIN_PORT = 8080;
    private static final int MEDIA_PORT = 8081;
    private static final int WORKER_PORT = 8082;

    private PortRestrictionFilter filter;

    @BeforeEach
    void setUp() {
        ClipHiveProperties props = new ClipHiveProperties();
        props.setMediaPort(MEDIA_PORT);
        props.setWorkerPort(WORKER_PORT);
        filter = new PortRestrictionFilter(props);
    }

    @Test
    void media_port_allows_media_path() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        var req = requestOn(MEDIA_PORT, "/media/some-token");
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void media_port_blocks_non_media_path() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        var req = requestOn(MEDIA_PORT, "/admin/jobs");
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verifyNoInteractions(chain);
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
    }

    @Test
    void worker_port_allows_ws_path() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        var req = requestOn(WORKER_PORT, "/ws/worker");
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void worker_port_allows_tasks_path() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        var req = requestOn(WORKER_PORT, "/tasks/abc/1/audio");
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void worker_port_blocks_non_ws_path() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        var req = requestOn(WORKER_PORT, "/admin/jobs");
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verifyNoInteractions(chain);
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
    }

    @Test
    void admin_port_blocks_tasks_path() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        var req = requestOn(ADMIN_PORT, "/tasks/abc/1/audio");
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verifyNoInteractions(chain);
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
    }

    @Test
    void admin_port_allows_admin_paths() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        var req = requestOn(ADMIN_PORT, "/admin/jobs");
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void admin_port_blocks_media_path() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        var req = requestOn(ADMIN_PORT, "/media/token");
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verifyNoInteractions(chain);
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
    }

    @Test
    void admin_port_blocks_ws_path() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        var req = requestOn(ADMIN_PORT, "/ws/worker");
        var res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verifyNoInteractions(chain);
        assertThat(res.getStatus()).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
    }

    private static MockHttpServletRequest requestOn(int localPort, String path) {
        var req = new MockHttpServletRequest("GET", path);
        req.setLocalPort(localPort);
        return req;
    }

    @Test
    void runs_before_spring_security_so_login_never_answers_on_the_media_port() {
        // Spring Security's filter chain is registered at order -100
        Order order = AnnotationUtils.findAnnotation(PortRestrictionFilter.class, Order.class);
        assertThat(order).isNotNull();
        assertThat(order.value()).isLessThan(-100);
    }
}
