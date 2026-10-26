package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.server.llm.LlmClient;
import io.github.raulgl422.cliphive.server.model.Transcript;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.repository.TranscriptRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Transcript correction (DESIGN.md §10.5) end to end on the embedded DB, Claude mocked. */
@SpringBootTest
@ActiveProfiles("embedded")
class TranscriptCorrectionServiceTest {

    @MockitoBean LlmClient llm;
    @Autowired TranscriptCorrectionService correction;
    @Autowired TranscriptRepository transcripts;
    @Autowired VideoJobRepository jobs;
    @Autowired SettingService settings;

    private VideoJob job;

    @AfterEach
    void tearDown() {
        transcripts.deleteAll();
        jobs.deleteAll();
        settings.set(SettingService.TRANSCRIPT_CORRECT, "true");
        reset(llm);
    }

    private void transcriptOf(String... segmentTexts) {
        job = jobs.save(new VideoJob("https://example.com/v", "es", "auto", false));
        List<Map<String, Object>> segments = new ArrayList<>();
        long t = 0;
        for (String text : segmentTexts) {
            List<Map<String, Object>> words = new ArrayList<>();
            for (String w : text.split(" ")) {
                words.add(Map.of("text", w, "startMs", t, "endMs", t + 200));
                t += 250;
            }
            segments.add(Map.of("text", text, "words", words));
        }
        transcripts.save(new Transcript(job, "es", Map.of("language", "es", "segments", segments)));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> storedSegments() {
        return (List<Map<String, Object>>) transcripts.findByVideoJobId(job.getId()).orElseThrow()
                .getWordsJson().get("segments");
    }

    @Test
    void corrections_are_stored_once_with_times_kept() throws Exception {
        transcriptOf("ola a todos", "bamos a jugar");
        when(llm.call(anyString(), anyString())).thenReturn("""
                Aquí tienes:
                {"segments":[{"id":"1","words":["vamos","a","jugar"]},{"id":"0","words":["Hola","todos"]}]}""");

        correction.correct(job.getId());

        List<Map<String, Object>> segs = storedSegments();
        assertThat(segs.get(1).get("text")).isEqualTo("vamos a jugar");
        @SuppressWarnings("unchecked")
        Map<String, Object> first = ((List<Map<String, Object>>) segs.get(1).get("words")).getFirst();
        assertThat(first).containsEntry("text", "vamos");
        assertThat(((Number) first.get("startMs")).longValue()).isEqualTo(750L);
        assertThat(segs.get(0).get("text")).as("wrong word count: rejected").isEqualTo("ola a todos");
        assertThat(transcripts.findByVideoJobId(job.getId()).orElseThrow().getWordsJson())
                .containsEntry(TranscriptCorrectionService.CORRECTED_FLAG, true);

        // Retrying the render setup must not call Claude again
        correction.correct(job.getId());
        verify(llm, times(1)).call(anyString(), anyString());
    }

    @Test
    void claude_failing_leaves_the_transcript_as_whisper_wrote_it() throws Exception {
        transcriptOf("ola a todos");
        when(llm.call(anyString(), anyString())).thenThrow(new IOException("claude not found"));

        correction.correct(job.getId());

        assertThat(storedSegments().getFirst().get("text")).isEqualTo("ola a todos");
        verify(llm, times(TranscriptCorrectionService.MAX_RETRIES)).call(anyString(), anyString());
    }

    @Test
    void does_nothing_when_switched_off_in_settings() throws Exception {
        transcriptOf("ola a todos");
        settings.set(SettingService.TRANSCRIPT_CORRECT, "false");

        correction.correct(job.getId());

        verify(llm, never()).call(anyString(), anyString());
    }

    @Test
    void long_transcripts_are_sent_in_batches() throws Exception {
        String[] many = new String[TranscriptCorrectionService.BATCH_SIZE + 10];
        Arrays.fill(many, "hola mundo");
        transcriptOf(many);
        when(llm.call(anyString(), anyString())).thenReturn("{\"segments\":[]}");

        correction.correct(job.getId());

        verify(llm, times(2)).call(anyString(), anyString());
    }
}
