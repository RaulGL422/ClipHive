package io.github.raulgl422.cliphive.server.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmClientTest {

    // ── extractJson ───────────────────────────────────────────────────────────

    @Test
    void plain_json_is_returned_unchanged() {
        String json = "{\"key\": \"value\"}";
        assertThat(LlmClient.extractJson(json)).isEqualTo(json);
    }

    @Test
    void json_code_fence_is_stripped() {
        String raw = "```json\n{\"a\": 1}\n```";
        assertThat(LlmClient.extractJson(raw)).isEqualTo("{\"a\": 1}");
    }

    @Test
    void plain_code_fence_is_stripped() {
        String raw = "```\n{\"a\": 1}\n```";
        assertThat(LlmClient.extractJson(raw)).isEqualTo("{\"a\": 1}");
    }

    @Test
    void leading_and_trailing_whitespace_is_stripped() {
        String raw = "  \n{\"a\": 1}\n  ";
        assertThat(LlmClient.extractJson(raw)).isEqualTo("{\"a\": 1}");
    }

    @Test
    void a_sentence_before_and_after_the_json_is_dropped() {
        String raw = "Aquí tienes:\n{\"segments\": [{\"id\": \"1\"}]}\nEspero que sirva.";
        assertThat(LlmClient.extractJson(raw)).isEqualTo("{\"segments\": [{\"id\": \"1\"}]}");
    }

    @Test
    void a_fence_after_a_sentence_is_handled() {
        String raw = "Claro:\n```json\n{\"a\": 1}\n```";
        assertThat(LlmClient.extractJson(raw)).isEqualTo("{\"a\": 1}");
    }

    @Test
    void a_top_level_array_after_a_sentence_is_kept_whole() {
        String raw = "Resultado: [{\"a\": 1}, {\"b\": 2}]";
        assertThat(LlmClient.extractJson(raw)).isEqualTo("[{\"a\": 1}, {\"b\": 2}]");
    }

    @Test
    void multiline_json_in_fence_is_preserved() {
        String raw = """
                ```json
                {
                  "titulo": "test",
                  "descripcion": "desc"
                }
                ```""";
        String result = LlmClient.extractJson(raw);
        assertThat(result).contains("titulo").contains("descripcion");
        assertThat(result).doesNotContain("```");
    }
}
