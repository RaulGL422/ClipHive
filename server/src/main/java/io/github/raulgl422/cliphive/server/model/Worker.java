package io.github.raulgl422.cliphive.server.model;

import io.github.raulgl422.cliphive.protocol.message.WorkerCapabilities;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A registered worker - docs/DESIGN.md section 7 ("Alta de workers"). Only
 * {@link #tokenHash} is stored; the real token is shown once at creation and
 * never again. Revoking a worker (setting {@link #revokedAt}) must close its
 * active WebSocket session.
 *
 * <p>{@link #lastCapabilities} reuses {@code protocol}'s own
 * {@link WorkerCapabilities} record directly as the JSON column's Java type
 * - it's exactly the payload the worker reports in {@code HELLO}, so there's
 * no reason to duplicate its shape here.
 *
 * <p>Token hash and revocation only change through the methods below.
 */
@Entity
@Table(name = "worker")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Worker {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Setter
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "last_capabilities")
    private WorkerCapabilities lastCapabilities;

    @Setter
    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Worker(String name, String tokenHash) {
        this.name = name;
        this.tokenHash = tokenHash;
        this.createdAt = Instant.now();
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** Revokes this worker's token, effective immediately. */
    public void revoke() {
        this.revokedAt = Instant.now();
    }
}
