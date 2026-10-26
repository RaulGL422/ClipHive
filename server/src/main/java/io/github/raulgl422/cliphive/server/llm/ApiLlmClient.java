package io.github.raulgl422.cliphive.server.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LlmClient implementation backed by the Anthropic Messages API
 * (docs/DESIGN.md §10.1), for when the Agent SDK credit runs short; needs
 * {@code ANTHROPIC_API_KEY}. {@link CliLlmClient} is the {@code @Primary}
 * bean, so callers get this one only by asking for it explicitly.
 *
 * <p>The prompt and stdinContent are concatenated into one user message.
 */
@Component
public class ApiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(ApiLlmClient.class);
    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-opus-5-5";
    private static final int MAX_TOKENS = 4096;

    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ApiLlmClient(ObjectMapper objectMapper) {
        this.apiKey = System.getenv().getOrDefault("ANTHROPIC_API_KEY", "");
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public String call(String prompt, String stdinContent) throws IOException, InterruptedException {
        if (apiKey.isBlank()) {
            throw new IOException("ANTHROPIC_API_KEY not configured — cannot use ApiLlmClient");
        }
        String content = stdinContent.isBlank()
                ? prompt
                : prompt + "\n\n" + stdinContent;

        String body = buildBody(content);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .timeout(CliLlmClient.TIMEOUT)   // no answer by then: give up, don't hang the caller
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Anthropic API returned " + response.statusCode()
                    + ": " + response.body());
        }

        JsonNode node = objectMapper.readTree(response.body());
        String text = node.at("/content/0/text").asText("");
        log.debug("Anthropic API returned {} chars", text.length());
        return text.strip();
    }

    private String buildBody(String content) throws IOException {
        Map<String, Object> message = Map.of("role", "user", "content", content);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", MODEL);
        body.put("max_tokens", MAX_TOKENS);
        body.put("messages", List.of(message));
        return objectMapper.writeValueAsString(body);
    }
}
