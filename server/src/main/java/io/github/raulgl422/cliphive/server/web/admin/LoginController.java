package io.github.raulgl422.cliphive.server.web.admin;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the superadmin login form — docs/DESIGN.md §12.1. Spring Security
 * only handles the POST; with a custom {@code loginPage("/login")} the GET
 * needs a handler, otherwise it 404s and the error dispatch bounces back to
 * {@code /login} in a redirect loop.
 */
@Controller
public class LoginController {

    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
