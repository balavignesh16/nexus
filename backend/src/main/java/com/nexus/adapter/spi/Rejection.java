package com.nexus.adapter.spi;

import java.util.Objects;

/**
 * A refusal: a fixed code and a classification, nothing else. It never carries payload text, an identifier or an exception message,
 * so it is safe to log, count and return.
 */
public record Rejection(RejectionCode code, Classification classification) {
    public enum Classification { PERMANENT, TRANSIENT }

    public Rejection {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(classification, "classification");
    }

    public static Rejection permanent(RejectionCode code) {
        return new Rejection(code, Classification.PERMANENT);
    }

    public static Rejection transientFailure(RejectionCode code) {
        return new Rejection(code, Classification.TRANSIENT);
    }

    @Override
    public String toString() {
        return code + "/" + classification;
    }
}
