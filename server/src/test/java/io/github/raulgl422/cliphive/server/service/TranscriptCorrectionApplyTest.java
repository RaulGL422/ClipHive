package io.github.raulgl422.cliphive.server.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What Claude may and may not change in a transcript (DESIGN.md §10.5). */
class TranscriptCorrectionApplyTest {

    private final ObjectMapper json = JsonMapper.builder().build();

    private static Map<String, Object> word(String text, long start, long end) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("text", text);
        w.put("startMs", start);
        w.put("endMs", end);
        return w;
    }

    /** Segment 0: "ola a todos"; segment 1: "bamos a jugar". */
    private static List<Map<String, Object>> segments() {
        List<Map<String, Object>> segs = new ArrayList<>();
        for (List<Map<String, Object>> words : List.of(
                List.of(word("ola", 0, 300), word("a", 300, 400), word("todos,", 400, 900)),
                List.of(word("bamos", 1000, 1300), word("a", 1300, 1400), word("jugar.", 1400, 1900)))) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("text", String.join(" ", words.stream().map(w -> (String) w.get("text")).toList()));
            s.put("words", new ArrayList<>(words));
            segs.add(s);
        }
        return segs;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> words(List<Map<String, Object>> segs, int i) {
        return (List<Map<String, Object>>) segs.get(i).get("words");
    }

    @Test
    void a_valid_correction_changes_the_text_but_never_the_times() {
        List<Map<String, Object>> segs = segments();

        int changed = TranscriptCorrectionService.apply(segs, json.readTree(
                "{\"segments\":[{\"id\":\"0\",\"words\":[\"Hola\",\"a\",\"todos,\"]}]}"));

        assertThat(changed).isEqualTo(1);
        assertThat(segs.get(0).get("text")).isEqualTo("Hola a todos,");
        assertThat(words(segs, 0).getFirst())
                .containsEntry("text", "Hola").containsEntry("startMs", 0L).containsEntry("endMs", 300L);
        assertThat(segs.get(1).get("text")).isEqualTo("bamos a jugar.");
    }

    @Test
    void segments_that_add_remove_merge_or_rewrite_words_are_rejected() {
        List<Map<String, Object>> segs = segments();

        int changed = TranscriptCorrectionService.apply(segs, json.readTree("""
                {"segments":[
                  {"id":"0","words":["Hola","a","todos","vosotros"]},
                  {"id":"1","words":["vamos a","jugar."]},
                  {"id":"1","words":["Vamos","","jugar."]},
                  {"id":"1","words":["Vamos","%s","jugar."]},
                  {"id":"7","words":["x","y","z"]},
                  {"id":"nope","words":["x","y","z"]}
                ]}""".formatted("a".repeat(TranscriptCorrectionService.MAX_WORD_LENGTH + 1))));

        assertThat(changed).isZero();
        assertThat(segs.get(0).get("text")).isEqualTo("ola a todos,");
        assertThat(segs.get(1).get("text")).isEqualTo("bamos a jugar.");
    }

    @Test
    void returning_a_segment_unchanged_does_not_count_as_a_correction() {
        List<Map<String, Object>> segs = segments();

        int changed = TranscriptCorrectionService.apply(segs, json.readTree(
                "{\"segments\":[{\"id\":\"1\",\"words\":[\"bamos\",\"a\",\"jugar.\"]}]}"));

        assertThat(changed).isZero();
    }
}
