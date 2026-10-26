package com.nexus.intelligence.model;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One piece of UNTRUSTED data handed to a model: device telemetry, a device or rule name, a log line, a user's request. It is always delimited as data and never
 * becomes an instruction. {@code deviceEvidence} marks data that came from devices or their history: it is sent only with the separate explicit opt-in.
 */
public record DataBlock(String label, String text, boolean deviceEvidence) {
    private static final Pattern LABEL = Pattern.compile("[a-z][a-z_]{0,31}");

    public DataBlock {
        Objects.requireNonNull(text, "text");
        if (label == null || !LABEL.matcher(label).matches()) throw new IllegalArgumentException("a data label is 1 to 32 lower-case letters and underscores");
    }

    public static DataBlock evidence(String label, String text) {
        return new DataBlock(label, text, true);
    }

    public static DataBlock userText(String label, String text) {
        return new DataBlock(label, text, false);
    }
}
