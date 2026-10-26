package com.nexus.adapter.spi;

import java.util.Objects;

/**
 * What a core intake port reports for a message it was given. {@link Accepted} means the core processed it (including an identical
 * duplicate). {@link Refused} is a deterministic, permanent refusal with a fixed code (for example TRANSPORT_MISMATCH). Infrastructure
 * failures (database, connection, lock, timeout) are NOT results: they propagate as exceptions so the adapter does not acknowledge the
 * message and the transport can redeliver it, exactly as the MQTT listener behaves today.
 */
public sealed interface IntakeResult permits IntakeResult.Accepted, IntakeResult.Refused {

    record Accepted() implements IntakeResult {}

    record Refused(Rejection rejection) implements IntakeResult {
        public Refused {
            Objects.requireNonNull(rejection, "rejection");
        }
    }

    static IntakeResult accepted() {
        return new Accepted();
    }

    static IntakeResult refused(RejectionCode code) {
        return new Refused(Rejection.permanent(code));
    }

    default boolean isAccepted() {
        return this instanceof Accepted;
    }
}
