package io.github.raulgl422.cliphive.server.llm;

import io.github.raulgl422.cliphive.server.service.tool.ExternalToolLocator;
import io.github.raulgl422.cliphive.server.service.tool.ProcessRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * LlmClient implementation that calls {@code claude -p} as an external process
 * (docs/DESIGN.md §10.1). Consumes the Agent SDK monthly credit.
 *
 * <p>The prompt is passed as the {@code -p} argument; {@code stdinContent} is
 * written to the process's stdin so the transcript JSON isn't part of the
 * command line. A call that hasn't answered in {@link #TIMEOUT} is killed (a
 * stuck CLI — waiting on a login, a stalled network — would otherwise hold
 * the caller forever).
 */
@Component
@Primary
public class CliLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(CliLlmClient.class);

    /** The longest analysis (a 2 h transcript) takes a few minutes; this is well past it. */
    static final Duration TIMEOUT = Duration.ofMinutes(15);

    private final ExternalToolLocator toolLocator;

    public CliLlmClient(ExternalToolLocator toolLocator) {
        this.toolLocator = toolLocator;
    }

    @Override
    public String call(String prompt, String stdinContent) throws IOException, InterruptedException {
        Path claude = toolLocator.claude();
        // No tools, in an empty folder: the transcript and metadata come from
        // third-party videos, and a crafted text must not get it to read the
        // server's files into what gets published (DESIGN.md §13).
        Path workDir = Files.createTempDirectory("cliphive-claude-");
        ProcessRunner.Result result;
        try {
            ProcessBuilder pb = new ProcessBuilder(List.of(claude.toString(), "-p", prompt, "--tools", ""))
                    .directory(workDir.toFile());
            result = ProcessRunner.run(pb, stdinContent.getBytes(StandardCharsets.UTF_8), TIMEOUT);
        } finally {
            Files.deleteIfExists(workDir);
        }
        if (!result.ok()) {
            throw new IOException("claude -p exited with code " + result.exitCode()
                    + ": " + result.stderr().strip());
        }

        String response = result.stdout().strip();
        log.debug("claude -p returned {} chars", response.length());
        return response;
    }
}
