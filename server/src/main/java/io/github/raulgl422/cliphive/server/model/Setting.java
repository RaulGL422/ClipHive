package io.github.raulgl422.cliphive.server.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One hot-editable superadmin setting - docs/DESIGN.md section 12.2
 * (default language/fps/review mode, publishing mode, clip duration limits,
 * low-queue alert threshold, ...). Natural key, no surrogate id: the key
 * IS the identity. Untyped value - each reader parses what it expects.
 */
@Entity
@Table(name = "setting")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Setting {

    @Id
    private String key;

    @Column(nullable = false)
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Setting(String key, String value) {
        this.key = key;
        this.value = value;
        this.updatedAt = Instant.now();
    }

    public void setValue(String value) {
        this.value = value;
        this.updatedAt = Instant.now();
    }
}
