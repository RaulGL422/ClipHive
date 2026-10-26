package io.github.raulgl422.cliphive.server.web.worker;

import io.github.raulgl422.cliphive.protocol.state.TaskState;
import io.github.raulgl422.cliphive.server.model.Task;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.properties.ClipHiveProperties;
import io.github.raulgl422.cliphive.server.repository.TaskRepository;
import io.github.raulgl422.cliphive.server.service.JobFiles;
import io.github.raulgl422.cliphive.server.service.SignedUrlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkerFileControllerTest {

    @TempDir
    Path tempDir;

    private TaskRepository taskRepository;
    private SignedUrlService signedUrlService;
    private MockMvc mvc;
    private ClipHiveProperties props;

    @BeforeEach
    void setUp() {
        taskRepository = mock(TaskRepository.class);
        signedUrlService = mock(SignedUrlService.class);

        props = new ClipHiveProperties();
        props.setDataDir(tempDir.toString());

        WorkerFileController controller = new WorkerFileController(
                taskRepository, signedUrlService, new JobFiles(props));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ── GET /tasks/{taskId}/{attempt}/audio ──────────────────────────────────

    @Test
    void download_audio_returns_401_for_invalid_token() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(signedUrlService.validateWorkerToken(any(), any(), anyInt(), any())).thenReturn(false);

        mvc.perform(get("/tasks/{id}/1/audio", taskId).param("token", "bad"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void download_audio_returns_404_for_missing_task() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(signedUrlService.validateWorkerToken(any(), any(), anyInt(), any())).thenReturn(true);
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        mvc.perform(get("/tasks/{id}/1/audio", taskId).param("token", "t"))
                .andExpect(status().isNotFound());
    }

    @Test
    void download_audio_returns_404_when_chunk_file_missing() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(signedUrlService.validateWorkerToken(any(), any(), anyInt(), any())).thenReturn(true);

        Task task = transcribeTask(taskId, "jobs/abc/chunks/chunk_000.wav");
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        // file doesn't exist in tempDir

        mvc.perform(get("/tasks/{id}/1/audio", taskId).param("token", "t"))
                .andExpect(status().isNotFound());
    }

    @Test
    void download_audio_returns_200_when_chunk_exists() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(signedUrlService.validateWorkerToken(any(), any(), anyInt(), any())).thenReturn(true);

        Path chunksDir = Files.createDirectories(tempDir.resolve("jobs/abc/chunks"));
        Path chunkFile = chunksDir.resolve("chunk_000.wav");
        Files.write(chunkFile, "WAV_DATA".getBytes(StandardCharsets.UTF_8));

        Task task = transcribeTask(taskId, "jobs/abc/chunks/chunk_000.wav");
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        mvc.perform(get("/tasks/{id}/1/audio", taskId).param("token", "t"))
                .andExpect(status().isOk());
    }

    // ── PUT /tasks/{taskId}/{attempt}/transcript ─────────────────────────────

    @Test
    void upload_transcript_returns_401_for_invalid_token() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(signedUrlService.validateWorkerToken(any(), any(), anyInt(), any())).thenReturn(false);

        mvc.perform(put("/tasks/{id}/1/transcript", taskId)
                        .param("token", "bad")
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void upload_transcript_returns_404_for_missing_task() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(signedUrlService.validateWorkerToken(any(), any(), anyInt(), any())).thenReturn(true);
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        mvc.perform(put("/tasks/{id}/1/transcript", taskId)
                        .param("token", "t")
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void upload_transcript_stores_json_and_returns_204() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(signedUrlService.validateWorkerToken(any(), any(), anyInt(), any())).thenReturn(true);

        Path chunksDir = Files.createDirectories(tempDir.resolve("jobs/abc/chunks"));
        Path chunkFile = chunksDir.resolve("chunk_000.wav");
        Files.write(chunkFile, new byte[0]);

        Task task = transcribeTask(taskId, "jobs/abc/chunks/chunk_000.wav");
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        byte[] transcript = "{\"segments\":[]}".getBytes(StandardCharsets.UTF_8);
        mvc.perform(put("/tasks/{id}/1/transcript", taskId)
                        .param("token", "t")
                        .content(transcript))
                .andExpect(status().isNoContent());

        Path saved = chunksDir.resolve("chunk_000.json");
        assertThat(Files.exists(saved)).isTrue();
        assertThat(Files.readString(saved)).isEqualTo("{\"segments\":[]}");
    }

    // ── Superseded attempts (DESIGN.md §6.3) ─────────────────────────────────

    @Test
    void upload_from_a_superseded_attempt_is_rejected_and_leaves_the_file_alone() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(signedUrlService.validateWorkerToken(any(), any(), anyInt(), any())).thenReturn(true);

        Path chunksDir = Files.createDirectories(tempDir.resolve("jobs/abc/chunks"));
        Files.write(chunksDir.resolve("chunk_000.wav"), new byte[0]);
        Path saved = Files.writeString(chunksDir.resolve("chunk_000.json"), "current");

        Task task = transcribeTask(taskId, "jobs/abc/chunks/chunk_000.wav");
        task.reassign();                       // attempt 2 is the live one now
        task.assignTo(null);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        mvc.perform(put("/tasks/{id}/1/transcript", taskId).param("token", "t").content("stale"))
                .andExpect(status().isConflict());
        assertThat(Files.readString(saved)).isEqualTo("current");
    }

    @Test
    void a_cancelled_task_serves_no_files() throws Exception {
        UUID taskId = UUID.randomUUID();
        when(signedUrlService.validateWorkerToken(any(), any(), anyInt(), any())).thenReturn(true);

        Path chunksDir = Files.createDirectories(tempDir.resolve("jobs/abc/chunks"));
        Files.write(chunksDir.resolve("chunk_000.wav"), "WAV_DATA".getBytes(StandardCharsets.UTF_8));

        Task task = transcribeTask(taskId, "jobs/abc/chunks/chunk_000.wav");
        task.cancel();
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        mvc.perform(get("/tasks/{id}/{attempt}/audio", taskId, task.getAttempt()).param("token", "t"))
                .andExpect(status().isConflict());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Task transcribeTask(UUID taskId, String chunkPath) {
        VideoJob job = new VideoJob("http://x", "es", null, false);
        setField(job, "id", UUID.randomUUID());
        Task task = Task.forTranscribe(job, Map.of("chunkIndex", 0, "chunkPath", chunkPath));
        setField(task, "id", taskId);
        task.setState(TaskState.RUNNING);
        return task;
    }

    private static void setField(Object obj, String name, Object value) {
        try {
            Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
