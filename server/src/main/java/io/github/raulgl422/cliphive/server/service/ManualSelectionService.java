package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.protocol.state.VideoJobState;
import io.github.raulgl422.cliphive.server.model.VideoJob;
import io.github.raulgl422.cliphive.server.repository.VideoJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manual clip mode (DESIGN.md §4.1): ranges marked by hand in the superadmin
 * become the job's clips instead of Claude's proposals.
 */
@Service
public class ManualSelectionService {

    private static final Logger log = LoggerFactory.getLogger(ManualSelectionService.class);

    private final VideoJobRepository jobs;
    private final SettingService settings;
    private final LogEventService logEvents;
    private final TransactionTemplate tx;

    public ManualSelectionService(VideoJobRepository jobs, SettingService settings,
                                  LogEventService logEvents, TransactionTemplate tx) {
        this.jobs = jobs;
        this.settings = settings;
        this.logEvents = logEvents;
        this.tx = tx;
    }

    /** The ranges can still be edited: manual job, not confirmed, not finished. */
    public static boolean isEditable(VideoJob job) {
        return job.isManual() && !job.isSelectionConfirmed() && !job.isTerminal();
    }

    /**
     * Adds a range, kept sorted by start.
     *
     * @throws IllegalArgumentException with a message for the superadmin when
     *         the range is invalid (too short/long, past the end, overlapping)
     */
    public void addRange(UUID jobId, long startMs, long endMs) {
        tx.executeWithoutResult(s -> {
            VideoJob job = editableJob(jobId);
            long min = settings.clipMinMs();
            long max = settings.clipMaxMs();
            if (startMs < 0 || endMs <= startMs) {
                throw new IllegalArgumentException("El fin tiene que ir después del inicio.");
            }
            long length = endMs - startMs;
            if (length < min) {
                throw new IllegalArgumentException("El clip dura " + seconds(length) + " s; el mínimo es "
                        + seconds(min) + " s.");
            }
            if (length > max) {
                throw new IllegalArgumentException("El clip dura " + seconds(length) + " s; el máximo es "
                        + seconds(max) + " s.");
            }
            if (job.getDurationSeconds() != null && endMs > job.getDurationSeconds() * 1000L) {
                throw new IllegalArgumentException("El fin está después del final del vídeo.");
            }
            List<Map<String, Object>> ranges = new ArrayList<>(
                    job.getManualRangesJson() == null ? List.of() : job.getManualRangesJson());
            for (Map<String, Object> r : ranges) {
                if (startMs < ms(r, "endMs") && endMs > ms(r, "startMs")) {
                    throw new IllegalArgumentException("Se solapa con otro clip ya marcado.");
                }
            }
            Map<String, Object> range = new LinkedHashMap<>();
            range.put("startMs", startMs);
            range.put("endMs", endMs);
            ranges.add(range);
            ranges.sort(Comparator.comparingLong(r -> ms(r, "startMs")));
            job.setManualRangesJson(ranges);
            jobs.save(job);
        });
    }

    public void removeRange(UUID jobId, int index) {
        tx.executeWithoutResult(s -> {
            VideoJob job = editableJob(jobId);
            List<Map<String, Object>> ranges = new ArrayList<>(
                    job.getManualRangesJson() == null ? List.of() : job.getManualRangesJson());
            if (index < 0 || index >= ranges.size()) return;
            ranges.remove(index);
            job.setManualRangesJson(ranges);
            jobs.save(job);
        });
    }

    /**
     * Confirms the ranges: they can't change any more. If the transcription
     * already finished, the job goes on to clip planning right away.
     */
    public void confirm(UUID jobId) {
        tx.executeWithoutResult(s -> {
            VideoJob job = editableJob(jobId);
            int count = job.getManualRangesJson() == null ? 0 : job.getManualRangesJson().size();
            if (count == 0) throw new IllegalArgumentException("Marca al menos un clip antes de confirmar.");
            job.setSelectionConfirmed(true);
            if (job.getState() == VideoJobState.AWAITING_SELECTION) {
                job.setState(VideoJobState.ANALYZING); // RenderTriggerScheduler picks it up
            }
            jobs.save(job);
            log.info("Job {}: {} manual clip range(s) confirmed", jobId, count);
            logEvents.info("trabajos", count + " clip(s) elegidos a mano confirmados", jobId.toString());
        });
    }

    /**
     * Moves each range's ends to the edges of the words they fall inside, so a
     * clip never starts or ends halfway through a word.
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> snapToWords(List<Map<String, Object>> ranges,
                                                        Map<String, Object> stitched) {
        List<long[]> words = new ArrayList<>();
        for (Map<String, Object> seg : (List<Map<String, Object>>) stitched.getOrDefault("segments", List.of())) {
            for (Map<String, Object> w : (List<Map<String, Object>>) seg.getOrDefault("words", List.of())) {
                words.add(new long[]{ms(w, "startMs"), ms(w, "endMs")});
            }
        }
        List<Map<String, Object>> snapped = new ArrayList<>();
        for (Map<String, Object> r : ranges) {
            long start = ms(r, "startMs");
            long end = ms(r, "endMs");
            for (long[] w : words) {
                if (start > w[0] && start < w[1]) start = w[0];
                if (end > w[0] && end < w[1]) end = w[1];
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("startMs", start);
            m.put("endMs", end);
            snapped.add(m);
        }
        return snapped;
    }

    private VideoJob editableJob(UUID jobId) {
        VideoJob job = jobs.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Trabajo no encontrado."));
        if (!isEditable(job)) {
            throw new IllegalArgumentException("Los cortes de este trabajo ya no se pueden cambiar.");
        }
        return job;
    }

    private static long ms(Map<String, Object> m, String key) {
        return ((Number) m.get(key)).longValue();
    }

    private static String seconds(long ms) {
        return String.valueOf(Math.round(ms / 100.0) / 10.0);
    }
}
