package io.github.raulgl422.cliphive.server.web;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The privacy and terms pages are public (DESIGN.md §11.5) while the rest of
 * the superadmin still requires a login — checked through the real security
 * filter chain.
 */
@SpringBootTest
@ActiveProfiles("embedded")
class LegalPagesTest {

    @Autowired WebApplicationContext wac;
    @Autowired @Qualifier("springSecurityFilterChain") Filter securityChain;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(wac).addFilters(securityChain).build();
    }

    @Test
    void legal_pages_open_without_a_login() throws Exception {
        mvc().perform(get("/legal/privacy"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("Política de privacidad")))
                .andExpect(content().string(containsString("Limited Use")));
        mvc().perform(get("/legal/terms"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Términos del servicio")));
    }

    @Test
    void legal_pages_name_the_configured_operator() throws Exception {
        mvc().perform(get("/legal/privacy"))
                .andExpect(content().string(containsString("Embedded Operator")))
                .andExpect(content().string(containsString("mailto:operator@example.com")))
                .andExpect(content().string(not(containsString("{{operator"))));
        mvc().perform(get("/legal/terms"))
                .andExpect(content().string(containsString("Embedded Operator")))
                .andExpect(content().string(not(containsString("{{operator"))));
    }

    @Test
    void operator_values_are_html_escaped_and_blank_says_not_configured() {
        String html = LegalController.fill("legal/privacy.html", "<b>Eve</b>", " ");
        assertThat(html).contains("&lt;b&gt;Eve&lt;/b&gt;").doesNotContain("<b>Eve</b>")
                        .contains("(sin configurar / not configured)");
    }

    @Test
    void the_rest_of_the_superadmin_still_needs_a_login() throws Exception {
        mvc().perform(get("/admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/login")));
        mvc().perform(get("/admin/accounts"))
                .andExpect(status().is3xxRedirection());
    }
}
