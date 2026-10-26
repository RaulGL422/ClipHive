package io.github.raulgl422.cliphive.server.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The server's guarantees over Claude's editorial plan (DESIGN.md §10.2):
 * clips in order without overlaps, the whole content covered (in full
 * coverage), filler never inside a clip, long clips split at sentence ends,
 * short ones merged with a neighbour, and the parts of each section numbered.
 * Everything works on segment indices — segment edges are pauses between
 * sentences, so no clip ever starts or ends mid-sentence. Pure, no I/O; notes
 * for the superadmin are in Spanish.
 */
final class EditorialPlan {

    /** A transcript segment: index, times and text. */
    record Segment(int index, long startMs, long endMs, String text) {}

    /** A section as Claude proposed it: title and its clips as inclusive segment index ranges. */
    record Section(String title, List<int[]> clips) {}

    /** A clip of the final plan: inclusive segment range, times, and its place in its section. */
    record PlannedClip(int from, int to, long startMs, long endMs, String section, int part, int parts) {}

    record Result(List<PlannedClip> clips, List<String> notes, double proposedCoverage) {}

    /** A clip while it's being worked on. */
    private static final class Work {
        int from;
        int to;
        final int section;

        Work(int from, int to, int section) {
            this.from = from;
            this.to = to;
            this.section = section;
        }
    }

    private EditorialPlan() {
    }

    /**
     * @param contentStart first useful segment (null: the first)
     * @param contentEnd   last useful segment (null: the last)
     * @param removed      filler segments (Claude's and SponsorBlock's)
     * @param fullCoverage cover everything between start and end except filler
     */
    static Result build(List<Segment> segments, Integer contentStart, Integer contentEnd, Set<Integer> removed,
                        List<Section> sections, long minMs, long maxMs, boolean fullCoverage) {
        List<String> notes = new ArrayList<>();
        int n = segments.size();
        if (n == 0) return new Result(List.of(), notes, 0);
        int cs = contentStart == null ? 0 : Math.clamp(contentStart, 0, n - 1);
        int ce = contentEnd == null ? n - 1 : Math.clamp(contentEnd, cs, n - 1);
        boolean[] keep = new boolean[n];
        for (int i = cs; i <= ce; i++) keep[i] = !removed.contains(i);

        // 1. Claude's clips, in order and without overlaps
        List<String> titles = new ArrayList<>();
        List<Work> clips = new ArrayList<>();
        for (int s = 0; s < sections.size(); s++) {
            titles.add(sections.get(s).title() == null ? "" : sections.get(s).title().strip());
            for (int[] c : sections.get(s).clips()) {
                int from = Math.clamp(Math.min(c[0], c[1]), cs, ce);
                int to = Math.clamp(Math.max(c[0], c[1]), cs, ce);
                clips.add(new Work(from, to, s));
            }
        }
        clips.sort(Comparator.comparingInt((Work w) -> w.from).thenComparingInt(w -> w.to));
        List<Work> ordered = new ArrayList<>();
        for (Work w : clips) {
            if (!ordered.isEmpty() && w.from <= ordered.getLast().to) {
                w.from = ordered.getLast().to + 1;
                if (w.from > w.to) {
                    notes.add("Un clip propuesto quedaba dentro de otro: descartado.");
                    continue;
                }
                notes.add("Dos clips se solapaban: el segundo empieza donde acaba el primero.");
            }
            ordered.add(w);
        }
        clips = ordered;

        double keepTotal = 0;
        double keepCovered = 0;
        boolean[] owned = new boolean[n];
        for (Work w : clips) for (int i = w.from; i <= w.to; i++) owned[i] = true;
        for (int i = 0; i < n; i++) {
            if (!keep[i]) continue;
            double d = segments.get(i).endMs() - segments.get(i).startMs();
            keepTotal += d;
            if (owned[i]) keepCovered += d;
        }
        double coverage = keepTotal == 0 ? 1 : keepCovered / keepTotal;

        // 2. Gaps: nothing of the content is left out (full coverage)
        if (fullCoverage) {
            int i = 0;
            while (i < n) {
                if (!keep[i] || owned[i]) { i++; continue; }
                int runStart = i;
                while (i + 1 < n && keep[i + 1] && !owned[i + 1]) i++;
                int runEnd = i;
                Work prev = null;
                Work next = null;
                for (Work w : clips) {
                    if (w.to < runStart) prev = w;
                    if (w.from > runEnd && next == null) next = w;
                }
                if (prev != null && prev.to == runStart - 1) {
                    prev.to = runEnd;
                } else if (next != null && next.from == runEnd + 1) {
                    next.from = runStart;
                } else {
                    int section = prev != null ? prev.section : next != null ? next.section : sectionFor(titles);
                    Work gap = new Work(runStart, runEnd, section);
                    clips.add(gap);
                    clips.sort(Comparator.comparingInt((Work w) -> w.from));
                }
                notes.add("Faltaba el tramo " + time(segments.get(runStart).startMs()) + "–"
                        + time(segments.get(runEnd).endMs()) + ": añadido para no saltarse nada.");
                for (int k = runStart; k <= runEnd; k++) owned[k] = true;
                i = runEnd + 1;
            }
        }

        // 3. Filler is never inside a clip: split around it
        List<Work> clean = new ArrayList<>();
        for (Work w : clips) {
            List<Work> runs = new ArrayList<>();
            int start = -1;
            for (int i = w.from; i <= w.to + 1; i++) {
                boolean ok = i <= w.to && keep[i];
                if (ok && start < 0) start = i;
                if (!ok && start >= 0) {
                    runs.add(new Work(start, i - 1, w.section));
                    start = -1;
                }
            }
            if (runs.size() > 1) notes.add("Se quitó relleno (patrocinio, saludo…) de dentro de un clip.");
            clean.addAll(runs);
        }
        clips = clean;

        // 4. Too long: split at sentence ends into parts of similar length
        List<Work> sized = new ArrayList<>();
        for (Work w : clips) {
            if (duration(segments, w) <= maxMs) {
                sized.add(w);
                continue;
            }
            List<Work> parts = split(segments, w, maxMs);
            notes.add("Un clip de " + time(duration(segments, w)) + " pasaba del máximo: dividido en "
                    + parts.size() + " partes por finales de frase.");
            sized.addAll(parts);
        }
        clips = sized;

        // 5. Too short: join the neighbour of the same section when it fits
        boolean merged = true;
        while (merged) {
            merged = false;
            for (int k = 0; k < clips.size(); k++) {
                Work w = clips.get(k);
                if (duration(segments, w) >= minMs) continue;
                Work prev = k > 0 ? clips.get(k - 1) : null;
                Work next = k + 1 < clips.size() ? clips.get(k + 1) : null;
                if (prev != null && prev.section == w.section && prev.to + 1 == w.from
                        && span(segments, prev.from, w.to) <= maxMs) {
                    prev.to = w.to;
                } else if (next != null && next.section == w.section && w.to + 1 == next.from
                        && span(segments, w.from, next.to) <= maxMs) {
                    next.from = w.from;
                } else {
                    continue;
                }
                clips.remove(k);
                notes.add("Un clip de " + time(duration(segments, w)) + " era muy corto: unido al de al lado.");
                merged = true;
                break;
            }
        }

        // 6. Number the parts of each section
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        clips.forEach(w -> counts.merge(w.section, 1, Integer::sum));
        Map<Integer, Integer> seen = new LinkedHashMap<>();
        List<PlannedClip> out = new ArrayList<>();
        for (Work w : clips) {
            int part = seen.merge(w.section, 1, Integer::sum);
            String title = w.section < titles.size() ? titles.get(w.section) : "";
            out.add(new PlannedClip(w.from, w.to, segments.get(w.from).startMs(), segments.get(w.to).endMs(),
                    title.isBlank() ? null : title, part, counts.get(w.section)));
        }
        return new Result(out, List.copyOf(new LinkedHashSet<>(notes)), coverage);
    }

    /**
     * Content segments no proposed clip covers, as inclusive index runs — what
     * Claude is told it left out when it asks again (DESIGN.md §10.2).
     */
    static List<int[]> missingRuns(int n, Integer contentStart, Integer contentEnd, Set<Integer> removed,
                                   List<Section> sections) {
        List<int[]> runs = new ArrayList<>();
        if (n == 0) return runs;
        int cs = contentStart == null ? 0 : Math.clamp(contentStart, 0, n - 1);
        int ce = contentEnd == null ? n - 1 : Math.clamp(contentEnd, cs, n - 1);
        boolean[] owned = new boolean[n];
        for (Section s : sections) {
            for (int[] c : s.clips()) {
                for (int i = Math.max(0, Math.min(c[0], c[1])); i <= Math.min(n - 1, Math.max(c[0], c[1])); i++) {
                    owned[i] = true;
                }
            }
        }
        int start = -1;
        for (int i = cs; i <= ce + 1; i++) {
            boolean missing = i <= ce && !owned[i] && !removed.contains(i);
            if (missing && start < 0) start = i;
            if (!missing && start >= 0) {
                runs.add(new int[]{start, i - 1});
                start = -1;
            }
        }
        return runs;
    }

    /** Parts of similar length, each ending at the sentence end nearest its share of the clip. */
    private static List<Work> split(List<Segment> segments, Work w, long maxMs) {
        long total = duration(segments, w);
        int k = (int) Math.ceil((double) total / maxMs);
        List<Work> parts = new ArrayList<>();
        int from = w.from;
        for (int p = 1; p < k && from < w.to; p++) {
            long remaining = segments.get(w.to).endMs() - segments.get(from).startMs();
            long target = segments.get(from).startMs() + remaining / (k - p + 1);
            int best = -1;
            long bestScore = Long.MAX_VALUE;
            for (int i = from; i < w.to; i++) {
                long end = segments.get(i).endMs();
                if (end - segments.get(from).startMs() > maxMs) break;
                long score = Math.abs(end - target);
                if (!endsSentence(segments.get(i).text())) score += maxMs / 4; // prefer full stops
                if (score < bestScore) {
                    bestScore = score;
                    best = i;
                }
            }
            if (best < 0) best = from; // a single segment longer than the maximum
            parts.add(new Work(from, best, w.section));
            from = best + 1;
        }
        if (from <= w.to) parts.add(new Work(from, w.to, w.section));
        // The last part may still be too long when segments are uneven: split it again
        List<Work> out = new ArrayList<>();
        for (Work part : parts) {
            if (duration(segments, part) > maxMs && part.from < part.to && parts.size() < 50) {
                out.addAll(split(segments, part, maxMs));
            } else {
                out.add(part);
            }
        }
        return out;
    }

    static boolean endsSentence(String text) {
        if (text == null) return false;
        String t = text.strip();
        return !t.isEmpty() && ".?!…»\"".indexOf(t.charAt(t.length() - 1)) >= 0;
    }

    private static long duration(List<Segment> segments, Work w) {
        return span(segments, w.from, w.to);
    }

    private static long span(List<Segment> segments, int from, int to) {
        return segments.get(to).endMs() - segments.get(from).startMs();
    }

    private static int sectionFor(List<String> titles) {
        titles.add("");
        return titles.size() - 1;
    }

    static String time(long ms) {
        long s = ms / 1000;
        return s / 60 + ":" + String.format("%02d", s % 60);
    }
}
