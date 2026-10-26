package io.github.raulgl422.cliphive.server.publish;

import io.github.raulgl422.cliphive.server.model.enums.Platform;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * "Claude proposes, the server guarantees" (DESIGN.md §10.4): turns a list of
 * proposed {@code (clip, time)} into publications that are in today's window,
 * within each platform's remaining cap, spaced by the minimum gap, complete
 * and at the same time on every connected platform, within today's clip limit, and
 * always the first clips of the queue, which the planner hands over in
 * publishing order (videos in progress first): a clip that drops out takes
 * every later one with it, so videos never mix (DESIGN.md §10.4). Pure — no I/O — so every rule is unit-tested. Notes for the
 * superadmin are in Spanish.
 */
final class PlanValidator {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * A clip waiting in the queue, in queue order (oldest video first, then clip
     * index). {@code videoStarted}: its video already has clips published or
     * scheduled, so it's finished before a new one starts.
     */
    record Candidate(String key, UUID clipId, UUID jobId, int clipIndex, String title, boolean videoStarted) {
        Candidate(String key, UUID clipId, UUID jobId, int clipIndex, String title) {
            this(key, clipId, jobId, clipIndex, title, false);
        }
    }

    /** {@code platform} null: the time is for every platform (the normal case). */
    record Proposal(String clipKey, Platform platform, LocalTime time) {
        Proposal(String clipKey, LocalTime time) {
            this(clipKey, null, time);
        }
    }

    record Assignment(UUID clipId, Platform platform, Instant at) {}

    record Result(List<Assignment> assignments, List<String> adjustments) {}

    /**
     * @param remaining  publications each platform can still take today
     * @param occupied   times already taken today per platform (scheduled or published)
     */
    record Context(List<Platform> platforms, Map<Platform, Integer> remaining, Map<Platform, List<Instant>> occupied,
                   Duration minGap, Instant windowStart, Instant windowEnd, LocalDate day, ZoneId zone,
                   int maxClips) {
        /** No clip limit beyond each platform's cap. */
        Context(List<Platform> platforms, Map<Platform, Integer> remaining, Map<Platform, List<Instant>> occupied,
                Duration minGap, Instant windowStart, Instant windowEnd, LocalDate day, ZoneId zone) {
            this(platforms, remaining, occupied, minGap, windowStart, windowEnd, day, zone, Integer.MAX_VALUE);
        }
    }

    private PlanValidator() {
    }

    static Result validate(List<Candidate> queue, List<Proposal> proposals, Context ctx) {
        if (ctx.platforms().isEmpty()) return new Result(List.of(), List.of());
        List<String> notes = new ArrayList<>();
        Map<String, Candidate> byKey = new LinkedHashMap<>();
        queue.forEach(c -> byKey.put(c.key(), c));

        // 1. Usable proposals: known clip and, if a platform is named, a connected one. A clip goes
        //    out at the same time everywhere: with several times, the earliest one
        Map<String, LocalTime> wanted = new LinkedHashMap<>();
        Set<String> unified = new LinkedHashSet<>();
        for (Proposal p : proposals) {
            if (!byKey.containsKey(p.clipKey())) {
                notes.add("Clip desconocido \"" + p.clipKey() + "\": ignorado.");
                continue;
            }
            if (p.platform() != null && !ctx.platforms().contains(p.platform())) {
                notes.add(p.platform().label() + " no está conectado: ignorado.");
                continue;
            }
            LocalTime before = wanted.get(p.clipKey());
            if (before == null) {
                wanted.put(p.clipKey(), p.time());
            } else if (!before.equals(p.time())) {
                wanted.put(p.clipKey(), before.isBefore(p.time()) ? before : p.time());
                unified.add(p.clipKey());
            }
        }
        for (String key : unified) {
            notes.add(title(byKey.get(key)) + " tenía horas distintas por plataforma: sale a las "
                    + wanted.get(key).format(HHMM) + " en todas.");
        }

        // 2. The queue comes in publishing order (DESIGN.md §10.4): videos in progress first, each
        //    with its clips in order. Claude decides how many and when; asked for n clips, the first
        //    n of the queue go, taking the times from earliest to latest. So a video is finished
        //    before the next one starts and two videos never mix.
        List<LocalTime> asked = wanted.values().stream().sorted().toList();
        int n = Math.min(asked.size(), queue.size());
        if (n > ctx.maxClips()) {
            notes.add("Límite de hoy: " + ctx.maxClips() + " clip(s); los demás quedan en cola.");
            n = ctx.maxClips();
        }
        List<Candidate> first = queue.subList(0, n);
        Set<String> firstKeys = new HashSet<>();
        first.forEach(c -> firstKeys.add(c.key()));
        if (!firstKeys.equals(wanted.keySet())) {
            notes.add("Orden de la cola: salen los " + n + " primeros ("
                    + String.join(", ", first.stream().map(PlanValidator::title).toList()) + ").");
        }

        // 3. One time per clip for every platform: from the one wanted, the first that keeps the
        //    minimum gap on all of them at once, inside the window and with room on each. A clip
        //    that doesn't fit stays for another day with every clip after it in the queue
        //    (never part 2 without part 1, never the next video before this one ends)
        Map<Platform, List<Instant>> taken = new EnumMap<>(Platform.class);
        Map<Platform, Integer> room = new EnumMap<>(Platform.class);
        for (Platform platform : ctx.platforms()) {
            taken.put(platform, new ArrayList<>(ctx.occupied().getOrDefault(platform, List.of())));
            room.put(platform, ctx.remaining().getOrDefault(platform, 0));
        }
        record Wanted(Candidate clip, LocalTime time) {}
        List<Wanted> order = new ArrayList<>();
        for (int i = 0; i < first.size(); i++) order.add(new Wanted(first.get(i), asked.get(i)));
        Map<Candidate, Instant> placed = new LinkedHashMap<>();
        boolean cut = false;
        for (Wanted w : order) {
            Candidate c = w.clip();
            if (cut) {
                notes.add(title(c) + " queda para otro día: va después de un clip que no cabía hoy.");
                continue;
            }
            List<Platform> full = ctx.platforms().stream().filter(p -> room.get(p) <= 0).toList();
            if (!full.isEmpty()) {
                notes.add(String.join(", ", full.stream().map(Platform::label).toList())
                        + ": tope diario alcanzado, " + title(c) + " no cabe hoy.");
                cut = true;
                continue;
            }
            Instant want = at(ctx, w.time());
            Instant t = want.isBefore(ctx.windowStart()) ? ctx.windowStart() : want;
            boolean moved;
            do {
                moved = false;
                for (List<Instant> onPlatform : taken.values()) {
                    for (Instant o : onPlatform) {
                        if (Duration.between(o, t).abs().compareTo(ctx.minGap()) < 0) {
                            t = o.plus(ctx.minGap());
                            moved = true;
                        }
                    }
                }
            } while (moved);
            if (t.isAfter(ctx.windowEnd())) {
                notes.add(title(c) + " no cabe hoy dentro del horario.");
                cut = true;
                continue;
            }
            if (!t.equals(want)) {
                notes.add(title(c) + " pasa de " + w.time().format(HHMM) + " a " + hhmm(ctx, t)
                        + " (hora pasada o separación mínima).");
            }
            for (Platform platform : ctx.platforms()) {
                taken.get(platform).add(t);
                room.merge(platform, -1, Integer::sum);
            }
            placed.put(c, t);
        }

        // 4. The clips in queue order get the placed times in ascending order
        List<Candidate> sequence = new ArrayList<>(placed.keySet());
        List<Instant> slots = placed.values().stream().sorted().toList();
        List<Assignment> assignments = new ArrayList<>();
        for (int i = 0; i < sequence.size(); i++) {
            for (Platform platform : ctx.platforms()) {
                assignments.add(new Assignment(sequence.get(i).clipId(), platform, slots.get(i)));
            }
        }
        return new Result(assignments, List.copyOf(new LinkedHashSet<>(notes)));
    }


    /**
     * The fallback when Claude fails: the default slots on every platform,
     * the oldest clips first.
     */
    static List<Proposal> fallback(List<Candidate> queue, List<Platform> platforms, List<LocalTime> slots) {
        List<Proposal> proposals = new ArrayList<>();
        Set<String> used = new HashSet<>();
        List<LocalTime> sortedSlots = slots.stream().sorted().toList();
        for (int i = 0; i < sortedSlots.size() && i < queue.size(); i++) {
            Candidate c = queue.get(i);
            if (!used.add(c.key())) continue;
            proposals.add(new Proposal(c.key(), sortedSlots.get(i)));
        }
        return proposals;
    }

    private static Instant at(Context ctx, LocalTime time) {
        return ctx.day().atTime(time).atZone(ctx.zone()).toInstant();
    }

    private static String hhmm(Context ctx, Instant t) {
        return t.atZone(ctx.zone()).toLocalTime().format(HHMM);
    }

    private static String title(Candidate c) {
        return c.title() == null || c.title().isBlank() ? "el clip " + c.key() : "\"" + c.title() + "\"";
    }
}
