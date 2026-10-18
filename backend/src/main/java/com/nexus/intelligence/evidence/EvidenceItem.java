package com.nexus.intelligence.evidence;

import java.util.Objects;

/**
 * One resolvable persisted fact. {@code ref} is a stable string such as {@code command:<id>/ack/status}; {@code value} is a plain value (text, number,
 * boolean or an ISO instant as text) and is null unless the presence is PRESENT.
 */
public record EvidenceItem(String ref, String label, Object value, Presence presence) {
    public EvidenceItem {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(presence, "presence");
        if (presence != Presence.PRESENT && value != null) throw new IllegalArgumentException("only a PRESENT item carries a value: " + ref);
        if (presence == Presence.PRESENT && value == null) throw new IllegalArgumentException("a PRESENT item needs a value: " + ref);
    }

    public static EvidenceItem present(String ref, String label, Object value) {
        return new EvidenceItem(ref, label, value, Presence.PRESENT);
    }

    public static EvidenceItem absent(String ref, String label) {
        return new EvidenceItem(ref, label, null, Presence.ABSENT);
    }

    public static EvidenceItem notRecorded(String ref, String label) {
        return new EvidenceItem(ref, label, null, Presence.NOT_RECORDED);
    }

    /** PRESENT when the value is non-null, otherwise ABSENT (the column or field is recorded as empty). */
    public static EvidenceItem presentOrAbsent(String ref, String label, Object value) {
        return value == null ? absent(ref, label) : present(ref, label, value);
    }
}
