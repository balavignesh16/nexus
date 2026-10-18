package com.nexus.adapter.mqtt;

import java.time.Duration;
import java.util.Objects;

/**
 * What the MQTT adapter needs from the broker client to put one command on the wire: a bounded, classified publish. It is a port of the
 * transport package; {@code adapter.core} implements it on top of the existing bounded Paho publisher, so the classification table
 * (NOT_SENT only with proof that nothing reached the client, everything else UNCERTAIN) is the one Phase 4 already tested.
 */
public interface MqttPublisher {
    enum Kind { CONFIRMED, NOT_SENT, UNCERTAIN }

    record Result(Kind kind, String code) {
        public Result {
            Objects.requireNonNull(kind, "kind");
        }
    }

    /** Publishes {@code payload} verbatim, QoS 1, not retained, and waits at most {@code timeout}. Never retries. */
    Result publish(String topic, byte[] payload, Duration timeout);

    /** Whether the broker connection is up right now (diagnostics only; it never gates a publish). */
    default boolean connected() {
        return false;
    }
}
