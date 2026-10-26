package io.github.raulgl422.cliphive.server.model.enums;

import java.util.Locale;
import java.util.Optional;

/**
 * How rendered clips get their publication time (DESIGN.md §10.4), stored in
 * the {@code publish.mode} setting as {@link #value()}.
 */
public enum PublishMode {
    /** Every clip is scheduled as soon as its job is rendered (testing). */
    IMMEDIATE("immediate"),
    /** Claude plans the day's slots. */
    CLAUDE("claude");

    private final String value;

    PublishMode(String value) {
        this.value = value;
    }

    /** As stored in the setting and sent by the settings form. */
    public String value() {
        return value;
    }

    /** The mode for a stored or submitted value, ignoring case and spaces; empty if it isn't one. */
    public static Optional<PublishMode> parse(String raw) {
        if (raw == null) return Optional.empty();
        String v = raw.strip().toLowerCase(Locale.ROOT);
        for (PublishMode m : values()) {
            if (m.value.equals(v)) return Optional.of(m);
        }
        return Optional.empty();
    }
}
