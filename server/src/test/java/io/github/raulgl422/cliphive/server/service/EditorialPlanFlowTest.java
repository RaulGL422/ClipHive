package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.server.llm.LlmClient;
import io.github.raulgl422.cliphive.server.model.ContentAnalysis;
import io.github.raulgl422.cliphive.server.model.Transcript;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.model.enums.ReviewStatus;
import io.github.raulgl422.cliphive.server.repository.ContentAnalysisRepository;
import io.github.raulgl422.cliphive.server.repository.TranscriptRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import io.github.raulgl422.cliphive.server.service.tool.SponsorBlockClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The editorial plan end to end (DESIGN.md §10.2): Claude first leaves half
 * the video out, is told which stretches and asked again; the saved plan
 * covers everything, in order, with the long section split into numbered
 * parts. Claude and SponsorBlock are mocked.
 */
@SpringBootTest
@ActiveProfiles("embedded")
class EditorialPlanFlowTest {

    @MockitoBean LlmClient llm;
    @MockitoBean SponsorBlockClient sponsorBlock;

    @Autowired ContentAnalysisService analysis;
    @Autowired VideoJobRepository jobs;
    @Autowired TranscriptRepository transcripts;
    @Autowired ContentAnalysisRepository analyses;

    @AfterEach
    void tearDown() {
        analyses.deleteAll();
        transcripts.deleteAll();
        jobs.deleteAll();
    }

    @Test
    void a_plan_that_skips_content_is_asked_again_and_completed() throws Exception {
        // 16 min of talk: 96 segments of 10 s, a full stop every third one
        List<Map<String, Object>> segments = new ArrayList<>();
        for (int i = 0; i < 96; i++) {
            segments.add(Map.of("startMs", i * 10_000L, "endMs", (i + 1) * 10_000L,
                    "text", i % 3 == 2 ? "y así fue." : "seguimos", "words", List.of()));
        }
        VideoJob job = jobs.save(new VideoJob("https://example.com/v", "es", "auto", false));
        transcripts.save(new Transcript(job, "es", Map.of("segments", segments, "durationMs", 960_000L)));
        when(sponsorBlock.fetchSegments(any())).thenReturn(List.of());
        when(llm.call(anyString(), anyString())).thenReturn(
                // 1st: four 1-minute highlights — what used to happen
                """
                {"contenido_inicio": "1", "contenido_fin": "95", "segmentos_eliminar": [{"ids": ["0"], "motivo": "saludos"}],
                 "secciones": [{"titulo": "A", "clips": [{"inicio": "1", "fin": "6"}]},
                               {"titulo": "B", "clips": [{"inicio": "30", "fin": "35"}]},
                               {"titulo": "C", "clips": [{"inicio": "60", "fin": "65"}]},
                               {"titulo": "D", "clips": [{"inicio": "90", "fin": "95"}]}],
                 "tipo_contenido": "charla"}""",
                // 2nd: the whole video in three sections; the middle one is 8 min long
                """
                {"contenido_inicio": "1", "contenido_fin": "95", "segmentos_eliminar": [{"ids": ["0"], "motivo": "saludos"}],
                 "secciones": [{"titulo": "El origen", "clips": [{"inicio": "1", "fin": "11"}, {"inicio": "12", "fin": "23"}]},
                               {"titulo": "La crisis", "clips": [{"inicio": "24", "fin": "71"}]},
                               {"titulo": "El final", "clips": [{"inicio": "72", "fin": "83"}, {"inicio": "84", "fin": "95"}]}],
                 "tipo_contenido": "charla"}""");

        ContentAnalysis result = analysis.analyze(job.getId());

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(llm, times(2)).call(prompts.capture(), anyString());
        assertThat(prompts.getAllValues().get(1)).contains("solo cubría el 25 %").contains("7–29");

        List<Map<String, Object>> clips = result.getProposedClipsJson();
        assertThat(clips.getFirst().get("startMs")).isEqualTo(10_000L);  // after the greeting
        assertThat(clips.getLast().get("endMs")).isEqualTo(960_000L);
        for (int i = 1; i < clips.size(); i++) {
            assertThat(((Number) clips.get(i).get("startMs")).longValue()).as("nothing skipped between clips")
                    .isEqualTo(((Number) clips.get(i - 1).get("endMs")).longValue());
        }
        assertThat(clips).allMatch(c -> ((Number) c.get("endMs")).longValue() - ((Number) c.get("startMs")).longValue() <= 180_000);
        // "La crisis" (8 min) became a numbered series
        List<Map<String, Object>> crisis = clips.stream().filter(c -> "La crisis".equals(c.get("section"))).toList();
        assertThat(crisis).hasSizeGreaterThan(2);
        assertThat(crisis.getLast().get("part")).isEqualTo(crisis.size());
        assertThat(result.getPlanNotesJson()).anyMatch(n -> n.contains("se le volvió a pedir"));
        assertThat(result.getContentType()).isEqualTo("charla");
    }

    @Test
    void when_claude_fails_the_provisional_cuts_go_to_review_even_without_review_mode() throws Exception {
        List<Map<String, Object>> segments = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            segments.add(Map.of("startMs", i * 10_000L, "endMs", (i + 1) * 10_000L,
                    "text", "seguimos.", "words", List.of()));
        }
        VideoJob job = jobs.save(new VideoJob("https://example.com/v", "es", "auto", false));
        transcripts.save(new Transcript(job, "es", Map.of("segments", segments, "durationMs", 120_000L)));
        when(sponsorBlock.fetchSegments(any())).thenReturn(List.of());
        when(llm.call(anyString(), anyString())).thenThrow(new IOException("claude -p exited with code 1"));

        ContentAnalysis saved = analysis.analyze(job.getId());

        assertThat(saved.getReviewStatus()).isEqualTo(ReviewStatus.PENDING_REVIEW);
        assertThat(saved.getProposedClipsJson()).isNull();
    }
}
