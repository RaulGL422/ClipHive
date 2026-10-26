package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.server.llm.LlmClient;
import io.github.raulgl422.cliphive.server.model.Clip;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.repository.ClipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ClipMetadataServiceTest {

    private ClipMetadataService service;

    @BeforeEach
    void setUp() {
        service = new ClipMetadataService(
                mock(LlmClient.class),
                mock(ClipRepository.class),
                null, null, null, null,
                new ObjectMapper(),
                null);
    }

    // ── parseAndValidate ──────────────────────────────────────────────────────

    @Test
    void a_batch_gives_each_clip_its_texts_and_on_screen_title() throws IOException {
        String json = """
                {"clips": [
                  {"id": "c1", "titulo": "Empieza la remontada", "descripcion": "Primer tiempo.",
                   "hashtags": ["#futbol"], "titulo_pantalla": "La remontada · 1/2"},
                  {"id": "c2", "titulo": "Y llega el gol", "descripcion": "Final.",
                   "hashtags": [], "titulo_pantalla": "  La remontada   · 2/2 #gol "},
                  {"id": "c3", "titulo": "", "descripcion": "sin título: se descarta"}]}""";

        var texts = service.parseBatch(json);

        assertThat(texts).containsOnlyKeys("c1", "c2");
        assertThat(texts.get("c1").overlayTitle()).isEqualTo("La remontada · 1/2");
        assertThat(texts.get("c1").description()).isEqualTo("Primer tiempo.\n\n#futbol");
        assertThat(texts.get("c2").overlayTitle()).as("cleaned").isEqualTo("La remontada · 2/2");
        assertThat(ClipMetadataService.overlay("x".repeat(80))).hasSize(ClipMetadataService.MAX_OVERLAY_LENGTH);
        assertThat(ClipMetadataService.overlay("  ")).isNull();
    }

    @Test
    void the_server_numbers_series_on_the_on_screen_title() {
        var job = new VideoJob("https://x", "es", null, false);
        var clip = new Clip(job, 1, List.of());
        clip.setSectionTitle("La remontada");

        assertThat(ClipMetadataService.numbered("La remontada", clip)).as("not a series").isEqualTo("La remontada");
        clip.setPartIndex(2);
        clip.setPartCount(3);
        assertThat(ClipMetadataService.numbered("La remontada", clip)).isEqualTo("La remontada · 2/3");
        assertThat(ClipMetadataService.numbered("La remontada (1/3)", clip)).as("Claude's own number replaced")
                .isEqualTo("La remontada · 2/3");
        assertThat(ClipMetadataService.numbered(null, clip)).as("falls back to the section").isEqualTo("La remontada · 2/3");
    }

    @Test
    void hashtags_are_normalized_and_appended_once() throws IOException {
        String json = """
                {"titulo": "El gol del año", "descripcion": "Clip de Canal X. ¿Lo has visto? #gol",
                 "hashtags": ["#Gol", "la liga", "#fyp", "#gol", "Fútbol!", "#Messi", "#Barça", "#extra"]}""";
        String[] title = new String[1];
        String[] desc = new String[1];

        service.parseAndValidate(json, title, desc);

        assertThat(desc[0]).isEqualTo("Clip de Canal X. ¿Lo has visto?\n\n#Gol #laliga #Fútbol #Messi #Barça");
    }

    @Test
    void clip_text_keeps_only_the_kept_ranges() {
        var job = new VideoJob("https://x", "es", null, false);
        var clip = new Clip(job, 0, List.of(
                Map.of("startMs", 0, "endMs", 1000), Map.of("startMs", 3000, "endMs", 4000)));
        var segments = List.<Map<String, Object>>of(Map.of("words", List.of(
                Map.of("text", " hola", "startMs", 100, "endMs", 500),
                Map.of("text", " patrocinio", "startMs", 1500, "endMs", 2000),
                Map.of("text", " adiós", "startMs", 3100, "endMs", 3500))));

        assertThat(ClipMetadataService.clipText(clip, segments)).isEqualTo("hola adiós");
    }

    @Test
    void valid_response_is_accepted() throws IOException {
        String json = """
                {"titulo": "Un título corto", "descripcion": "Una descripción. #hashtag"}""";
        String[] title = {null};
        String[] desc  = {null};
        service.parseAndValidate(json, title, desc);
        assertThat(title[0]).isEqualTo("Un título corto");
        assertThat(desc[0]).isEqualTo("Una descripción. #hashtag");
    }

    @Test
    void title_too_long_is_truncated() throws IOException {
        String longTitle = "A".repeat(120);
        String json = "{\"titulo\": \"" + longTitle + "\", \"descripcion\": \"desc\"}";
        String[] title = {null};
        String[] desc  = {null};
        service.parseAndValidate(json, title, desc);
        assertThat(title[0]).hasSize(ClipMetadataService.MAX_TITLE_LENGTH);
    }

    @Test
    void description_too_long_is_truncated() throws IOException {
        String longDesc = "B".repeat(2500);
        String json = "{\"titulo\": \"ok\", \"descripcion\": \"" + longDesc + "\"}";
        String[] title = {null};
        String[] desc  = {null};
        service.parseAndValidate(json, title, desc);
        assertThat(desc[0]).hasSizeLessThanOrEqualTo(ClipMetadataService.MAX_DESCRIPTION_LENGTH);
    }

    @Test
    void forbidden_characters_removed_from_title() throws IOException {
        String json = "{\"titulo\": \"<Cool> Title\", \"descripcion\": \"ok\"}";
        String[] title = {null};
        String[] desc  = {null};
        service.parseAndValidate(json, title, desc);
        assertThat(title[0]).doesNotContain("<").doesNotContain(">");
        assertThat(title[0]).contains("Cool");
    }

    @Test
    void missing_title_throws() {
        String json = "{\"descripcion\": \"desc only\"}";
        String[] title = {null};
        String[] desc  = {null};
        assertThatThrownBy(() -> service.parseAndValidate(json, title, desc))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("titulo");
    }

    @Test
    void non_json_throws() {
        assertThatThrownBy(() -> service.parseAndValidate("not json", new String[1], new String[1]))
                .isInstanceOf(IOException.class);
    }

    @Test
    void a_series_part_says_which_part_it_is_in_its_title_and_description() {
        var job = new VideoJob("https://x", "es", null, false);
        var clip = new Clip(job, 1, List.of());

        assertThat(ClipMetadataService.withPart("Un clip suelto", clip, "es")).as("not a series").isEqualTo("Un clip suelto");
        clip.setPartIndex(2);
        clip.setPartCount(3);
        assertThat(ClipMetadataService.withPart("La remontada del siglo", clip, "es"))
                .isEqualTo("La remontada del siglo (Parte 2/3)");
        assertThat(ClipMetadataService.withPart("La remontada - Parte 1", clip, "es")).as("Claude's own number replaced")
                .isEqualTo("La remontada (Parte 2/3)");
        assertThat(ClipMetadataService.withPart("The comeback", clip, "en")).isEqualTo("The comeback (Part 2/3)");
        assertThat(ClipMetadataService.withPart("Le doy un 10/10", clip, "es")).as("a score isn't a part number")
                .isEqualTo("Le doy un 10/10 (Parte 2/3)");
        assertThat(ClipMetadataService.withPart("x".repeat(120), clip, "es")).hasSize(ClipMetadataService.MAX_TITLE_LENGTH)
                .endsWith("… (Parte 2/3)");

        assertThat(ClipMetadataService.withPartLine("Sigue la remontada.\n\n#futbol #remontada", clip, "es"))
                .isEqualTo("Sigue la remontada.\nParte 2 de 3 · el resto, en el perfil\n\n#futbol #remontada");
        assertThat(ClipMetadataService.withPartLine("Parte 2 de 3 de la remontada.", clip, "es")).as("already says it")
                .isEqualTo("Parte 2 de 3 de la remontada.");
    }

    @Test
    void batches_never_split_a_section() {
        var job = new VideoJob("https://x", "es", null, false);
        List<Clip> clips = new ArrayList<>();
        // 25 standalone clips, then a series of 10 parts: the series goes whole into the second batch
        for (int i = 0; i < 35; i++) {
            var c = new Clip(job, i, List.of());
            c.setSectionTitle(i < 25 ? "Sección " + i : "La serie");
            clips.add(c);
        }
        var batches = ClipMetadataService.batches(clips);
        assertThat(batches).hasSize(2);
        assertThat(batches.get(0)).hasSize(25);
        assertThat(batches.get(1)).hasSize(10).allMatch(c -> c.getSectionTitle().equals("La serie"));
    }
}
