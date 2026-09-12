package com.nexus.infrastructure.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.telemetry.application.TelemetryService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Behavioral tests for TelemetryMQTTListener's message-acknowledgment
 * contract: a message is only ever acked (messageArrivedComplete) AFTER
 * telemetry has been successfully processed. Three categories of failure are
 * distinguished:
 *   - PERMANENTLY INVALID input (malformed/mistyped JSON, missing required
 *     fields, topic/payload device-id mismatch): acknowledged and dropped,
 *     never retried, since redelivery cannot make it valid.
 *   - TRANSIENT processing failure (TelemetryService throws): NOT
 *     acknowledged, so QoS 1 broker redelivery can retry it later.
 *   - SUCCESS: processed, then acknowledged, in that order.
 *
 * As of fix(mqtt): prevent poison-message redelivery loops, deserialization
 * failures and missing-required-field payloads are both routed into the
 * "permanently invalid -> ack and drop" category instead of falling through
 * to the transient-failure path, which previously caused them to be
 * redelivered forever (deserialization failure) or to throw a
 * NullPointerException before validation ever ran (missing deviceId).
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
    private MqttReconnectController reconnectController;
    private MeterRegistry meterRegistry;
    private TelemetryMQTTListener listener;

    @BeforeEach
    void setUp() {
        mqttClient = mock(MqttClient.class);
        telemetryService = mock(TelemetryService.class);
        reconnectController = mock(MqttReconnectController.class);
        meterRegistry = new SimpleMeterRegistry();
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        listener = new TelemetryMQTTListener(mqttClient, telemetryService, objectMapper, validator, TELEMETRY_TOPIC, reconnectController, meterRegistry);
        listener.init();
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

    // ---------- Bean validation failures: acknowledged and dropped, cleanly ----------
    //
    // FIXED (fix(mqtt): prevent poison-message redelivery loops): these
    // paths previously acked the message and then threw a
    // ConstraintViolationException anyway, which was caught by the outer
    // catch and logged as "Did NOT ack" - misleading, since the ack had
    // already happened. Bean-validation failure is now handled inline: log,
    // acknowledge, return. No exception is thrown, and the misleading log
    // line is structurally unreachable from this path (that log statement
    // only exists in the processing try/catch now).

    @Test
    void missingSensorType_failsBeanValidation_isNotProcessed_andDoesNotThrow() throws Exception {
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/telemetry";
        String json = String.format(
                "{\"deviceId\":\"%s\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"sensorType\":\"\",\"value\":22.5,\"unit\":\"CELSIUS\"}",
                deviceId);

        assertDoesNotThrow(() -> listener.messageArrived(topic, messageFor(json)));

        verify(telemetryService, never()).processTelemetry(any());
    }

    @Test
    void missingSensorType_failsBeanValidation_isAcknowledgedExactlyOnce_cleanly() throws Exception {
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/telemetry";
        String json = String.format(
                "{\"deviceId\":\"%s\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"sensorType\":\"\",\"value\":22.5,\"unit\":\"CELSIUS\"}",
                deviceId);
        MqttMessage message = messageFor(json);

        assertDoesNotThrow(() -> listener.messageArrived(topic, message));

        verify(mqttClient, times(1)).messageArrivedComplete(message.getId(), message.getQos());
    }

    @Test
    void missingValue_failsBeanValidation_isNotProcessed_andDoesNotThrow() throws Exception {
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/telemetry";
        String json = String.format(
                "{\"deviceId\":\"%s\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"sensorType\":\"TEMPERATURE_SENSOR\",\"unit\":\"CELSIUS\"}",
                deviceId);
        MqttMessage message = messageFor(json);

        assertDoesNotThrow(() -> listener.messageArrived(topic, message));

        verify(telemetryService, never()).processTelemetry(any());
        verify(mqttClient, times(1)).messageArrivedComplete(message.getId(), message.getQos());
    }

    @Test
    void missingUnit_failsBeanValidation_isNotProcessed_andDoesNotThrow() throws Exception {
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/telemetry";
        String json = String.format(
                "{\"deviceId\":\"%s\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"sensorType\":\"TEMPERATURE_SENSOR\",\"value\":22.5}",
                deviceId);
        MqttMessage message = messageFor(json);

        assertDoesNotThrow(() -> listener.messageArrived(topic, message));

        verify(telemetryService, never()).processTelemetry(any());
        verify(mqttClient, times(1)).messageArrivedComplete(message.getId(), message.getQos());
    }

    // ---------- Malformed / structurally incomplete payloads: FIXED ----------
    //
    // FIXED (fix(mqtt): prevent poison-message redelivery loops): previously,
    // deserialization ran before either explicit ack-and-drop path, so a
    // payload that wasn't even parseable JSON fell straight to the outer
    // catch, which did NOT ack - meaning it would have been redelivered by
    // the broker indefinitely. Deserialization is now wrapped in its own
    // try/catch(IOException) that acknowledges and drops the message
    // immediately, without throwing.

    @Test
    void malformedJsonSyntax_isAcknowledgedAndDropped_doesNotThrow_isNotRetried() throws Exception {
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/telemetry";
        MqttMessage message = messageFor("{not valid json at all");

        assertDoesNotThrow(() -> listener.messageArrived(topic, message));

        verify(telemetryService, never()).processTelemetry(any());
        verify(mqttClient, times(1)).messageArrivedComplete(message.getId(), message.getQos());
    }

    @Test
    void jsonWithWrongFieldType_isAcknowledgedAndDropped_doesNotThrow() throws Exception {
        // Same category as malformed syntax: syntactically valid JSON but a
        // value of the wrong type (e.g. a boolean where a numeric "value" is
        // expected) fails during deserialization, before either ack-and-drop
        // path is reached. This is the exact real-world scenario a motion
        // sensor previously triggered against the live backend (a Python
        // bool serialized as JSON true/false into a Double field).
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/telemetry";
        String jsonWithBooleanValue = String.format(
                "{\"deviceId\":\"%s\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"sensorType\":\"MOTION_SENSOR\",\"value\":false,\"unit\":\"BOOLEAN\"}",
                deviceId);
        MqttMessage message = messageFor(jsonWithBooleanValue);

        assertDoesNotThrow(() -> listener.messageArrived(topic, message));

        verify(telemetryService, never()).processTelemetry(any());
        verify(mqttClient, times(1)).messageArrivedComplete(message.getId(), message.getQos());
    }

    @Test
    void nullDeviceIdInPayload_isRejectedByBeanValidation_noNullPointerException_isAcknowledged() throws Exception {
        // FIXED: a structurally valid JSON payload with "deviceId" omitted
        // deserializes successfully (deviceId = null). Bean validation now
        // runs BEFORE the topic-vs-payload device-id comparison, so the
        // @NotNull violation on deviceId is caught here - request.deviceId()
        // is never dereferenced against a null value, so the
        // NullPointerException that previously occurred here cannot happen.
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/telemetry";
        String jsonMissingDeviceId = "{\"timestamp\":\"2026-01-01T00:00:00Z\",\"sensorType\":\"TEMPERATURE_SENSOR\",\"value\":22.5,\"unit\":\"CELSIUS\"}";
        MqttMessage message = messageFor(jsonMissingDeviceId);

        assertDoesNotThrow(() -> listener.messageArrived(topic, message));

        verify(telemetryService, never()).processTelemetry(any());
        verify(mqttClient, times(1)).messageArrivedComplete(message.getId(), message.getQos());
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

    // ---------- Reconnect controller notification (Tier 3 Phase 2) ----------

    @Test
    void connectComplete_notifiesReconnectController() {
        listener.connectComplete(true, "tcp://localhost:1883");

        verify(reconnectController, times(1)).onConnectComplete();
    }

    @Test
    void connectionLost_notifiesReconnectControllerWithTheCause() {
        RuntimeException cause = new RuntimeException("broker unreachable");

        listener.connectionLost(cause);

        verify(reconnectController, times(1)).onConnectionLost(cause);
    }

    // ---------- Message-level observability (Tier 3 Phase 4) ----------
    //
    // "Received" means Paho invoked messageArrived() - it must NOT be
    // redefined to mean successfully deserialized, validated, or processed.
    // These tests deliberately exercise both a valid message and a malformed
    // one to prove the counter fires identically for both.

    private double gaugeValue(String name) {
        return meterRegistry.get(name).gauge().value();
    }

    private double counterValue(String name) {
        return meterRegistry.get(name).counter().count();
    }

    @Test
    void validMessage_incrementsReceivedCounter() throws Exception {
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/telemetry";

        listener.messageArrived(topic, messageFor(validPayload(deviceId)));

        assertEquals(1.0, counterValue("mqtt.messages.received"));
    }

    @Test
    void validMessage_updatesLastMessageGaugeToASmallRecentValue() throws Exception {
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/telemetry";

        listener.messageArrived(topic, messageFor(validPayload(deviceId)));

        double secondsSinceLast = gaugeValue("mqtt.messages.seconds.since.last");
        assertTrue(secondsSinceLast >= 0 && secondsSinceLast < 5,
                "expected a small non-negative value just after receipt, was " + secondsSinceLast);
    }

    @Test
    void noMessageEverReceived_gaugeReportsNegativeOneSentinel() {
        assertEquals(-1.0, gaugeValue("mqtt.messages.seconds.since.last"),
                "before any message arrives, the gauge must report the defined -1 'never received' sentinel");
    }

    @Test
    void malformedJsonPayload_stillIncrementsReceivedCounter() throws Exception {
        // The receive counter counts every Paho callback invocation,
        // regardless of downstream outcome - a malformed payload must still
        // count as "received" (it did reach messageArrived()), even though
        // it is separately ack-and-dropped without being processed.
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/telemetry";

        listener.messageArrived(topic, messageFor("{not valid json at all"));

        assertEquals(1.0, counterValue("mqtt.messages.received"));
        verify(telemetryService, never()).processTelemetry(any());
    }

    @Test
    void subscribeException_incrementsSubscribeFailureCounter() throws Exception {
        doThrow(new RuntimeException("broker refused SUBSCRIBE")).when(mqttClient).subscribe(anyString(), anyInt());

        listener.connectComplete(false, "tcp://localhost:1883");

        assertEquals(1.0, counterValue("mqtt.subscribe.failures"));
    }

    @Test
    void successfulSubscribe_doesNotIncrementSubscribeFailureCounter() {
        listener.connectComplete(false, "tcp://localhost:1883");

        assertEquals(0.0, counterValue("mqtt.subscribe.failures"));
    }

    @Test
    void receivedCounter_remainsCumulativeAcrossAReconnectCycle() throws Exception {
        UUID deviceId = UUID.randomUUID();
        String topic = "nexus/devices/" + deviceId + "/telemetry";

        listener.messageArrived(topic, messageFor(validPayload(deviceId)));
        listener.connectionLost(new RuntimeException("dropped"));
        listener.connectComplete(true, "tcp://localhost:1883");
        listener.messageArrived(topic, messageFor(validPayload(deviceId)));

        assertEquals(2.0, counterValue("mqtt.messages.received"),
                "the counter must not reset across a reconnect cycle, exactly like the existing mqtt.reconnect.* counters");
    }

    @Test
    void concurrentMessageArrivedInvocations_loseNoCounterIncrements() throws Exception {
        int threads = 20;
        int perThread = 50;
        int totalExpected = threads * perThread;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);

        List<UUID> deviceIds = IntStream.range(0, threads).mapToObj(i -> UUID.randomUUID()).toList();

        for (int t = 0; t < threads; t++) {
            UUID deviceId = deviceIds.get(t);
            String topic = "nexus/devices/" + deviceId + "/telemetry";
            String payload = validPayload(deviceId);
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    for (int i = 0; i < perThread; i++) {
                        listener.messageArrived(topic, messageFor(payload));
                    }
                } catch (InterruptedException ignored) {
                }
            });
        }

        ready.await();
        go.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals((double) totalExpected, counterValue("mqtt.messages.received"),
                "concurrent invocations must not lose any counter increments");
    }
}
