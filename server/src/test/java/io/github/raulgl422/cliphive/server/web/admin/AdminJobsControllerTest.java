package io.github.raulgl422.cliphive.server.web.admin;

import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.model.enums.ClipMode;
import io.github.raulgl422.cliphive.server.publish.PublicationService;
import io.github.raulgl422.cliphive.server.repository.ClipRepository;
import io.github.raulgl422.cliphive.server.repository.ContentAnalysisRepository;
import io.github.raulgl422.cliphive.server.repository.ScheduledPostRepository;
import io.github.raulgl422.cliphive.server.repository.TaskRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import io.github.raulgl422.cliphive.server.repository.WorkerRepository;
import io.github.raulgl422.cliphive.server.service.ClipMetadataService;
import io.github.raulgl422.cliphive.server.service.JobControlService;
import io.github.raulgl422.cliphive.server.service.JobFiles;
import io.github.raulgl422.cliphive.server.service.SettingService;
import io.github.raulgl422.cliphive.server.service.VideoJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Submitting a job from the Jobs page (DESIGN.md §12.2): the "Revisión" checkbox. */
class AdminJobsControllerTest {

    private static final String URL = "https://www.youtube.com/watch?v=abc";

    private VideoJobService jobService;
    private SettingService settings;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        jobService = mock(VideoJobService.class);
        settings = mock(SettingService.class);
        when(settings.defaultLanguage()).thenReturn("es");
        when(settings.defaultFpsMode()).thenReturn("auto");
        when(settings.defaultReviewMode()).thenReturn(true);
        VideoJob job = mock(VideoJob.class);
        when(job.getId()).thenReturn(UUID.randomUUID());
        when(jobService.submit(anyString(), anyString(), anyString(), anyBoolean(), any(ClipMode.class), any()))
                .thenReturn(job);
        var controller = new AdminJobsController(jobService, mock(VideoJobRepository.class),
                mock(ClipRepository.class), mock(TaskRepository.class), mock(ContentAnalysisRepository.class),
                settings, mock(WorkerRepository.class), mock(JobControlService.class),
                mock(ScheduledPostRepository.class), mock(PublicationService.class), mock(JobFiles.class),
                mock(ClipMetadataService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller).setValidator(new LocalValidatorFactoryBean()).build();
    }

    /** What the jobs.html form posts: the checkbox plus its "_reviewMode" field marker. */
    private MockHttpServletRequestBuilder form() {
        return post("/admin/jobs").param("url", URL).param("_reviewMode", "on");
    }

    @Test
    void a_ticked_checkbox_turns_review_on() throws Exception {
        mvc.perform(form().param("reviewMode", "true")).andExpect(status().is3xxRedirection());
        verify(jobService).submit(eq(URL), eq("es"), eq("auto"), eq(true), eq(ClipMode.AUTO), isNull());
    }

    @Test
    void an_unticked_checkbox_turns_review_off_even_when_the_default_is_on() throws Exception {
        mvc.perform(form()).andExpect(status().is3xxRedirection());
        verify(jobService).submit(eq(URL), eq("es"), eq("auto"), eq(false), eq(ClipMode.AUTO), isNull());
    }

    @Test
    void a_client_that_sends_no_review_field_gets_the_default() throws Exception {
        mvc.perform(post("/admin/jobs").param("url", URL)).andExpect(status().is3xxRedirection());
        verify(jobService).submit(eq(URL), eq("es"), eq("auto"), eq(true), eq(ClipMode.AUTO), isNull());
    }
}
