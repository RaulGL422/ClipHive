package io.github.raulgl422.cliphive.server.web.media;

import io.github.raulgl422.cliphive.server.model.Clip;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.repository.ClipRepository;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import io.github.raulgl422.cliphive.server.service.JobFiles;
import io.github.raulgl422.cliphive.server.service.SignedUrlService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.context.WebApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The public media route Instagram fetches clips from (DESIGN.md §3.2, R1). */
@SpringBootTest
@ActiveProfiles("embedded")
class MediaRouteTest {

    @Autowired SignedUrlService signedUrls;
    @Autowired ClipRepository clips;
    @Autowired VideoJobRepository jobs;
    @Autowired JobFiles files;
    @Autowired WebApplicationContext wac;

    private VideoJob job;

    @AfterEach
    void tearDown() throws Exception {
        clips.deleteAll();
        jobs.deleteAll();
        if (job != null) FileSystemUtils.deleteRecursively(files.dataDir().resolve("jobs").resolve(job.getId().toString()));
    }

    @Test
    void a_signed_url_serves_the_rendered_clip_with_byte_ranges() throws Exception {
        job = jobs.save(new VideoJob("https://example.com/v", "es", "auto", false));
        Clip clip = clips.save(new Clip(job, 0, List.of()));
        Path file = files.renderedClip(job.getId(), 0);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[1000]);

        String url = signedUrls.generateMediaUrl(clip.getId());
        String token = url.substring(url.lastIndexOf('/') + 1);
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).build();

        mvc.perform(get("/media/" + token))
                .andExpect(status().isOk())
                .andExpect(content().contentType("video/mp4"));
        mvc.perform(get("/media/" + token).header("Range", "bytes=0-99"))
                .andExpect(status().isPartialContent());
        mvc.perform(get("/media/not-a-valid-token"))
                .andExpect(status().isUnauthorized());
    }
}
