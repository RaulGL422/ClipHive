package io.github.raulgl422.cliphive.server.service.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The child is a one-file Java program run with the current JDK, so the test
 * behaves the same on Windows and Linux without ffmpeg or a shell.
 */
class ProcessRunnerTest {

    @TempDir Path dir;

    @Test
    void a_child_that_hangs_is_killed_at_the_timeout() throws Exception {
        ProcessBuilder pb = program("Hang", "Thread.sleep(120_000);");

        long start = System.nanoTime();
        assertThatThrownBy(() -> ProcessRunner.run(pb, Duration.ofSeconds(5)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timed out");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(30));
    }

    @Test
    void a_child_that_never_reads_its_stdin_still_times_out() throws Exception {
        ProcessBuilder pb = program("Deaf", "Thread.sleep(120_000);");
        byte[] bigInput = new byte[4 * 1024 * 1024];   // far more than a pipe buffer

        assertThatThrownBy(() -> ProcessRunner.run(pb, bigInput, Duration.ofSeconds(5)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    void output_bigger_than_the_pipe_buffer_does_not_block_the_child() throws Exception {
        ProcessBuilder pb = program("Flood",
                "String line = \"x\".repeat(1023); for (int i = 0; i < 2048; i++) { System.out.println(line); System.err.println(line); }");

        ProcessRunner.Result result = ProcessRunner.run(pb, Duration.ofMinutes(1));

        assertThat(result.ok()).isTrue();
        assertThat(result.stdout().length()).isGreaterThan(2_000_000);
        assertThat(result.stderr().length()).isGreaterThan(2_000_000);
    }

    @Test
    void stdin_reaches_the_child_and_the_exit_code_comes_back() throws Exception {
        ProcessBuilder pb = program("Echo",
                "String in = new String(System.in.readAllBytes()); System.out.print(in.toUpperCase()); System.exit(3);");

        ProcessRunner.Result result = ProcessRunner.run(pb, "hola".getBytes(StandardCharsets.UTF_8), Duration.ofMinutes(1));

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.ok()).isFalse();
        assertThat(result.stdout()).isEqualTo("HOLA");
    }

    private ProcessBuilder program(String name, String body) throws IOException {
        Path source = dir.resolve(name + ".java");
        Files.writeString(source, "public class " + name
                + " { public static void main(String[] a) throws Exception { " + body + " } }");
        String java = ProcessHandle.current().info().command().orElse("java");
        return new ProcessBuilder(java, source.toString());
    }
}
