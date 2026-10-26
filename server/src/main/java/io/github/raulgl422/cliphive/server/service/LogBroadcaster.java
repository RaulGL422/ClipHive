package io.github.raulgl422.cliphive.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory SSE broadcaster for the admin logs page — docs/DESIGN.md §12.2
 * ("Logs … en directo por SSE"). Registered emitters receive each new log event.
 * Completed/timed-out emitters are removed automatically.
 */
@Component
public class LogBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(LogBroadcaster.class);

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    public void broadcast(String level, String component, String message, String jobId) {
        if (emitters.isEmpty()) return;
        var data = Map.of(
                "level", level,
                "component", component,
                "message", message,
                "jobId", jobId != null ? jobId : "",
                "time", Instant.now().toString());
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().data(data));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }
    }
}
