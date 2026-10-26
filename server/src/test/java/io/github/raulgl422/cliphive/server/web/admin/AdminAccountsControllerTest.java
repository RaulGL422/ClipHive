package io.github.raulgl422.cliphive.server.web.admin;

import io.github.raulgl422.cliphive.server.model.SocialAccount;
import io.github.raulgl422.cliphive.server.model.enums.Platform;
import io.github.raulgl422.cliphive.server.publish.SocialAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

/**
 * The OAuth callback only connects an account when its {@code state} is the
 * one this session started (DESIGN.md §13): a forged or replayed callback
 * must not attach someone else's account.
 */
class AdminAccountsControllerTest {

    private SocialAccountService accounts;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        accounts = mock(SocialAccountService.class);
        when(accounts.isConfigured(Platform.TIKTOK)).thenReturn(true);
        when(accounts.authorizationUrl(eq(Platform.TIKTOK), anyString())).thenReturn("https://tiktok.example/auth");
        when(accounts.connect(eq(Platform.TIKTOK), anyString()))
                .thenReturn(new SocialAccount(Platform.TIKTOK, "acct", "token"));
        mvc = MockMvcBuilders.standaloneSetup(new AdminAccountsController(accounts)).build();
    }

    @Test
    void a_callback_with_a_state_this_session_did_not_start_connects_nothing() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/admin/accounts/tiktok/connect").session(session));

        mvc.perform(get("/admin/accounts/tiktok/callback").session(session)
                        .param("code", "attacker-code").param("state", "made-up"))
                .andExpect(redirectedUrl("/admin/accounts"));

        verify(accounts, never()).connect(any(), any());
    }

    @Test
    void the_state_works_once() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/admin/accounts/tiktok/connect").session(session));
        String state = (String) session.getAttribute("oauth-state-" + Platform.TIKTOK);

        mvc.perform(get("/admin/accounts/tiktok/callback").session(session)
                .param("code", "code-1").param("state", state));
        mvc.perform(get("/admin/accounts/tiktok/callback").session(session)
                .param("code", "code-2").param("state", state));

        verify(accounts, times(1)).connect(Platform.TIKTOK, "code-1");
        verify(accounts, never()).connect(Platform.TIKTOK, "code-2");
    }

    @Test
    void a_state_from_another_session_is_refused() throws Exception {
        MockHttpSession mine = new MockHttpSession();
        MockHttpSession theirs = new MockHttpSession();
        mvc.perform(post("/admin/accounts/tiktok/connect").session(theirs));
        String theirState = (String) theirs.getAttribute("oauth-state-" + Platform.TIKTOK);

        mvc.perform(get("/admin/accounts/tiktok/callback").session(mine)
                .param("code", "their-code").param("state", theirState));

        verify(accounts, never()).connect(any(), any());
    }
}
