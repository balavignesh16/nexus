package com.nexus.adapter.spi;

import java.util.Objects;

/**
 * Whether a command may be ACCEPTED for a device right now. {@code message} is the operator-facing refusal text (it is the existing
 * Phase 3/4 wording for the MQTT rule, so current behaviour and tests do not change); {@code code} is a fixed machine code.
 */
public record Eligibility(boolean eligible, String code, String message) {
    private static final Eligibility YES = new Eligibility(true, null, null);

    public Eligibility {
        if (!eligible) {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }

    public static Eligibility yes() {
        return YES;
    }

    public static Eligibility no(String code, String message) {
        return new Eligibility(false, code, message);
    }
}
