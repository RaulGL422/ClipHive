package io.github.raulgl422.cliphive.server.service;

import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Word building from whisper.cpp tokens. The fixture is real {@code -ojf}
 * output for a Spanish sentence: whisper splits words into pieces
 * (" bien" + "venidos") and punctuation into its own token.
 */
class TranscriptStitchWordsTest {

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> segments() throws Exception {
        try (InputStream in = TranscriptStitchWordsTest.class.getResourceAsStream("/whisper/sample-es.json")) {
            Map<String, Object> json = JsonMapper.builder().build().readValue(in, new TypeReference<>() {});
            return (List<Map<String, Object>>) json.get("transcription");
        }
    }

    private static List<String> texts(List<Map<String, Object>> words) {
        return words.stream().map(w -> (String) w.get("text")).toList();
    }

    @Test
    void word_pieces_and_punctuation_join_the_previous_word() throws Exception {
        List<Map<String, Object>> words =
                TranscriptStitchService.extractWords(segments().get(0), 0, 0, Long.MAX_VALUE);

        assertThat(texts(words)).containsExactly(
                "Hola", "a", "todos,", "bienvenidos", "al", "directo", "de", "hoy.");
    }

    @Test
    void accented_words_split_in_pieces_come_out_whole() throws Exception {
        List<Map<String, Object>> words =
                TranscriptStitchService.extractWords(segments().get(1), 0, 0, Long.MAX_VALUE);

        assertThat(texts(words)).contains("partida", "increíble,", "preparados.");
    }

    @Test
    void a_joined_word_spans_from_its_first_to_its_last_piece() throws Exception {
        Map<String, Object> segment = Map.of("tokens", List.of(
                token(" bien", 1000, 1200),
                token("venidos", 1200, 1700)));

        Map<String, Object> word = TranscriptStitchService.extractWords(segment, 60_000, 0, Long.MAX_VALUE).getFirst();

        assertThat(word).containsEntry("text", "bienvenidos")
                .containsEntry("startMs", 61_000L)
                .containsEntry("endMs", 61_700L);
    }

    @Test
    void canonical_range_filters_whole_words_by_their_start() {
        // "bienvenidos" starts before the cut at 1500 ms but ends after it: it stays whole
        Map<String, Object> segment = Map.of("tokens", List.of(
                token(" bien", 1000, 1200),
                token("venidos", 1600, 1900),
                token(" al", 2000, 2100)));

        List<Map<String, Object>> before = TranscriptStitchService.extractWords(segment, 0, 0, 1500);
        List<Map<String, Object>> after  = TranscriptStitchService.extractWords(segment, 0, 1500, Long.MAX_VALUE);

        assertThat(texts(before)).containsExactly("bienvenidos");
        assertThat(texts(after)).containsExactly("al");
    }

    private static Map<String, Object> token(String text, int from, int to) {
        return Map.of("text", text, "offsets", Map.of("from", from, "to", to));
    }
}
