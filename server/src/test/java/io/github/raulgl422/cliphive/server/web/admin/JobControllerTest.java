package io.github.raulgl422.cliphive.server.web.admin;

import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.service.VideoJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JobControllerTest {

    private VideoJobService jobService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        jobService = mock(VideoJobService.class);

        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders
                .standaloneSetup(new JobController(jobService))
                .setValidator(validator)
                .build();
    }

    @Test
    void post_valid_url_returns_201_with_job_id_and_state() throws Exception {
        var job = jobWithId(UUID.randomUUID(), "https://youtu.be/abc");
        when(jobService.submit(anyString(), anyString(), isNull(), anyBoolean())).thenReturn(job);

        mockMvc.perform(post("/admin/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://youtu.be/abc","language":"es","reviewMode":false}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(job.getId().toString()))
                .andExpect(jsonPath("$.state").value("SUBMITTED"));
    }

    @Test
    void post_blank_url_returns_400() throws Exception {
        mockMvc.perform(post("/admin/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"","language":"es","reviewMode":false}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void post_missing_url_field_returns_400() throws Exception {
        mockMvc.perform(post("/admin/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"language":"es","reviewMode":false}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void post_forwards_all_fields_to_service() throws Exception {
        var job = jobWithId(UUID.randomUUID(), "https://youtu.be/abc");
        when(jobService.submit(anyString(), anyString(), anyString(), anyBoolean())).thenReturn(job);

        mockMvc.perform(post("/admin/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://youtu.be/abc","language":"en","fpsMode":"source","reviewMode":true}
                                """))
                .andExpect(status().isCreated());

        verify(jobService).submit("https://youtu.be/abc", "en", "source", true);
    }

    private static VideoJob jobWithId(UUID id, String url) {
        var job = new VideoJob(url, "es", null, false);
        try {
            Field idField = VideoJob.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(job, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return job;
    }

    @Test
    void post_non_http_url_returns_400() throws Exception {
        when(jobService.submit(anyString(), anyString(), isNull(), anyBoolean()))
                .thenThrow(new IllegalArgumentException("Solo se admiten enlaces http(s) a un vídeo: --exec=x"));

        mockMvc.perform(post("/admin/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"--exec=x","language":"es","reviewMode":false}
                                """))
                .andExpect(status().isBadRequest());
    }
}
