package io.github.raulgl422.cliphive.server.service.worker;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory index of currently connected workers, keyed by
 * worker id (one connection per worker at a time — a second HELLO from the
 * same worker evicts the previous session before registering the new one).
 *
 * <p>Also keyed by WebSocket session id for fast lookup on disconnect.
 */
@Component
public class WorkerSessionRegistry {

    private final ConcurrentHashMap<UUID, ConnectedWorker> byWorkerId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConnectedWorker> byWsSessionId = new ConcurrentHashMap<>();

    public void register(ConnectedWorker cw) {
        byWorkerId.put(cw.workerId(), cw);
        byWsSessionId.put(cw.wsSession.getId(), cw);
    }

    public void unregister(String wsSessionId) {
        ConnectedWorker cw = byWsSessionId.remove(wsSessionId);
        if (cw != null) {
            byWorkerId.remove(cw.workerId(), cw);
        }
    }

    public ConnectedWorker byWsSessionId(String wsSessionId) {
        return byWsSessionId.get(wsSessionId);
    }

    public ConnectedWorker byWorkerId(UUID workerId) {
        return byWorkerId.get(workerId);
    }

    public Collection<ConnectedWorker> all() {
        return Collections.unmodifiableCollection(byWorkerId.values());
    }
}
