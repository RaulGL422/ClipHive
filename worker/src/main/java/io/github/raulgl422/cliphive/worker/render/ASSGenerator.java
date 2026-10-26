package io.github.raulgl422.cliphive.worker.render;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generates an ASS karaoke subtitle file from a whisper.cpp word list
 * (docs/DESIGN.md §9.2 step 4).
 *
 * <p>Groups words into lines of up to {@value #MAX_WORDS_PER_GROUP} words or
 * ~{@value #MAX_CHARS_PER_GROUP} characters, preferring to cut at punctuation.
 * Each line uses {@code {\kf<n>}} karaoke tags so the active word is
 * progressively highlighted in the secondary color.
 *
 * <p>All timestamps in the word list are in <b>absolute video ms</b>. This
 * class converts them to clip-relative time by subtracting {@code clipStartMs}.
 *
 * <p>Font, size and colors are pending (docs/DESIGN.md §20.2): Arial 72 bold,
 * white + yellow, 1080×1920. The height comes from the server
 * ({@code subtitleMarginV}, px from the bottom — §9.2), with
 * {@value #DEFAULT_MARGIN_V} px if it doesn't send one.
 */
public class ASSGenerator {

    static final int MAX_WORDS_PER_GROUP = 5;
    static final int MAX_CHARS_PER_GROUP = 25;
    static final long MIN_GROUP_DURATION_MS = 600; // §9.2: at least 0.6 s on screen
    /** 25 % of 1920 from the bottom: above the strip the apps cover with text and buttons (§9.2). */
    public static final int DEFAULT_MARGIN_V = 480;
    /** On-screen title ~14 % from the top, under the apps' top tabs (§9.2). */
    static final int TITLE_MARGIN_V = 270;

    /** ASS header for 1080×1920 vertical video. Colors are in AABBGGRR (ASS ABGR). */
    private static final String HEADER = """
            [Script Info]
            ScriptType: v4.00+
            PlayResX: 1080
            PlayResY: 1920
            ScaledBorderAndShadow: yes

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
            Style: Default,Arial,72,&H00FFFFFF,&H0000FFFF,&H00000000,&H80000000,1,0,0,0,100,100,0,0,1,3,1,2,60,60,%d,1
            Style: Title,Arial,62,&H00FFFFFF,&H00FFFFFF,&H50000000,&H00000000,1,0,0,0,100,100,0,0,3,16,0,8,90,90,%d,1

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            """;

    /**
     * Generates the ASS file at {@code destFile}.
     *
     * @param words        word list from the transcript (each map has {@code text},
     *                     {@code startMs}, {@code endMs} in absolute video time)
     * @param clipStartMs  start of this clip in the original video (ms); all timestamps
     *                     are shifted by this to become clip-relative
     * @param destFile     output {@code .ass} file path
     */
    public void generate(List<Map<String, Object>> words, long clipStartMs, Path destFile)
            throws IOException {
        generate(words, clipStartMs, destFile, DEFAULT_MARGIN_V, null);
    }

    /**
     * Same, with the subtitles {@code marginV} px above the bottom edge of the
     * 1920 px frame (clamped so they stay on screen), plus {@code overlayTitle}
     * (DESIGN.md §10.3) at the top for the whole clip when it isn't blank.
     */
    public void generate(List<Map<String, Object>> words, long clipStartMs, Path destFile, int marginV,
                         String overlayTitle) throws IOException {

        List<Map<String, Object>> clipWords = shiftToClipTime(words, clipStartMs);
        List<WordGroup> groups = groupWords(clipWords);

        StringBuilder sb = new StringBuilder(header(marginV));
        if (overlayTitle != null && !overlayTitle.isBlank()) {
            sb.append(titleDialogue(overlayTitle)).append('\n');
        }
        for (WordGroup group : groups) {
            sb.append(buildDialogue(group)).append('\n');
        }

        Files.createDirectories(destFile.getParent());
        Files.writeString(destFile, sb.toString(), StandardCharsets.UTF_8);
    }

    static String header(int marginV) {
        return HEADER.formatted(Math.clamp(marginV, 0, 1600), TITLE_MARGIN_V);
    }

    /** The on-screen title, shown from the first frame to the end of the clip. */
    static String titleDialogue(String title) {
        // '{' '}' and '\' are ASS override syntax: keep them out of plain text
        String safe = title.replace('{', '(').replace('}', ')').replace('\\', '/')
                .replaceAll("\\s+", " ").strip();
        return "Dialogue: 1,0:00:00.00,9:59:59.99,Title,,0,0,0,," + safe;
    }

    // ── Grouping ──────────────────────────────────────────────────────────────

    List<WordGroup> groupWords(List<Map<String, Object>> words) {
        List<WordGroup> groups = new ArrayList<>();
        int i = 0;
        while (i < words.size()) {
            List<Map<String, Object>> groupWords = new ArrayList<>();
            int charCount = 0;

            while (i < words.size() && groupWords.size() < MAX_WORDS_PER_GROUP) {
                Map<String, Object> word = words.get(i);
                String text = word.get("text").toString().strip();
                int newChars = charCount == 0 ? text.length() : text.length() + 1;

                if (!groupWords.isEmpty() && charCount + newChars > MAX_CHARS_PER_GROUP) break;

                groupWords.add(word);
                charCount += newChars;
                i++;

                // Prefer cutting at punctuation (comma, period, question, exclamation)
                if (!text.isEmpty() && isPunctuationEnd(text) && groupWords.size() >= 2) break;
            }

            if (groupWords.isEmpty()) break;

            long startMs = ((Number) groupWords.get(0).get("startMs")).longValue();
            long endMs   = ((Number) groupWords.get(groupWords.size() - 1).get("endMs")).longValue();
            // Ensure minimum on-screen duration
            if (endMs - startMs < MIN_GROUP_DURATION_MS) {
                endMs = startMs + MIN_GROUP_DURATION_MS;
            }

            groups.add(new WordGroup(startMs, endMs, groupWords));
        }
        return groups;
    }

    // ── ASS line building ─────────────────────────────────────────────────────

    String buildDialogue(WordGroup group) {
        StringBuilder text = new StringBuilder();
        for (int j = 0; j < group.words().size(); j++) {
            Map<String, Object> word = group.words().get(j);
            String wordText = word.get("text").toString().strip();
            long wordStart = ((Number) word.get("startMs")).longValue();
            long wordEnd   = ((Number) word.get("endMs")).longValue();

            // Duration in centiseconds for {\kf<n>}
            long durCs = Math.max(1, (wordEnd - wordStart) / 10);
            text.append("{\\kf").append(durCs).append("}").append(wordText);
            if (j < group.words().size() - 1) text.append(' ');
        }
        return "Dialogue: 0,%s,%s,Default,,0,0,0,,%s".formatted(
                assTime(group.startMs()),
                assTime(group.endMs()),
                text
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Map<String, Object>> shiftToClipTime(List<Map<String, Object>> words, long clipStartMs) {
        List<Map<String, Object>> result = new ArrayList<>(words.size());
        for (Map<String, Object> word : words) {
            long start = ((Number) word.get("startMs")).longValue() - clipStartMs;
            long end   = ((Number) word.get("endMs")).longValue()   - clipStartMs;
            if (end < 0) continue; // before clip start — skip
            start = Math.max(0, start);
            Map<String, Object> shifted = new LinkedHashMap<>(word);
            shifted.put("startMs", start);
            shifted.put("endMs",   end);
            result.add(shifted);
        }
        return result;
    }

    /** Formats milliseconds as ASS time {@code H:MM:SS.cc}. */
    static String assTime(long ms) {
        long totalCs = ms / 10;
        long cs = totalCs % 100;
        long totalS = totalCs / 100;
        long s = totalS % 60;
        long totalM = totalS / 60;
        long m = totalM % 60;
        long h = totalM / 60;
        return "%d:%02d:%02d.%02d".formatted(h, m, s, cs);
    }

    private boolean isPunctuationEnd(String text) {
        char last = text.charAt(text.length() - 1);
        return last == ',' || last == '.' || last == '?' || last == '!' || last == ';';
    }

    record WordGroup(long startMs, long endMs, List<Map<String, Object>> words) {}
}
