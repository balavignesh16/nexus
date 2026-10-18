package com.nexus.adapter.core;

import com.nexus.action.outbox.BrokerCommandPublisher;
import com.nexus.adapter.mqtt.MqttPublisher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Implements the MQTT adapter's publishing port on top of the existing bounded broker publisher, unchanged: its classification table
 * (NOT_SENT only with proof that nothing reached the client, everything else UNCERTAIN) is exactly the one Phase 4 tested. The publisher
 * is injected by its bean name through the seam interface, so a decorated or substituted publisher (the crash harness, a test double) is
 * honoured.
 *
 * <p>Nothing calls this class in P5.3 except the adapter's egress, which only the {@code CommandEgressRouter} reaches, and nothing calls the
 * router yet: the outbox dispatcher stays the only caller of the bounded publisher (contract R31). The dispatcher is routed through the
 * router in P5.4, with an architecture test that keeps it the router's only caller.
 */
@Component
public class BrokerPublisherBridge implements MqttPublisher {
    private final BrokerCommandPublisher paho;
    private final BrokerConnectionState connection;

    public BrokerPublisherBridge(@Qualifier("pahoBrokerCommandPublisher") BrokerCommandPublisher paho, BrokerConnectionState connection) {
        this.paho = paho;
        this.connection = connection;
    }

    @Override
    public boolean connected() {
        return connection.connected();
    }

    @Override
    public Result publish(String topic, byte[] payload, Duration timeout) {
        BrokerCommandPublisher.Attempt attempt = paho.publish(topic, payload, timeout);
        return switch (attempt.result()) {
            case CONFIRMED -> new Result(Kind.CONFIRMED, null);
            case NOT_SENT -> new Result(Kind.NOT_SENT, attempt.code());
            case UNCERTAIN -> new Result(Kind.UNCERTAIN, attempt.code());
        };
    }
}
