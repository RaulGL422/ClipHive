package io.github.raulgl422.cliphive.server.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per WebSocket connection a worker ever made - docs/DESIGN.md
 * section 6.5. Lets the superadmin show connection history, not just the
 * worker's current state.
 */
@Entity
@Table(name = "worker_session")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WorkerSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "worker_id", nullable = false)
    private Worker worker;

    @Column(name = "connected_at", nullable = false, updatable = false)
    private Instant connectedAt;

    @Column(name = "disconnected_at")
    private Instant disconnectedAt;

    @Column(name = "disconnect_reason")
    private String disconnectReason;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    public WorkerSession(Worker worker) {
        this.worker = worker;
        this.connectedAt = Instant.now();
    }

    /** Marks this session closed, voluntarily or otherwise. */
    public void close(String reason) {
        this.disconnectedAt = Instant.now();
        this.disconnectReason = reason;
    }
}
