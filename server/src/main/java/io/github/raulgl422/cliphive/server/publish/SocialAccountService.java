package io.github.raulgl422.cliphive.server.publish;

import io.github.raulgl422.cliphive.server.model.SocialAccount;
import io.github.raulgl422.cliphive.server.model.enums.Platform;
import io.github.raulgl422.cliphive.server.properties.ClipHiveProperties;
import io.github.raulgl422.cliphive.server.repository.SocialAccountRepository;
import io.github.raulgl422.cliphive.server.security.EncryptionService;
import io.github.raulgl422.cliphive.server.service.LogEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Connected social accounts (DESIGN.md §11.6): connect through OAuth, keep
 * tokens encrypted at rest (§13) and hand out a valid access token,
 * refreshing it shortly before it expires (§11.2).
 */
@Service
public class SocialAccountService {

    private static final Logger log = LoggerFactory.getLogger(SocialAccountService.class);

    private final SocialAccountRepository accounts;
    private final EncryptionService encryption;
    private final ClipHiveProperties props;
    private final LogEventService logEvents;
    private final Map<Platform, OAuthProvider> providers = new EnumMap<>(Platform.class);
    private final Map<Platform, ReentrantLock> refreshLocks = new ConcurrentHashMap<>();

    public SocialAccountService(SocialAccountRepository accounts,
                                EncryptionService encryption,
                                ClipHiveProperties props,
                                LogEventService logEvents,
                                List<OAuthProvider> providers) {
        this.accounts = accounts;
        this.encryption = encryption;
        this.props = props;
        this.logEvents = logEvents;
        providers.forEach(p -> this.providers.put(p.platform(), p));
    }

    public List<SocialAccount> all() {
        return accounts.findAll();
    }

    /** The account a platform publishes to (one per platform — §11.6). */
    public Optional<SocialAccount> accountFor(Platform platform) {
        return accounts.findByPlatform(platform).stream().findFirst();
    }

    /** Platforms with an OAuth implementation, configured or not. */
    public boolean isSupported(Platform platform) {
        return providers.containsKey(platform);
    }

    /** True when the platform is supported and its app credentials are in {@code .env}. */
    public boolean isConfigured(Platform platform) {
        OAuthProvider p = providers.get(platform);
        return p != null && p.isConfigured() && StringUtils.hasText(props.getAdminPublicUrl());
    }

    /** {@code <CLIPHIVE_ADMIN_PUBLIC_URL>/admin/accounts/<platform>/callback} — register it in the platform. */
    public String redirectUri(Platform platform) {
        String base = props.getAdminPublicUrl();
        if (!StringUtils.hasText(base)) throw new IllegalStateException("CLIPHIVE_ADMIN_PUBLIC_URL is not set");
        return base.replaceAll("/+$", "") + "/admin/accounts/" + platform.name().toLowerCase() + "/callback";
    }

    public String authorizationUrl(Platform platform, String state) {
        return provider(platform).authorizationUrl(state, redirectUri(platform));
    }

    /** Finishes the OAuth flow: stores (or updates) the account with encrypted tokens. */
    public SocialAccount connect(Platform platform, String code) throws PublishException {
        OAuthTokens tokens = provider(platform).exchangeCode(code, redirectUri(platform));
        String accessToken = encryption.encrypt(tokens.accessToken());
        SocialAccount account = accounts.findByPlatformAndExternalAccountId(platform, tokens.accountId())
                .orElseGet(() -> new SocialAccount(platform, tokens.accountId(), accessToken));
        account.setAccessTokenEncrypted(accessToken);
        if (tokens.refreshToken() != null) {
            account.setRefreshTokenEncrypted(encryption.encrypt(tokens.refreshToken()));
        }
        account.setExpiresAt(tokens.expiresAt());
        account.setDisplayName(tokens.displayName());
        SocialAccount saved = accounts.save(account);
        log.info("{} account connected: {} ({})", platform, tokens.displayName(), tokens.accountId());
        logEvents.info("cuentas", platform + " conectado: " + tokens.displayName());
        return saved;
    }

    public void disconnect(UUID accountId) {
        accounts.findById(accountId).ifPresent(a -> {
            accounts.delete(a);
            log.info("{} account disconnected: {}", a.getPlatform(), a.getDisplayName());
            logEvents.info("cuentas", a.getPlatform() + " desconectado: " + a.getDisplayName());
        });
    }

    /** A usable access token, refreshed first if it's about to expire. */
    public String accessToken(SocialAccount account) throws PublishException {
        Instant expiresAt = account.getExpiresAt();
        OAuthProvider provider = providers.get(account.getPlatform());
        Duration margin = provider != null ? provider.refreshMargin() : Duration.ofMinutes(5);
        if (expiresAt == null || provider == null || expiresAt.isAfter(Instant.now().plus(margin))) {
            return encryption.decrypt(account.getAccessTokenEncrypted());
        }
        // One refresh per platform at a time: the upload and stats threads can
        // both find the token expiring, and TikTok rotates the refresh token on
        // every use, so the second refresh would spend one that's already gone.
        ReentrantLock lock = refreshLocks.computeIfAbsent(account.getPlatform(), p -> new ReentrantLock());
        lock.lock();
        try {
            SocialAccount current = accounts.findById(account.getId()).orElse(account);
            Instant currentExpiry = current.getExpiresAt();
            if (currentExpiry != null && currentExpiry.isAfter(Instant.now().plus(margin))) {
                return encryption.decrypt(current.getAccessTokenEncrypted());   // the other thread refreshed it
            }
            if (current.getRefreshTokenEncrypted() == null) {
                throw new PublishException("El token de " + current.getPlatform()
                        + " ha caducado y no hay refresh token: vuelve a conectar la cuenta", false);
            }
            OAuthTokens fresh = provider(current.getPlatform())
                    .refresh(encryption.decrypt(current.getRefreshTokenEncrypted()));
            current.setAccessTokenEncrypted(encryption.encrypt(fresh.accessToken()));
            if (fresh.refreshToken() != null) {
                current.setRefreshTokenEncrypted(encryption.encrypt(fresh.refreshToken()));
            }
            current.setExpiresAt(fresh.expiresAt());
            accounts.save(current);
            log.debug("{} access token refreshed", current.getPlatform());
            return fresh.accessToken();
        } finally {
            lock.unlock();
        }
    }

    private OAuthProvider provider(Platform platform) {
        OAuthProvider p = providers.get(platform);
        if (p == null) throw new IllegalStateException("No OAuth implementation for " + platform);
        return p;
    }
}
