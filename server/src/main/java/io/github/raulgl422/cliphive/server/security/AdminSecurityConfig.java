package io.github.raulgl422.cliphive.server.security;

import io.github.raulgl422.cliphive.server.repository.AdminUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Spring Security configuration for the superadmin — docs/DESIGN.md §12.1.
 *
 * <p>Two-step authentication:
 * <ol>
 *   <li>Username + password → Spring Security grants {@code ROLE_ADMIN_PRE_AUTH}.
 *   <li>If TOTP is enabled, the success handler redirects to {@code /admin/totp}.
 *       The TOTP controller upgrades the authority to {@code ROLE_ADMIN} on success.
 *       If TOTP is not enabled, the success handler upgrades immediately.
 * </ol>
 *
 * <p>Route access rules:
 * <ul>
 *   <li>{@code /admin/totp} (the code form) — {@code ROLE_ADMIN_PRE_AUTH} or {@code ROLE_ADMIN}
 *   <li>{@code /admin/**}, including 2FA setup and disable — {@code ROLE_ADMIN}: the
 *       password alone must never be enough to replace or remove the second factor
 * </ul>
 * <p>An IP that failed the password {@value LoginAttemptService#MAX_ATTEMPTS} times
 * is refused before the password is even checked, until the block expires.
 * An {@code AccessDeniedHandler} redirects {@code ROLE_ADMIN_PRE_AUTH} users to
 * {@code /admin/totp} so they see the 2FA form instead of a 403 page.
 */
@Configuration
@EnableWebSecurity
public class AdminSecurityConfig {

    private final AdminUserDetailsService userDetailsService;
    private final LoginAttemptService loginAttemptService;
    private final AdminUserRepository adminUserRepository;

    public AdminSecurityConfig(AdminUserDetailsService userDetailsService,
                               LoginAttemptService loginAttemptService,
                               AdminUserRepository adminUserRepository) {
        this.userDetailsService = userDetailsService;
        this.loginAttemptService = loginAttemptService;
        this.adminUserRepository = adminUserRepository;
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * Machine endpoints — worker WebSocket ({@code /ws/**}), worker file
     * transfer ({@code /tasks/**}) and the public media route ({@code /media/**}).
     * They're authenticated by the protocol itself (worker token in HELLO,
     * HMAC-signed URLs) and restricted to their connector by
     * {@code PortRestrictionFilter}; the admin login chain would answer them
     * with a redirect to /login (and CSRF would reject the worker's uploads).
     */
    @Bean
    @Order(1)
    public SecurityFilterChain machineEndpointsFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/ws/**", "/tasks/**", "/media/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(csrf -> csrf.disable())
            .requestCache(cache -> cache.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .userDetailsService(userDetailsService)
            .securityContext(sc -> sc.securityContextRepository(securityContextRepository()))
            .authorizeHttpRequests(auth -> auth
                // /error must be public: if the error dispatch needs auth, any
                // unauthenticated 404/500 turns into a redirect to /login.
                // /legal/**: public privacy policy and terms the platform apps link to (§11.5)
                .requestMatchers("/login", "/error", "/css/**", "/js/**", "/favicon.ico", "/legal/**").permitAll()
                .requestMatchers("/admin/totp").hasAnyRole("ADMIN_PRE_AUTH", "ADMIN")
                .requestMatchers("/admin/**", "/").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .addFilterBefore(blockedLoginFilter(), UsernamePasswordAuthenticationFilter.class)
            .formLogin(form -> form
                .loginPage("/login")
                .successHandler(successHandler())
                .failureHandler(failureHandler())
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .clearAuthentication(true)
            )
            .exceptionHandling(ex -> ex
                .accessDeniedHandler((request, response, denied) -> {
                    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                    if (auth != null && auth.getAuthorities().stream()
                            .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN_PRE_AUTH"))) {
                        response.sendRedirect(request.getContextPath() + "/admin/totp");
                    } else {
                        response.sendError(HttpServletResponse.SC_FORBIDDEN);
                    }
                })
            );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // ── Handlers ──────────────────────────────────────────────────────────────

    private AuthenticationSuccessHandler successHandler() {
        return (HttpServletRequest request, HttpServletResponse response, Authentication auth) -> {
            loginAttemptService.recordSuccess(extractIp(request));
            String username = auth.getName();
            boolean totpEnabled = adminUserRepository.findByUsername(username)
                    .map(u -> u.isTotpEnabled()).orElse(false);
            if (totpEnabled) {
                response.sendRedirect(request.getContextPath() + "/admin/totp");
            } else {
                upgradeToAdmin(request, response, auth);
                response.sendRedirect(request.getContextPath() + "/admin");
            }
        };
    }

    /**
     * Takes a try for the IP before the password is checked, and refuses the
     * POST when the IP is blocked. The success handler gives the tries back.
     */
    private OncePerRequestFilter blockedLoginFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
                if ("POST".equals(request.getMethod()) && (request.getContextPath() + "/login").equals(request.getRequestURI())
                        && !loginAttemptService.tryAcquire(extractIp(request))) {
                    response.sendRedirect(request.getContextPath() + "/login?blocked");
                    return;
                }
                chain.doFilter(request, response);
            }
        };
    }

    private AuthenticationFailureHandler failureHandler() {
        return (request, response, ex) -> {
            // The try was already counted by blockedLoginFilter
            String redirect = loginAttemptService.isBlocked(extractIp(request))
                    ? "/login?blocked" : "/login?error";
            response.sendRedirect(request.getContextPath() + redirect);
        };
    }

    /**
     * Replaces the {@code ROLE_ADMIN_PRE_AUTH} authentication with a full
     * {@code ROLE_ADMIN} one and saves it to the session.
     */
    public void upgradeToAdmin(HttpServletRequest request, HttpServletResponse response,
                               Authentication previous) {
        var fullAuth = new UsernamePasswordAuthenticationToken(
                previous.getPrincipal(), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContext ctx = SecurityContextHolder.getContext();
        ctx.setAuthentication(fullAuth);
        securityContextRepository().saveContext(ctx, request, response);
    }

    /**
     * The client's IP for the lockout. The admin connector only listens on
     * loopback, so requests arrive through a proxy (cloudflared, nginx) that
     * appends the address it saw to {@code X-Forwarded-For}. The client can
     * write whatever it likes in front of that, so the last entry is the one
     * to trust, not the first.
     */
    static String extractIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff == null || xff.isBlank()) return request.getRemoteAddr();
        String[] hops = xff.split(",");
        return hops[hops.length - 1].trim();
    }
}
