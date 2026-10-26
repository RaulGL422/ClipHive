package io.github.raulgl422.cliphive.server.llm;

import java.io.IOException;

/**
 * Calls an LLM with a prompt and optional stdin context, returning raw text
 * (expected to be JSON). Callers parse and validate the response.
 *
 * <p>Two implementations (docs/DESIGN.md §10.1):
 * <ul>
 *   <li>{@link CliLlmClient} — {@code claude -p} (primary, consumes Agent SDK credit)
 *   <li>{@link ApiLlmClient} — Anthropic Messages API (requires {@code ANTHROPIC_API_KEY})
 * </ul>
 */
public interface LlmClient {

    /**
     * Calls the LLM with {@code prompt}; {@code stdinContent} is fed via stdin
     * (or appended to the message for API-based implementations).
     *
     * @return raw LLM response text, stripped of leading/trailing whitespace
     * @throws IOException          on process or network failure
     * @throws InterruptedException if the call is interrupted
     */
    String call(String prompt, String stdinContent) throws IOException, InterruptedException;

    /**
     * Extracts a JSON string from a response that may be wrapped in markdown
     * code fences (e.g. {@code ```json ... ```}) or have a sentence around it
     * ("Aquí tienes: {...}") — Claude adds one now and then even when asked
     * for JSON only, and the whole call used to fail.
     */
    static String extractJson(String raw) {
        String s = raw.strip();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline == -1) return s;
            s = s.substring(firstNewline + 1);
            int closing = s.lastIndexOf("```");
            if (closing >= 0) s = s.substring(0, closing);
            return s.strip();
        }
        if (s.startsWith("{") || s.startsWith("[")) return s;

        // Text around the JSON: take from the first opening brace/bracket to its
        // last closing counterpart.
        int brace = s.indexOf('{');
        int bracket = s.indexOf('[');
        int start = (brace < 0) ? bracket : (bracket < 0 ? brace : Math.min(brace, bracket));
        if (start < 0) return s;
        char close = s.charAt(start) == '{' ? '}' : ']';
        int end = s.lastIndexOf(close);
        return end > start ? s.substring(start, end + 1) : s;
    }
}
