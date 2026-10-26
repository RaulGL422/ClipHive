package io.github.raulgl422.cliphive.worker.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ToolProcess#runLogged} with the JVM running these tests as the child
 * process (no ffmpeg needed): the output goes to the log file, a hung process
 * is killed at the timeout, and a cancel (interrupt) kills it too (DESIGN.md §9.2).
 */
class ToolProcessTest {

    private static final String JAVA = ProcessHandle.current().info().command().orElse("java");

    @TempDir
    Path dir;

    private Path log() {
        return dir.resolve("out.log");
    }

    /** A single-file Java program, run in source mode, that just sleeps. */
    private List<String> sleeper() throws IOException {
        Path source = dir.resolve("Sleeper.java");
        Files.writeString(source, """
                class Sleeper {
                    public static void main(String[] a) throws Exception { Thread.sleep(120_000); }
                }
                """);
        return List.of(JAVA, source.toString());
    }

    @Test
    void success_writes_the_output_to_the_log_file() throws Exception {
        ToolProcess.runLogged(List.of(JAVA, "-version"), dir, log(), Duration.ofMinutes(1), "java");
        assertThat(Files.readString(log())).contains("version");
    }

    @Test
    void non_zero_exit_fails_with_the_last_output_lines() {
        assertThatThrownBy(() -> ToolProcess.runLogged(
                List.of(JAVA, "-XX:+NoSuchOptionForThisTest"), dir, log(), Duration.ofMinutes(1), "java"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("java exited with code")
                .hasMessageContaining("NoSuchOptionForThisTest");
    }

    @Test
    void a_missing_binary_fails_saying_it_could_not_start() {
        assertThatThrownBy(() -> ToolProcess.runLogged(
                List.of(dir.resolve("no-such-tool").toString()), dir, log(), Duration.ofMinutes(1), "ffmpeg"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Could not start ffmpeg");
    }

    @Test
    void a_hung_process_is_killed_at_the_timeout() throws Exception {
        Set<Long> before = childPids();
        assertThatThrownBy(() -> ToolProcess.runLogged(sleeper(), dir, log(), Duration.ofSeconds(1), "sleeper"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("timed out");
        assertThat(newChildren(before)).isEmpty();
    }

    @Test
    void an_interrupt_kills_the_process_and_surfaces_as_interrupted() throws Exception {
        List<String> cmd = sleeper();
        Set<Long> before = childPids();
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread runner = Thread.ofVirtual().start(() -> {
            try {
                ToolProcess.runLogged(cmd, dir, log(), Duration.ofMinutes(30), "sleeper");
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        // Wait until the child is really running before cancelling it.
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (newChildren(before).isEmpty() && System.nanoTime() < deadline) Thread.sleep(50);
        assertThat(newChildren(before)).isNotEmpty();

        runner.interrupt();
        runner.join(Duration.ofSeconds(30));
        assertThat(runner.isAlive()).isFalse();
        assertThat(thrown.get()).isInstanceOf(InterruptedException.class);
        assertThat(newChildren(before)).isEmpty();
    }

    @Test
    void tail_keeps_the_last_lines_and_caps_their_size() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10_000; i++) sb.append("line ").append(i).append('\n');
        Files.writeString(log(), sb);
        String tail = ToolProcess.tail(log());
        assertThat(tail).endsWith("line 9999").doesNotStartWith("ine");
        assertThat(tail.lines().count()).isEqualTo(30);
        assertThat(tail.length()).isLessThanOrEqualTo(ToolProcess.MAX_TAIL_CHARS);
    }

    private static Set<Long> childPids() {
        return ProcessHandle.current().children().filter(ProcessHandle::isAlive)
                .map(ProcessHandle::pid).collect(Collectors.toSet());
    }

    private static Set<Long> newChildren(Set<Long> before) {
        Set<Long> now = childPids();
        now.removeAll(before);
        return now;
    }
}
