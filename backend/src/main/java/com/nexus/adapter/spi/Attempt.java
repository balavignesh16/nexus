package com.nexus.adapter.spi;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The classified outcome of ONE delivery attempt, the Phase 4 shape: CONFIRMED (with an evidence kind), NOT_SENT (proof that nothing
 * reached the transport) or UNCERTAIN (anything ambiguous). The default for doubt is UNCERTAIN, never NOT_SENT and never CONFIRMED.
 */
public record Attempt(Outcome outcome, String code, EvidenceKind evidence) {
    public enum Outcome { CONFIRMED, NOT_SENT, UNCERTAIN }

    private static final Pattern CODE = Pattern.compile("[A-Z0-9_]{1,64}");

    public Attempt {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == Outcome.CONFIRMED) {
            Objects.requireNonNull(evidence, "a CONFIRMED attempt must state its evidence kind");
        } else {
            if (evidence != null) throw new IllegalArgumentException("only a CONFIRMED attempt carries an evidence kind");
            if (code == null || !CODE.matcher(code).matches()) throw new IllegalArgumentException("NOT_SENT and UNCERTAIN need a stable code of A-Z 0-9 _");
        }
    }

    public static Attempt confirmed(EvidenceKind evidence) {
        return new Attempt(Outcome.CONFIRMED, null, evidence);
    }

    public static Attempt notSent(String code) {
        return new Attempt(Outcome.NOT_SENT, code, null);
    }

    public static Attempt uncertain(String code) {
        return new Attempt(Outcome.UNCERTAIN, code, null);
    }
}
