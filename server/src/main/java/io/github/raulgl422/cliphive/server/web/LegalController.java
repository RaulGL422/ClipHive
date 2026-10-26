package io.github.raulgl422.cliphive.server.web;

import io.github.raulgl422.cliphive.server.properties.ClipHiveProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Public privacy policy and terms pages (DESIGN.md §11.5) that Google, Meta
 * and TikTok link to. No login: they're exempt from the admin chain and from
 * Cloudflare Access by a Bypass rule for {@code <admin host>/legal/*}.
 *
 * <p>The operator (data controller) named on them comes from
 * {@code CLIPHIVE_OPERATOR_NAME} / {@code CLIPHIVE_OPERATOR_EMAIL}, so every
 * installation names whoever runs it.
 */
@Controller
public class LegalController {

    private static final MediaType HTML_UTF8 = MediaType.parseMediaType("text/html;charset=UTF-8");
    private static final String NOT_CONFIGURED = "(sin configurar / not configured)";

    private final String privacy;
    private final String terms;

    public LegalController(ClipHiveProperties props) {
        // Unset values show "not configured"; StartupConfigCheck warns about them at startup.
        String name = props.getOperatorName();
        String email = props.getOperatorEmail();
        this.privacy = fill("legal/privacy.html", name, email);
        this.terms = fill("legal/terms.html", name, email);
    }

    @GetMapping("/legal/privacy")
    public ResponseEntity<String> privacy() {
        return page(privacy);
    }

    @GetMapping("/legal/terms")
    public ResponseEntity<String> terms() {
        return page(terms);
    }

    private static ResponseEntity<String> page(String html) {
        return ResponseEntity.ok()
                .contentType(HTML_UTF8)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(html);
    }

    /** The page with its {@code {{operatorName}}} / {@code {{operatorEmail}}} placeholders filled, HTML-escaped. */
    static String fill(String path, String name, String email) {
        try {
            String html = new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
            return html.replace("{{operatorName}}", escaped(name))
                       .replace("{{operatorEmail}}", escaped(email));
        } catch (IOException e) {
            throw new UncheckedIOException("Missing legal page " + path, e);
        }
    }

    private static String escaped(String value) {
        return isBlank(value) ? NOT_CONFIGURED : HtmlUtils.htmlEscape(value.strip());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
