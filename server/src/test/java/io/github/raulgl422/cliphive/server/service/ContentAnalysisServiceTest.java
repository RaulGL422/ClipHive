package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.server.llm.LlmClient;
import io.github.raulgl422.cliphive.server.repository.ContentAnalysisRepository;
import io.github.raulgl422.cliphive.server.repository.TranscriptRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import io.github.raulgl422.cliphive.server.service.tool.SponsorBlockClient;
import io.github.raulgl422.cliphive.server.service.tool.SponsorSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ContentAnalysisServiceTest {

    private ContentAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new ContentAnalysisService(
                mock(LlmClient.class),
                mock(TranscriptRepository.class),
                mock(VideoJobRepository.class),
                mock(ContentAnalysisRepository.class),
                mock(SponsorBlockClient.class),
                new ObjectMapper(),
                null,
                null);
    }

    private static Map<String, Object> seg(long startMs, long endMs, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("startMs", startMs);
        m.put("endMs", endMs);
        m.put("text", text);
        return m;
    }

    // ── buildSegmentTimeMap ───────────────────────────────────────────────────

    @Test
    void segment_time_map_assigns_sequential_ids() {
        var segments = List.of(
                seg(0, 2000, "uno"),
                seg(2500, 5000, "dos"),
                seg(6000, 9000, "tres"));

        Map<String, long[]> map = service.buildSegmentTimeMap(segments);

        assertThat(map).containsKeys("0", "1", "2");
        assertThat(map.get("0")).containsExactly(0L, 2000L);
        assertThat(map.get("2")).containsExactly(6000L, 9000L);
    }

    // ── numberedSegmentsJson ──────────────────────────────────────────────────

    @Test
    void numbered_segments_json_contains_id_field() throws Exception {
        var segments = List.of(seg(0, 1000, "hola"));
        String json = service.numberedSegmentsJson(segments);
        assertThat(json).contains("\"id\"").contains("\"0\"").contains("hola");
    }

    // ── parsePlan ─────────────────────────────────────────────────────────────

    @Test
    void parse_reads_the_editorial_plan() throws IOException {
        String json = """
                {
                  "contenido_inicio": "1",
                  "contenido_fin": "8",
                  "segmentos_eliminar": [{"ids": ["4", "5"], "motivo": "patrocinio"}],
                  "secciones": [
                    {"titulo": "El planteamiento", "resumen": "...", "clips": [{"inicio": "1", "fin": "3"}]},
                    {"titulo": "La remontada", "resumen": "...", "clips": [{"inicio": "6", "fin": "7"}, {"inicio": "8", "fin": "8"}]}
                  ],
                  "tipo_contenido": "deporte"
                }""";

        var plan = service.parsePlan(json, 10);

        assertThat(plan.contentStart()).isEqualTo(1);
        assertThat(plan.contentEnd()).isEqualTo(8);
        assertThat(plan.removed()).containsExactlyInAnyOrder(4, 5);
        assertThat(plan.sections()).extracting(EditorialPlan.Section::title).containsExactly("El planteamiento", "La remontada");
        assertThat(plan.sections().get(1).clips()).hasSize(2);
        assertThat(plan.contentType()).isEqualTo("deporte");
    }

    @Test
    void parse_still_reads_the_old_flat_clip_list() throws IOException {
        var plan = service.parsePlan("""
                {"clips_propuestos": [{"inicio": "0", "fin": "2"}, {"inicio": "3", "fin": "4"}], "tipo_contenido": "charla"}""", 5);
        assertThat(plan.sections()).hasSize(2);
        assertThat(plan.contentStart()).isNull();
    }

    @Test
    void parse_rejects_unknown_ids_empty_plans_and_non_json() {
        assertThatThrownBy(() -> service.parsePlan("""
                {"secciones": [{"titulo": "x", "clips": [{"inicio": "99", "fin": "0"}]}]}""", 3))
                .isInstanceOf(IOException.class).hasMessageContaining("unknown segment");
        assertThatThrownBy(() -> service.parsePlan("{\"secciones\": []}", 3))
                .isInstanceOf(IOException.class).hasMessageContaining("empty");
        assertThatThrownBy(() -> service.parsePlan("not json", 3)).isInstanceOf(IOException.class);
    }

    @Test
    void sponsorblock_marks_the_segments_it_mostly_covers() {
        var segments = List.of(
                new EditorialPlan.Segment(0, 0, 10_000, "a"),
                new EditorialPlan.Segment(1, 10_000, 20_000, "b"),
                new EditorialPlan.Segment(2, 20_000, 30_000, "c"));
        var sponsor = List.of(new SponsorSegment(12.0, 27.0, "sponsor"));

        assertThat(ContentAnalysisService.segmentsCoveredBy(segments, sponsor)).containsExactlyInAnyOrder(1, 2);
    }
}
