package io.github.raulgl422.cliphive.server.service;

import io.github.raulgl422.cliphive.server.model.Worker;
import io.github.raulgl422.cliphive.server.repository.WorkerRepository;
import io.github.raulgl422.cliphive.server.service.worker.WorkerSessionRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Worker registration, token generation and revocation — docs/DESIGN.md §12.2
 * ("Workers: alta (token mostrado una vez) y revocación").
 *
 * <p>The raw token is returned exactly once at creation; only its SHA-256 hash
 * is stored. Revoking closes the worker's active session if any.
 */
@Service
public class WorkerAdminService {

    private final WorkerRepository workerRepository;
    private final WorkerSessionRegistry sessionRegistry;

    public WorkerAdminService(WorkerRepository workerRepository,
                              WorkerSessionRegistry sessionRegistry) {
        this.workerRepository = workerRepository;
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * Registers a new worker and returns a {@link RegistrationResult} with the
     * plain-text token (shown once, never again).
     */
    @Transactional
    public RegistrationResult register(String name) {
        String token = generateToken();
        String tokenHash = sha256Hex(token);
        Worker worker = new Worker(name, tokenHash);
        workerRepository.save(worker);
        return new RegistrationResult(worker.getId(), worker.getName(), token);
    }

    @Transactional
    public void revoke(UUID workerId) {
        workerRepository.findById(workerId).ifPresent(worker -> {
            worker.revoke();
            workerRepository.save(worker);
            var connected = sessionRegistry.byWorkerId(workerId);
            if (connected != null) {
                connected.close();
            }
        });
    }

    public List<Worker> listAll() {
        return workerRepository.findAll();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 hex of a worker token: what's stored at registration and compared on HELLO. */
    public static String sha256Hex(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record RegistrationResult(UUID id, String name, String token) {}
}
