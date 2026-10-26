package io.github.raulgl422.cliphive.server.publish;

import io.github.raulgl422.cliphive.server.model.SocialAccount;
import io.github.raulgl422.cliphive.server.model.enums.Platform;
import io.github.raulgl422.cliphive.server.repository.SocialAccountRepository;
import io.github.raulgl422.cliphive.server.security.EncryptionService;
import io.github.raulgl422.cliphive.server.service.LogEventService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TikTok hands out a new refresh token on every refresh and the old one stops
 * working. Two threads (an upload, the stats) finding the token expiring at
 * once must refresh it only once.
 */
class SocialAccountRefreshTest {

    /** The account's row, so each thread loads its own copy as JPA would. */
    private final AtomicReference<String[]> row = new AtomicReference<>();
    private final UUID id = UUID.randomUUID();

    @Test
    void two_threads_with_an_expiring_token_refresh_it_once() throws Exception {
        row.set(new String[]{"old-access", "refresh-1", Instant.now().plusSeconds(10).toString()});

        SocialAccountRepository repo = mock(SocialAccountRepository.class);
        when(repo.findById(id)).thenAnswer(inv -> Optional.of(load()));
        when(repo.save(any())).thenAnswer(inv -> {
            SocialAccount a = inv.getArgument(0);
            row.set(new String[]{a.getAccessTokenEncrypted(), a.getRefreshTokenEncrypted(), a.getExpiresAt().toString()});
            return a;
        });
        EncryptionService encryption = mock(EncryptionService.class);
        when(encryption.encrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(encryption.decrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));

        AtomicInteger refreshes = new AtomicInteger();
        CountDownLatch bothIn = new CountDownLatch(2);
        OAuthProvider tiktok = mock(OAuthProvider.class);
        when(tiktok.platform()).thenReturn(Platform.TIKTOK);
        when(tiktok.refreshMargin()).thenReturn(Duration.ofMinutes(5));
        when(tiktok.refresh(anyString())).thenAnswer(inv -> {
            String used = inv.getArgument(0);
            if (!used.equals(row.get()[1])) throw new PublishException("refresh token already used", false);
            int n = refreshes.incrementAndGet();
            Thread.sleep(200);   // a slow network call: the other thread is waiting by now
            return new OAuthTokens("access-" + n, "refresh-" + (n + 1), Instant.now().plusSeconds(86_400), null, null);
        });

        SocialAccountService service = new SocialAccountService(repo, encryption, null,
                mock(LogEventService.class), List.of(tiktok));

        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<String>> tokens = List.of(
                    pool.submit(() -> { SocialAccount mine = load(); bothIn.countDown(); bothIn.await(); return service.accessToken(mine); }),
                    pool.submit(() -> { SocialAccount mine = load(); bothIn.countDown(); bothIn.await(); return service.accessToken(mine); }));

            assertThat(tokens.get(0).get()).isEqualTo("access-1");
            assertThat(tokens.get(1).get()).isEqualTo("access-1");
        }
        assertThat(refreshes.get()).isEqualTo(1);
        assertThat(row.get()[1]).isEqualTo("refresh-2");
    }

    private SocialAccount load() throws ReflectiveOperationException {
        String[] r = row.get();
        SocialAccount a = new SocialAccount(Platform.TIKTOK, "acct", r[0]);
        a.setRefreshTokenEncrypted(r[1]);
        a.setExpiresAt(Instant.parse(r[2]));
        Field f = SocialAccount.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(a, id);
        return a;
    }
}
