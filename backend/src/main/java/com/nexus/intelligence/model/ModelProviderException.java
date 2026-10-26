package com.nexus.intelligence.model;

/** A provider failure. The message is a fixed code, never provider text, a URL or a key. {@code transientFailure} allows a bounded retry. */
public class ModelProviderException extends Exception {
    private final boolean transientFailure;

    public ModelProviderException(String code, boolean transientFailure) {
        super(code, null, false, false);
        this.transientFailure = transientFailure;
    }

    public boolean transientFailure() {
        return transientFailure;
    }
}
