package com.nexus.infrastructure.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexus.device.command.DeviceCommandAckMessageHandler;
import com.nexus.device.connectivity.DeviceStatusMessageHandler;
import com.nexus.telemetry.application.InvalidTelemetryException;
import com.nexus.telemetry.application.TelemetryService;
import com.nexus.telemetry.application.TelemetrySource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ingestion lag (contract R18, ledger P4-092): telemetry is processed inside the single MQTT callback with no queue of its own, so the time
 * spent in the service is what makes the broker's delivery queue grow. The listener records it as a timer; recording changes no behaviour.
 */
class TelemetryListenerProcessingTimerTest {
    private MqttClient mqttClient;
    private TelemetryService service;
    private SimpleMeterRegistry meters;
    private TelemetryMQTTListener listener;
    private UUID device;
    private String topic;

    @BeforeEach
    void setUp() {
        mqttClient = mock(MqttClient.class);
        service = mock(TelemetryService.class);
        meters = new SimpleMeterRegistry();
        listener = new TelemetryMQTTListener(mqttClient, service, new ObjectMapper().registerModule(new JavaTimeModule()),
                Validation.buildDefaultValidatorFactory().getValidator(), "nexus/devices/+/telemetry", mock(MqttReconnectController.class), meters,
                "nexus/devices/+/status", mock(DeviceStatusMessageHandler.class), "nexus/devices/+/commands/ack", mock(DeviceCommandAckMessageHandler.class));
        listener.init();
        device = UUID.randomUUID();
        topic = "nexus/devices/" + device + "/telemetry";
    }

    private MqttMessage valid() {
        MqttMessage m = new MqttMessage(("{\"deviceId\":\"" + device + "\",\"timestamp\":\"" + Instant.parse("2026-10-17T12:00:00Z")
                + "\",\"capabilityKey\":\"motion\",\"value\":true}").getBytes(StandardCharsets.UTF_8));
        m.setQos(1);
        m.setId(9);
        return m;
    }

    private long recorded() {
        var timer = meters.find("nexus.telemetry.processing").tag("source", "MQTT").timer();
        return timer == null ? 0 : timer.count();
    }

    @Test
    void everyProcessingAttemptIsTimedWhetherItSucceedsOrFails() throws Exception {
        listener.messageArrived(topic, valid());
        assertEquals(1, recorded());
        when(service.processTelemetry(any(), eq(TelemetrySource.MQTT))).thenThrow(new InvalidTelemetryException("X", "bad"));
        listener.messageArrived(topic, valid());
        assertEquals(2, recorded(), "a permanent rejection spent time in the callback too");
        when(service.processTelemetry(any(), eq(TelemetrySource.MQTT))).thenThrow(new IllegalStateException("transient"));
        assertThrows(RuntimeException.class, () -> listener.messageArrived(topic, valid()));
        assertEquals(3, recorded(), "and so did a failure that leaves the message for redelivery; the failure itself is unchanged");
        assertTrue(meters.get("nexus.telemetry.processing").timer().totalTime(TimeUnit.NANOSECONDS) > 0);
    }

    @Test
    void aMessageThatNeverReachesTheServiceIsNotTimedAndTheTagsAreFixed() throws Exception {
        MqttMessage broken = new MqttMessage("{not json".getBytes(StandardCharsets.UTF_8));
        broken.setQos(1);
        broken.setId(9);
        listener.messageArrived(topic, broken);
        assertEquals(0, recorded());
        meters.getMeters().stream().filter(m -> m.getId().getName().equals("nexus.telemetry.processing")).forEach(m ->
                assertEquals(java.util.List.of("source"), m.getId().getTags().stream().map(t -> t.getKey()).toList(), "no identifier is ever a tag"));
    }
}
