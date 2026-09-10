package com.nexus.infrastructure.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.telemetry.application.TelemetryService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Behavioral tests for TelemetryMQTTListener's message-acknowledgment
 * contract: a message is only ever acked (messageArrivedComplete) AFTER
 * telemetry has been successfully processed; any failure along the way -
 * processing exception, topic/payload device-id mismatch, bean-validation
 * failure, or a malformed payload - leaves the ack decision to whichever
 * exact code path handles that failure category (some explicitly ack-and-
 * drop; others deliberately do not ack, relying on QoS 1 broker redelivery).
 * These tests prove the CURRENT behavior of each path exactly as
 * implemented; they do not assume the design is complete or ideal.
 *
 * No real broker is used or needed: messageArrived() is a plain method on a
 * @Component, callable directly with a mocked MqttClient (to observe ack
 * calls) and a mocked TelemetryService (to control success/failure). A real
 * ObjectMapper and a real Jakarta Validator are used (both cheap and
 * deterministic) so that deserialization and bean-validation behavior is
 * genuinely exercised, not assumed.
 */
class TelemetryMQTTListenerTest {

    private static final String TELEMETRY_TOPIC = "nexus/devices/+/telemetry";

    private MqttClient mqttClient;
    private TelemetryService telemetryService;
    private TelemetryMQTTListener listener;

    @BeforeEach
    void setUp() {
        mqttClient = mock(MqttClient.class);
        telemetryService = mock(TelemetryService.class);
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        listener = new TelemetryMQTTListener(mqttClient, telemetryService, objectMapper, validator, TELEMETRY_TOPIC);
    }

    private MqttMessage messageFor(String json) {
        MqttMessage message = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
        message.setQos(1);
        message.setId(42);
        return message;
    }

    private String validPayload(UUID deviceId) {
        return String.format(
                "{\"deviceId\":\"%s\",\"timestamp\":\"%s\",\"sensorType\":\"TEMPERATURE_SENSOR\",\"value\":22.5,\"unit\":\"CELSIUS\"}",
                deviceId, Instant.parse("2026-01-01T00:00:00Z"));
    }

    // ---------- Success path: ACK only after processing ----------

    @Test
    void validMessage_matchingTopicAndPayload_processesTelemetryAndAcknowledgesAfterwards() throws Exception {
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/telemetry";
        MqttMessage message = messageFor(validPayload(deviceId));

        listener.messageArrived(topic, message);

        verify(telemetryService, times(1)).processTelemetry(any(TelemetryRequest.class));
        verify(mqttClient, times(1)).messageArrivedComplete(message.getId(), message.getQos());
    }

    @Test
    void validMessage_acknowledgementHappensStrictlyAfterProcessing_notMerelyBothEventually() throws Exception {
        // Proves ORDER, not just that both calls eventually happened.
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/telemetry";
        MqttMessage message = messageFor(validPayload(deviceId));

        listener.messageArrived(topic, message);

        InOrder inOrder = inOrder(telemetryService, mqttClient);
        inOrder.verify(telemetryService).processTelemetry(any(TelemetryRequest.class));
        inOrder.verify(mqttClient).messageArrivedComplete(anyInt(), anyInt());
    }

    @Test
    void validMessage_deserializedFieldsArePassedThroughAccurately() throws Exception {
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/telemetry";
        MqttMessage message = messageFor(validPayload(deviceId));

        listener.messageArrived(topic, message);

        ArgumentCaptor<TelemetryRequest> captor = ArgumentCaptor.forClass(TelemetryRequest.class);
        verify(telemetryService).processTelemetry(captor.capture());
        TelemetryRequest request = captor.getValue();
        assertEquals(deviceId, request.deviceId());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), request.timestamp());
        assertEquals("TEMPERATURE_SENSOR", request.sensorType());
        assertEquals(22.5, request.value());
        assertEquals("CELSIUS", request.unit());
    }

    // ---------- Failure path: processing exception => NO ack, exception propagates ----------

    @Test
    void telemetryProcessingThrows_messageIsNotAcknowledged() throws Exception {
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/telemetry";
        MqttMessage message = messageFor(validPayload(deviceId));
        when(telemetryService.processTelemetry(any())).thenThrow(new RuntimeException("simulated DB failure"));

        assertThrows(RuntimeException.class, () -> listener.messageArrived(topic, message));

        verify(mqttClient, never()).messageArrivedComplete(anyInt(), anyInt());
    }

    @Test
    void telemetryProcessingThrows_exceptionPropagatesOutOfMessageArrived_soBrokerCanRedeliver() throws Exception {
        // The listener deliberately rethrows on processing failure. Whether or
        // not Paho does anything special with that exception, the *absence* of
        // an ack (proven above) is what actually causes QoS 1 redelivery on
        // reconnect - this test just confirms the exception is not swallowed.
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/telemetry";
        MqttMessage message = messageFor(validPayload(deviceId));
        RuntimeException cause = new RuntimeException("device repository unavailable");
        when(telemetryService.processTelemetry(any())).thenThrow(cause);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> listener.messageArrived(topic, message));
        assertEquals(cause, thrown.getCause(), "original failure must be preserved as the cause");
    }

    // ---------- Topic/payload device-id mismatch: explicitly ack-and-drop ----------

    @Test
    void deviceIdMismatchBetweenTopicAndPayload_isNotProcessed() throws Exception {
        UUID topicDeviceId = UUID.randomUUID();
        UUID payloadDeviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + topicDeviceId + "/telemetry";
        MqttMessage message = messageFor(validPayload(payloadDeviceId));

        listener.messageArrived(topic, message);

        verify(telemetryService, never()).processTelemetry(any());
    }

    @Test
    void deviceIdMismatchBetweenTopicAndPayload_isAcknowledgedAnyway_toAvoidPermanentRedelivery() throws Exception {
        // Current, deliberate behavior: a message that can never match (topic
        // and payload device id will never agree on redelivery either) is
        // acked and dropped rather than retried forever.
        UUID topicDeviceId = UUID.randomUUID();
        UUID payloadDeviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + topicDeviceId + "/telemetry";
        MqttMessage message = messageFor(validPayload(payloadDeviceId));

        listener.messageArrived(topic, message);

        verify(mqttClient, times(1)).messageArrivedComplete(message.getId(), message.getQos());
    }

    @Test
    void topicWithFewerThanThreeSegments_skipsDeviceIdCheck_andProceedsToProcessing() throws Exception {
        // The mismatch check is guarded by "parts.length >= 3"; a topic that
        // doesn't have that shape bypasses the check entirely rather than
        // failing closed.
        UUID deviceId = UUID.randomUUID();
        MqttMessage message = messageFor(validPayload(deviceId));

        listener.messageArrived("short/topic", message);

        verify(telemetryService, times(1)).processTelemetry(any());
        verify(mqttClient, times(1)).messageArrivedComplete(anyInt(), anyInt());
    }

    // ---------- Bean validation failures: explicitly ack-and-drop ----------

    @Test
    void missingSensorType_failsBeanValidation_isNotProcessed() throws Exception {
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/telemetry";
        String json = String.format(
                "{\"deviceId\":\"%s\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"sensorType\":\"\",\"value\":22.5,\"unit\":\"CELSIUS\"}",
                deviceId);

        assertThrows(RuntimeException.class, () -> listener.messageArrived(topic, messageFor(json)));

        verify(telemetryService, never()).processTelemetry(any());
    }

    @Test
    void missingSensorType_failsBeanValidation_isAcknowledgedDespiteThrowing() throws Exception {
        // Current, exact (and slightly inconsistent) behavior: the code acks
        // the message as soon as the ConstraintViolationException is
        // detected, then throws anyway - so the log line at the outer catch
        // ("Did NOT ack") is misleading for this specific path, but the ack
        // itself has already genuinely happened by the time that log fires.
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/telemetry";
        String json = String.format(
                "{\"deviceId\":\"%s\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"sensorType\":\"\",\"value\":22.5,\"unit\":\"CELSIUS\"}",
                deviceId);
        MqttMessage message = messageFor(json);

        assertThrows(RuntimeException.class, () -> listener.messageArrived(topic, message));

        verify(mqttClient, times(1)).messageArrivedComplete(message.getId(), message.getQos());
    }

    @Test
    void missingValue_failsBeanValidation_isNotProcessed() throws Exception {
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/telemetry";
        String json = String.format(
                "{\"deviceId\":\"%s\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"sensorType\":\"TEMPERATURE_SENSOR\",\"unit\":\"CELSIUS\"}",
                deviceId);

        assertThrows(RuntimeException.class, () -> listener.messageArrived(topic, messageFor(json)));

        verify(telemetryService, never()).processTelemetry(any());
    }

    @Test
    void missingUnit_failsBeanValidation_isNotProcessed() throws Exception {
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/telemetry";
        String json = String.format(
                "{\"deviceId\":\"%s\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"sensorType\":\"TEMPERATURE_SENSOR\",\"value\":22.5}",
                deviceId);

        assertThrows(RuntimeException.class, () -> listener.messageArrived(topic, messageFor(json)));

        verify(telemetryService, never()).processTelemetry(any());
    }

    // ---------- Malformed / structurally incomplete payloads: confirmed gap ----------

    @Test
    void malformedJsonSyntax_failsDeserialization_isNotAcknowledged() throws Exception {
        // CONFIRMED GAP (already flagged in the original forensic audit, not a
        // new finding introduced by this test): deserialization happens
        // before either of the two explicit ack-and-drop paths (topic check,
        // bean validation), so a payload that isn't even parseable JSON never
        // reaches them. It falls straight to the outer catch, which does NOT
        // ack - meaning a genuinely malformed message (which can never become
        // parseable) would be redelivered by the broker indefinitely, absent
        // a dead-letter mechanism. This test proves that is the CURRENT,
        // real, reproducible behavior - it does not fix or mask it.
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/telemetry";
        MqttMessage message = messageFor("{not valid json at all");

        assertThrows(RuntimeException.class, () -> listener.messageArrived(topic, message));

        verify(telemetryService, never()).processTelemetry(any());
        verify(mqttClient, never()).messageArrivedComplete(anyInt(), anyInt());
    }

    @Test
    void nullDeviceIdInPayload_causesNullPointerDuringTopicComparison_isNotAcknowledged() throws Exception {
        // CONCRETE INSTANCE of the same gap above, pinpointed during this
        // investigation: a structurally valid JSON payload with "deviceId"
        // omitted deserializes successfully (deviceId = null), but then
        // request.deviceId().toString() in the topic-vs-payload check throws
        // an NPE - before bean validation (which would otherwise have caught
        // the missing @NotNull deviceId gracefully, ack-and-drop) ever runs.
        // The NPE falls into the same uncaught/no-ack/retry-forever path as
        // the malformed-JSON case above. This is the same already-disclosed
        // risk category, not a new class of defect - proven here precisely
        // rather than left as a vague possibility.
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/telemetry";
        String jsonMissingDeviceId = "{\"timestamp\":\"2026-01-01T00:00:00Z\",\"sensorType\":\"TEMPERATURE_SENSOR\",\"value\":22.5,\"unit\":\"CELSIUS\"}";
        MqttMessage message = messageFor(jsonMissingDeviceId);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> listener.messageArrived(topic, message));
        assertInstanceOf(NullPointerException.class, thrown.getCause(),
                "documents the exact mechanism: NPE from request.deviceId().toString() on a null deviceId");

        verify(telemetryService, never()).processTelemetry(any());
        verify(mqttClient, never()).messageArrivedComplete(anyInt(), anyInt());
    }

    // ---------- QoS / subscription behavior ----------

    @Test
    void init_whenAlreadyConnected_subscribesWithQos1() throws Exception {
        when(mqttClient.isConnected()).thenReturn(true);

        listener.init();

        verify(mqttClient).subscribe(TELEMETRY_TOPIC, 1);
    }

    @Test
    void init_whenNotYetConnected_doesNotSubscribeImmediately() throws Exception {
        when(mqttClient.isConnected()).thenReturn(false);

        listener.init();

        verify(mqttClient, never()).subscribe(anyString(), anyInt());
    }

    @Test
    void connectComplete_subscribesWithQos1_soReconnectsResubscribeCorrectly() throws Exception {
        listener.connectComplete(true, "tcp://localhost:1883");

        verify(mqttClient).subscribe(TELEMETRY_TOPIC, 1);
    }
}
