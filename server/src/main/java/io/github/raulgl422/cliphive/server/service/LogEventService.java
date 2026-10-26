package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.server.model.LogEvent;
import io.github.raulgl422.cliphive.server.repository.LogEventRepository;
import org.slf4j.event.Level;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Structured log storage and real-time broadcast — docs/DESIGN.md §12.2.
 * Persists to {@code log_event} and notifies all connected SSE subscribers.
 *
 * <p>Uses {@code REQUIRES_NEW} so a log write inside a failing transaction
 * still reaches the DB before the outer transaction rolls back.
 */
@Service
public class LogEventService {

    private final LogEventRepository logEventRepository;
    private final LogBroadcaster broadcaster;

    public LogEventService(LogEventRepository logEventRepository, LogBroadcaster broadcaster) {
        this.logEventRepository = logEventRepository;
        this.broadcaster = broadcaster;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void info(String component, String message) {
        record(component, Level.INFO, message, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void info(String component, String message, String jobId) {
        record(component, Level.INFO, message, jobId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void warn(String component, String message, String jobId) {
        record(component, Level.WARN, message, jobId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void error(String component, String message, String jobId) {
        record(component, Level.ERROR, message, jobId);
    }

    @Transactional(readOnly = true)
    public List<LogEvent> recent(int limit) {
        return logEventRepository.findTopN(PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public List<LogEvent> filtered(String level, String component, String jobId, int limit) {
        String lvl  = (level     == null || level.isBlank())     ? null : level.trim().toUpperCase();
        String comp = (component == null || component.isBlank()) ? null : "%" + component.trim() + "%";
        String jid  = (jobId     == null || jobId.isBlank())     ? null : jobId.trim();
        return logEventRepository.findFiltered(lvl, comp, jid, limit);
    }

    private void record(String component, Level level, String message, String jobId) {
        Map<String, Object> ctx = jobId != null ? Map.of("jobId", jobId) : null;
        var event = new LogEvent(level, component, message, ctx);
        logEventRepository.save(event);
        broadcaster.broadcast(level.name(), component, message, jobId);
    }
}
