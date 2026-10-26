package io.github.raulgl422.cliphive.server.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * The single superadmin account - docs/DESIGN.md section 12.1. Created on
 * first boot from {@code CLIPHIVE_ADMIN_USER}/{@code CLIPHIVE_ADMIN_PASSWORD},
 * no registration endpoint exists. {@link #totpSecretEncrypted} starts null;
 * 2FA is enabled later from the superadmin web UI, not at creation time.
 */
@Entity
@Table(name = "admin_user")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdminUser {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "totp_secret_encrypted")
    private String totpSecretEncrypted;

    @Column(name = "totp_enabled", nullable = false)
    private boolean totpEnabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public AdminUser(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.totpEnabled = false;
        this.createdAt = Instant.now();
    }

    /** Enables 2FA with the given encrypted TOTP secret. */
    public void enableTotp(String totpSecretEncrypted) {
        this.totpSecretEncrypted = totpSecretEncrypted;
        this.totpEnabled = true;
    }

    /** Replaces the password with an already-hashed one (BCrypt). */
    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void disableTotp() {
        this.totpSecretEncrypted = null;
        this.totpEnabled = false;
    }
}
