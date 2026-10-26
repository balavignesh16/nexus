package com.nexus.adapter.spi;

import java.util.Objects;

/** The only results of decoding: a typed value or a {@link Rejection}. A decoder never throws for bad input. */
public sealed interface Decoded<T> permits Decoded.Accepted, Decoded.Refused {

    record Accepted<T>(T value) implements Decoded<T> {
        public Accepted {
            Objects.requireNonNull(value, "value");
        }
    }

    record Refused<T>(Rejection rejection) implements Decoded<T> {
        public Refused {
            Objects.requireNonNull(rejection, "rejection");
        }
    }

    static <T> Decoded<T> accepted(T value) {
        return new Accepted<>(value);
    }

    static <T> Decoded<T> refused(RejectionCode code) {
        return new Refused<>(Rejection.permanent(code));
    }

    static <T> Decoded<T> refused(Rejection rejection) {
        return new Refused<>(rejection);
    }

    default boolean isAccepted() {
        return this instanceof Accepted<T>;
    }
}
