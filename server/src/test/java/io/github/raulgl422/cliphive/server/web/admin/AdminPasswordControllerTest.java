package io.github.raulgl422.cliphive.server.web.admin;

import io.github.raulgl422.cliphive.server.model.AdminUser;
import io.github.raulgl422.cliphive.server.repository.AdminUserRepository;
import io.github.raulgl422.cliphive.server.security.EncryptionService;
import io.github.raulgl422.cliphive.server.security.LoginAttemptService;
import io.github.raulgl422.cliphive.server.security.TotpService;
import io.github.raulgl422.cliphive.server.service.LogEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

/** Changing the superadmin password from Settings (DESIGN.md §12.1). */
class AdminPasswordControllerTest {

    private static final String CURRENT = "the-current-password";
    private static final String NEW = "a-brand-new-password";

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private AdminUserRepository users;
    private EncryptionService encryption;
    private AdminUser admin;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        users = mock(AdminUserRepository.class);
        encryption = mock(EncryptionService.class);
        admin = new AdminUser("admin", encoder.encode(CURRENT));
        when(users.findByUsername("admin")).thenReturn(Optional.of(admin));
        mvc = MockMvcBuilders.standaloneSetup(new AdminPasswordController(users, encoder, new TotpService(),
                encryption, new LoginAttemptService(), mock(LogEventService.class))).build();
    }

    private ResultActions change(String current, String fresh, String confirm, String code) throws Exception {
        var req = post("/admin/settings/password")
                .principal(new UsernamePasswordAuthenticationToken("admin", null))
                .param("currentPassword", current)
                .param("newPassword", fresh)
                .param("confirmPassword", confirm);
        if (code != null) req.param("code", code);
        return mvc.perform(req).andExpect(redirectedUrl("/admin/settings"));
    }

    @Test
    void the_right_current_password_and_a_valid_new_one_change_it() throws Exception {
        change(CURRENT, NEW, NEW, null).andExpect(flash().attribute("success", "Contraseña cambiada."));

        assertThat(encoder.matches(NEW, admin.getPasswordHash())).isTrue();
        verify(users).save(admin);
    }

    @Test
    void a_wrong_current_password_changes_nothing() throws Exception {
        change("not-the-password", NEW, NEW, null)
                .andExpect(flash().attribute("error", "La contraseña actual no es correcta."));

        assertThat(encoder.matches(CURRENT, admin.getPasswordHash())).isTrue();
        verify(users, never()).save(any());
    }

    @Test
    void a_short_mismatched_or_unchanged_new_password_is_refused() throws Exception {
        change(CURRENT, "short", "short", null)
                .andExpect(flash().attribute("error", "La nueva contraseña debe tener al menos 12 caracteres."));
        change(CURRENT, NEW, NEW + "x", null)
                .andExpect(flash().attribute("error", "Las dos contraseñas nuevas no coinciden."));
        change(CURRENT, CURRENT, CURRENT, null)
                .andExpect(flash().attribute("error", "La nueva contraseña es igual a la actual."));
        verify(users, never()).save(any());
    }

    @Test
    void five_wrong_tries_block_the_change_even_with_the_right_password() throws Exception {
        for (int i = 0; i < 5; i++) change("wrong-" + i, NEW, NEW, null);

        change(CURRENT, NEW, NEW, null)
                .andExpect(flash().attribute("error", "Demasiados intentos fallidos. Espera 15 minutos."));
        verify(users, never()).save(any());
    }

    @Test
    void with_2fa_on_a_wrong_or_missing_code_is_refused() throws Exception {
        TotpService totp = new TotpService();
        String secret = totp.generateSecret();
        admin.enableTotp("encrypted");
        when(encryption.decrypt("encrypted")).thenReturn(secret);

        change(CURRENT, NEW, NEW, "000000").andExpect(flash().attribute("error", "El código 2FA no es correcto."));
        change(CURRENT, NEW, NEW, null).andExpect(flash().attribute("error", "El código 2FA no es correcto."));
        verify(users, never()).save(any());
    }
}
