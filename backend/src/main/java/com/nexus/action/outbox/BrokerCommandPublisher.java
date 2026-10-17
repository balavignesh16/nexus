package com.nexus.action.outbox;

import java.time.Duration;

/**
 * The ONLY way the outbox dispatcher puts command bytes on the broker. It exists so the publish is bounded by an
 * explicit attempt timeout (the shared blocking client call may wait about 15 s, which is the whole delivery window)
 * and so the outcome is classified for the dispatcher (contract R10):
 * <ul>
 * <li>{@link Result#CONFIRMED}: the client completed the QoS 1 publish.</li>
 * <li>{@link Result#NOT_SENT}: the client refused before handing any bytes to the broker (not connected, in-flight
 *     limit, persistence failure). Definitely not published.</li>
 * <li>{@link Result#UNCERTAIN}: the bytes may have reached the broker (timeout, connection lost after the hand-off).
 *     Never a plain failure, never a success.</li>
 * </ul>
 * Implementations never retry, never alter the bytes and never throw for a classified outcome.
 */
public interface BrokerCommandPublisher {

    enum Result { CONFIRMED, NOT_SENT, UNCERTAIN }

    /** @param code stable machine code (for example CLIENT_NOT_CONNECTED, PUBLISH_TIMEOUT); never free text */
    record Attempt(Result result, String code) {
        public static Attempt confirmed() { return new Attempt(Result.CONFIRMED, null); }
        public static Attempt notSent(String code) { return new Attempt(Result.NOT_SENT, code); }
        public static Attempt uncertain(String code) { return new Attempt(Result.UNCERTAIN, code); }
    }

    /** Publishes {@code payload} verbatim, QoS 1, not retained, and waits at most {@code timeout} for completion. */
    Attempt publish(String topic, byte[] payload, Duration timeout);
}
