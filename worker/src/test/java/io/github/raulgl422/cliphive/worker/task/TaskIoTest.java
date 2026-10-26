package io.github.raulgl422.cliphive.worker.task;

import com.sun.net.httpserver.HttpServer;
import io.github.raulgl422.cliphive.protocol.message.TaskAssign;
import io.github.raulgl422.cliphive.protocol.state.TaskType;
import io.github.raulgl422.cliphive.worker.config.WorkerProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shared task plumbing: work directory layout, signed-URL transfers
 * against a local HTTP server, and work-directory cleanup.
 */
class TaskIoTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @TempDir Path tempDir;

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private void respond(String path, int status, byte[] body) {
        server.createContext(path, ex -> {
            ex.getRequestBody().readAllBytes();
            if (body == null) {
                ex.sendResponseHeaders(status, -1);
            } else {
                ex.sendResponseHeaders(status, body.length);
                try (OutputStream out = ex.getResponseBody()) { out.write(body); }
            }
            ex.close();
        });
    }

    // --- workDir ---

    @Test
    void work_dir_is_data_dir_tasks_task_id_and_attempt() {
        WorkerProperties props = new WorkerProperties();
        props.setDataDir(tempDir.toString());
        UUID taskId = UUID.randomUUID();
        TaskAssign assign = new TaskAssign(taskId, 1, TaskType.TRANSCRIBE, Map.of(), Map.of(), Map.of());

        assertThat(TaskIo.workDir(props, assign))
                .isEqualTo(tempDir.resolve("tasks").resolve(taskId + "-1"));
    }

    @Test
    void two_attempts_of_one_task_never_share_a_folder() {
        WorkerProperties props = new WorkerProperties();
        props.setDataDir(tempDir.toString());
        UUID taskId = UUID.randomUUID();
        TaskAssign first = new TaskAssign(taskId, 1, TaskType.RENDER_CLIP, Map.of(), Map.of(), Map.of());
        TaskAssign second = new TaskAssign(taskId, 2, TaskType.RENDER_CLIP, Map.of(), Map.of(), Map.of());

        assertThat(TaskIo.workDir(props, first)).isNotEqualTo(TaskIo.workDir(props, second));
    }

    // --- download ---

    @Test
    void download_without_url_fails_naming_the_file() {
        Path dest = tempDir.resolve("audio.wav");

        assertThatThrownBy(() -> TaskIo.download(null, dest, TIMEOUT, "audio"))
                .isInstanceOf(IOException.class).hasMessageContaining("audio");
        assertThatThrownBy(() -> TaskIo.download("  ", dest, TIMEOUT, "audio"))
                .isInstanceOf(IOException.class).hasMessageContaining("audio");
    }

    @Test
    void download_200_writes_the_body_to_dest() throws Exception {
        byte[] body = "video-bytes".getBytes(StandardCharsets.UTF_8);
        respond("/file", 200, body);
        Path dest = tempDir.resolve("source.mp4");

        TaskIo.download(url("/file"), dest, TIMEOUT, "source video");

        assertThat(dest).hasBinaryContent(body);
    }

    @Test
    void download_non_200_fails_with_the_http_code() {
        respond("/file", 404, "nope".getBytes(StandardCharsets.UTF_8));
        Path dest = tempDir.resolve("source.mp4");

        assertThatThrownBy(() -> TaskIo.download(url("/file"), dest, TIMEOUT, "source video"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 404")
                .hasMessageContaining("source video");
    }

    // --- upload ---

    @Test
    void upload_without_url_fails_naming_the_file() throws IOException {
        Path src = Files.writeString(tempDir.resolve("clip.mp4"), "x");

        assertThatThrownBy(() -> TaskIo.upload(" ", src, "video/mp4", TIMEOUT, "clip"))
                .isInstanceOf(IOException.class).hasMessageContaining("clip");
        assertThatThrownBy(() -> TaskIo.upload(null, src, "video/mp4", TIMEOUT, "clip"))
                .isInstanceOf(IOException.class).hasMessageContaining("clip");
    }

    @Test
    void upload_204_puts_the_file_bytes_with_content_type() throws Exception {
        byte[] content = "rendered-clip".getBytes(StandardCharsets.UTF_8);
        Path src = Files.write(tempDir.resolve("clip.mp4"), content);
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<byte[]> received = new AtomicReference<>();
        server.createContext("/upload", ex -> {
            method.set(ex.getRequestMethod());
            contentType.set(ex.getRequestHeaders().getFirst("Content-Type"));
            received.set(ex.getRequestBody().readAllBytes());
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });

        TaskIo.upload(url("/upload"), src, "video/mp4", TIMEOUT, "clip");

        assertThat(method.get()).isEqualTo("PUT");
        assertThat(contentType.get()).isEqualTo("video/mp4");
        assertThat(received.get()).isEqualTo(content);
    }

    @Test
    void upload_other_status_fails_with_the_http_code() throws IOException {
        Path src = Files.writeString(tempDir.resolve("clip.mp4"), "x");
        respond("/upload", 200, null);

        assertThatThrownBy(() -> TaskIo.upload(url("/upload"), src, "video/mp4", TIMEOUT, "clip"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("HTTP 200")
                .hasMessageContaining("clip");
    }

    // --- deleteRecursively ---

    @Test
    void delete_recursively_removes_a_nested_tree() throws IOException {
        Path root = tempDir.resolve("tasks").resolve("t1");
        Path nested = Files.createDirectories(root.resolve("a").resolve("b"));
        Files.writeString(root.resolve("top.txt"), "1");
        Files.writeString(nested.resolve("deep.txt"), "2");

        TaskIo.deleteRecursively(root);

        assertThat(root).doesNotExist();
        assertThat(tempDir.resolve("tasks")).exists();
    }

    @Test
    void delete_recursively_on_a_missing_dir_is_a_no_op() {
        Path missing = tempDir.resolve("does-not-exist");

        assertThatCode(() -> TaskIo.deleteRecursively(missing)).doesNotThrowAnyException();
        assertThat(missing).doesNotExist();
    }
}
