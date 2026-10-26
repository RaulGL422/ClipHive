package io.github.raulgl422.cliphive.server.model;

import io.github.raulgl422.cliphive.server.model.enums.Platform;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A connected TikTok/Instagram/YouTube account - docs/DESIGN.md section 11.
 * Token fields hold ciphertext: encryption/decryption with
 * {@code CLIPHIVE_ENCRYPTION_KEY} happens in the service layer, never here.
 */
@Entity
@Table(name = "social_account")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SocialAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Platform platform;

    @Column(name = "external_account_id", nullable = false)
    private String externalAccountId;

    /** Human-readable name of the account (e.g. the YouTube channel), for the superadmin only. */
    @Column(name = "display_name")
    private String displayName;

    @Column(name = "access_token_encrypted", nullable = false)
    private String accessTokenEncrypted;

    @Column(name = "refresh_token_encrypted")
    private String refreshTokenEncrypted;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "connected_at", nullable = false, updatable = false)
    private Instant connectedAt;

    public SocialAccount(Platform platform, String externalAccountId, String accessTokenEncrypted) {
        this.platform = platform;
        this.externalAccountId = externalAccountId;
        this.accessTokenEncrypted = accessTokenEncrypted;
        this.connectedAt = Instant.now();
    }
}
