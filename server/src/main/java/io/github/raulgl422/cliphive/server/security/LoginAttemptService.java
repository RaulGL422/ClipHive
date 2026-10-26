package io.github.raulgl422.cliphive.server.security;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory rate-limiter for the admin login — docs/DESIGN.md §12.1
 * ("rate limiting y bloqueo tras varios fallos"). Keyed by IP for the
 * password and by account for the TOTP code.
 *
 * <p>Each try takes a slot with {@link #tryAcquire} <em>before</em> the
 * password or code is checked, and a success gives them back. Checking and
 * counting in one atomic step is what caps a key at {@value #MAX_ATTEMPTS}
 * tries: checking first and counting the failure afterwards would let a burst
 * of parallel requests all pass the check. The key is then blocked for
 * {@value #BLOCK_SECONDS} seconds, cleared lazily on the next check.
 */
@Service
public class LoginAttemptService {

    static final int MAX_ATTEMPTS = 5;
    static final int BLOCK_SECONDS = 900; // 15 minutes

    record Attempt(int count, Instant blockedUntil) {
        boolean blockedAt(Instant now) { return blockedUntil != null && now.isBefore(blockedUntil); }
    }

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    /** The key used up its tries and the block hasn't expired yet. */
    public boolean isBlocked(String key) {
        Attempt a = attempts.get(key);
        return a != null && a.blockedAt(Instant.now());
    }

    /**
     * Takes one try for {@code key}: false if it's blocked (don't check the
     * credential at all), true otherwise. The try that reaches the limit
     * starts the block, so a wrong guess there leaves the key blocked.
     */
    public boolean tryAcquire(String key) {
        Instant now = Instant.now();
        boolean[] allowed = {false};
        attempts.compute(key, (k, prev) -> {
            if (prev != null && prev.blockedAt(now)) return prev;
            int count = (prev == null || prev.blockedUntil() != null ? 0 : prev.count()) + 1;
            allowed[0] = true;
            return new Attempt(count, count >= MAX_ATTEMPTS ? now.plusSeconds(BLOCK_SECONDS) : null);
        });
        return allowed[0];
    }

    /** The credential was right: forget the tries. */
    public void recordSuccess(String key) {
        attempts.remove(key);
    }
}
