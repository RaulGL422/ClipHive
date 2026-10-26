package io.github.raulgl422.cliphive.server.security;

import io.github.raulgl422.cliphive.server.model.AdminUser;
import io.github.raulgl422.cliphive.server.repository.AdminUserRepository;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two-step login (DESIGN.md §12.1) through the real security filter chain:
 * the password alone only reaches the code form, it can't replace or remove
 * the second factor, and both steps lock out after repeated failures.
 */
@SpringBootTest
@ActiveProfiles("embedded")
class AdminLoginFlowTest {

    // Seeded by AdminUserInitializer from application-embedded.properties
    private static final String USER = "admin";
    private static final String PASSWORD = "admin123";

    @Autowired WebApplicationContext wac;
    @Autowired @Qualifier("springSecurityFilterChain") Filter securityChain;
    @Autowired AdminUserRepository admins;
    @Autowired EncryptionService encryption;
    @Autowired TotpService totp;
    @Autowired LoginAttemptService attempts;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(wac).addFilters(securityChain).build();
    }

    @AfterEach
    void resetAdmin() {
        AdminUser admin = admins.findByUsername(USER).orElseThrow();
        admin.disableTotp();
        admins.save(admin);
        attempts.recordSuccess("totp:" + USER);
    }

    @Test
    void with_totp_on_the_password_alone_only_reaches_the_code_form() throws Exception {
        enableTotp(totp.generateSecret());
        MockHttpSession session = login("10.0.0.1");

        mvc().perform(get("/admin").session(session))
                .andExpect(redirectedUrl("/admin/totp"));
    }

    @Test
    void a_pre_auth_session_cannot_reach_totp_setup_or_overwrite_the_secret() throws Exception {
        String original = totp.generateSecret();
        enableTotp(original);
        MockHttpSession session = login("10.0.0.2");

        mvc().perform(get("/admin/totp/setup").session(session))
                .andExpect(redirectedUrl("/admin/totp"));

        String mine = totp.generateSecret();
        mvc().perform(post("/admin/totp/setup").session(session).with(csrf())
                        .param("secret", mine).param("code", code(mine)))
                .andExpect(redirectedUrl("/admin/totp"));
        mvc().perform(post("/admin/totp/disable").session(session).with(csrf())
                        .param("code", "000000"))
                .andExpect(redirectedUrl("/admin/totp"));

        assertThat(storedSecret()).isEqualTo(original);
    }

    @Test
    void a_wrong_code_keeps_the_session_pre_auth_and_the_right_one_upgrades_it() throws Exception {
        String secret = totp.generateSecret();
        enableTotp(secret);
        MockHttpSession session = login("10.0.0.3");

        mvc().perform(post("/admin/totp").session(session).with(csrf()).param("code", wrongCode(secret)))
                .andExpect(redirectedUrl("/admin/totp"));
        mvc().perform(get("/admin").session(session))
                .andExpect(redirectedUrl("/admin/totp"));

        mvc().perform(post("/admin/totp").session(session).with(csrf()).param("code", code(secret)))
                .andExpect(redirectedUrl("/admin"));
        mvc().perform(get("/admin").session(session))
                .andExpect(status().isOk());
    }

    @Test
    void totp_codes_lock_out_after_repeated_failures() throws Exception {
        String secret = totp.generateSecret();
        enableTotp(secret);
        MockHttpSession session = login("10.0.0.4");

        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            mvc().perform(post("/admin/totp").session(session).with(csrf()).param("code", wrongCode(secret)));
        }
        mvc().perform(post("/admin/totp").session(session).with(csrf()).param("code", code(secret)))
                .andExpect(redirectedUrl("/admin/totp"));
        mvc().perform(get("/admin").session(session))
                .andExpect(redirectedUrl("/admin/totp"));
    }

    @Test
    void setup_is_refused_while_totp_is_on_even_for_a_full_admin() throws Exception {
        String original = totp.generateSecret();
        enableTotp(original);
        MockHttpSession session = login("10.0.0.5");
        mvc().perform(post("/admin/totp").session(session).with(csrf()).param("code", code(original)))
                .andExpect(redirectedUrl("/admin"));

        mvc().perform(get("/admin/totp/setup").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Desactivar 2FA")))
                .andExpect(content().string(not(containsString("otpauth://"))));

        String other = totp.generateSecret();
        mvc().perform(post("/admin/totp/setup").session(session).with(csrf())
                        .param("secret", other).param("code", code(other)))
                .andExpect(redirectedUrl("/admin/settings"));

        assertThat(storedSecret()).isEqualTo(original);
    }

    @Test
    void a_blocked_ip_is_refused_even_with_the_right_password() throws Exception {
        String ip = "10.0.1.1";
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            mvc().perform(post("/login").with(csrf()).header("X-Forwarded-For", ip)
                    .param("username", USER).param("password", "wrong"));
        }

        mvc().perform(post("/login").with(csrf()).header("X-Forwarded-For", ip)
                        .param("username", USER).param("password", PASSWORD))
                .andExpect(redirectedUrl("/login?blocked"));
    }

    @Test
    void a_forged_first_forwarded_for_entry_does_not_reset_the_lockout() throws Exception {
        // The proxy appends the address it saw; whatever comes before it is the client's
        String proxySaw = "10.0.1.2";
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            mvc().perform(post("/login").with(csrf()).header("X-Forwarded-For", "203.0.113." + i + ", " + proxySaw)
                    .param("username", USER).param("password", "wrong"));
        }

        mvc().perform(post("/login").with(csrf()).header("X-Forwarded-For", "198.51.100.7, " + proxySaw)
                        .param("username", USER).param("password", PASSWORD))
                .andExpect(redirectedUrl("/login?blocked"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private MockHttpSession login(String ip) throws Exception {
        return (MockHttpSession) mvc().perform(post("/login").with(csrf()).header("X-Forwarded-For", ip)
                        .param("username", USER).param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andReturn().getRequest().getSession(false);
    }

    private void enableTotp(String secret) {
        AdminUser admin = admins.findByUsername(USER).orElseThrow();
        admin.enableTotp(encryption.encrypt(secret));
        admins.save(admin);
    }

    private String storedSecret() {
        return encryption.decrypt(admins.findByUsername(USER).orElseThrow().getTotpSecretEncrypted());
    }

    /** The current RFC 6238 code for {@code secret} (30 s step, 6 digits, SHA-1). */
    private String code(String secret) throws Exception {
        long step = Instant.now().getEpochSecond() / 30;
        byte[] msg = new byte[8];
        for (int i = 7; i >= 0; i--, step >>>= 8) msg[i] = (byte) step;
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(totp.base32Decode(secret), "RAW"));
        byte[] h = mac.doFinal(msg);
        int o = h[h.length - 1] & 0x0f;
        int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
        return "%06d".formatted(bin % 1_000_000);
    }

    /** A 6-digit code that is not valid for {@code secret} in any step of the window. */
    private String wrongCode(String secret) {
        for (int n = 0; ; n++) {
            String candidate = "%06d".formatted(n);
            if (!totp.verify(secret, candidate)) return candidate;
        }
    }
}
