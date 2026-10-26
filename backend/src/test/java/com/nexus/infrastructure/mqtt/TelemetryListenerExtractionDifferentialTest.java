package com.nexus.infrastructure.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.adapter.core.AdapterMetrics;
import com.nexus.adapter.core.TransportAssignments;
import com.nexus.adapter.core.TransportGuard;
import com.nexus.adapter.mqtt.MqttJsonAdapter;
import com.nexus.adapter.mqtt.MqttPublisher;
import com.nexus.adapter.reference.ReferenceTelemetryMQTTListener;
import com.nexus.adapter.spi.TransportKind;
import com.nexus.device.command.DeviceCommandAckMessageHandler;
import com.nexus.device.connectivity.DeviceStatusMessageHandler;
import com.nexus.device.onboarding.OnboardingTelemetryRejectedException;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.telemetry.application.InvalidTelemetryException;
import com.nexus.telemetry.application.TelemetryMessageConflictException;
import com.nexus.telemetry.application.TelemetryService;
import com.nexus.telemetry.application.TelemetrySource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ledger P5-023, P5-039, P5-040, P5-042 and P5-048: the extracted listener (MQTT adapter decoder + late-injected transport guard) and a
 * reference copy of the pre-extraction listener (git blob d7ea813 at e29e215) receive the same messages and must make the same calls to the
 * unchanged core, the same acknowledge-or-drop decisions, count the same permanent-rejection reasons, and throw the same exceptions. The
 * corpus is the real-payload parity corpus plus seeded mutations. The ONE intentional difference is the JSON text null.
 */
class TelemetryListenerExtractionDifferentialTest {
    private static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json().build();
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final String COUNTER = "nexus.telemetry.permanent.rejections";

    /** What a listener did with one message. */
    record Outcome(List<String> serviceCalls, int acknowledgements, Map<String, Double> rejections, String thrown, List<String> handlerCalls) {}

    interface Subject {
        void arrive(String topic, MqttMessage message);
    }

    private static MqttMessage message(byte[] payload) {
        MqttMessage message = new MqttMessage(payload);
        message.setQos(1);
        message.setId(7);
        return message;
    }

    /** Runs one message through the reference (old=true) or the extracted listener. */
    private static Outcome run(boolean old, String topic, byte[] payload, RuntimeException serviceFailure, TransportGuard guard) throws Exception {
        MqttClient client = mock(MqttClient.class);
        TelemetryService service = mock(TelemetryService.class);
        DeviceStatusMessageHandler status = mock(DeviceStatusMessageHandler.class);
        DeviceCommandAckMessageHandler ack = mock(DeviceCommandAckMessageHandler.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        if (serviceFailure != null) Mockito.doThrow(serviceFailure).when(service).processTelemetry(any(), any());
        Subject subject;
        if (old) {
            var listener = new ReferenceTelemetryMQTTListener(client, service, MAPPER, VALIDATOR, "nexus/devices/+/telemetry", mock(MqttReconnectController.class),
                    meters, "nexus/devices/+/status", status, "nexus/devices/+/commands/ack", ack);
            listener.init();
            subject = listener::messageArrived;
        } else {
            var listener = new TelemetryMQTTListener(client, service, MAPPER, VALIDATOR, "nexus/devices/+/telemetry", mock(MqttReconnectController.class),
                    meters, "nexus/devices/+/status", status, "nexus/devices/+/commands/ack", ack);
            if (guard != null) listener.setTransportGuard(guard);
            listener.init();
            subject = listener::messageArrived;
        }
        String thrown = null;
        try {
            subject.arrive(topic, message(payload));
        } catch (RuntimeException e) {
            thrown = e.getClass().getSimpleName();
        }
        List<String> calls = new ArrayList<>();
        for (var invocation : Mockito.mockingDetails(service).getInvocations()) {
            TelemetryRequest request = invocation.getArgument(0);
            calls.add(invocation.getArgument(1) + ":" + request);
        }
        int acknowledgements = (int) Mockito.mockingDetails(client).getInvocations().stream().filter(i -> i.getMethod().getName().equals("messageArrivedComplete")).count();
        Map<String, Double> rejections = new TreeMap<>();
        for (Counter counter : meters.find(COUNTER).counters()) rejections.put(counter.getId().getTag("reason"), counter.count());
        List<String> handlerCalls = new ArrayList<>();
        Mockito.mockingDetails(status).getInvocations().forEach(i -> handlerCalls.add("status:" + i.getArgument(0) + ":" + new String((byte[]) i.getArgument(1), StandardCharsets.UTF_8)));
        Mockito.mockingDetails(ack).getInvocations().forEach(i -> handlerCalls.add("ack:" + i.getArgument(0) + ":" + new String((byte[]) i.getArgument(1), StandardCharsets.UTF_8)));
        return new Outcome(calls, acknowledgements, rejections, thrown, handlerCalls);
    }

    private static Path corpusPath() {
        Path p = Path.of("../contracts/parity/corpus.json");
        return Files.exists(p) ? p : Path.of("contracts/parity/corpus.json");
    }

    private static List<byte[]> telemetryPayloads(List<String> topics) throws Exception {
        List<byte[]> payloads = new ArrayList<>();
        JsonNode corpus = MAPPER.readTree(Files.readAllBytes(corpusPath()));
        for (JsonNode item : corpus.get("items")) {
            if (!item.get("kind").asText().equals("telemetry")) continue;
            payloads.add(item.has("payloadBase64") ? Base64.getDecoder().decode(item.get("payloadBase64").asText()) : item.get("payloadText").asText().getBytes(StandardCharsets.UTF_8));
            topics.add("nexus/devices/" + item.get("topicDeviceId").asText() + "/telemetry");
        }
        return payloads;
    }

    @Test
    void p5_039_p5_023_everyRealAndMutatedTelemetryMessageIsHandledIdenticallyExceptTheDocumentedNullCase() throws Exception {
        List<String> topics = new ArrayList<>();
        List<byte[]> payloads = telemetryPayloads(topics);
        String device = "3f2c8a54-9d1e-4b7a-8c61-0e5d2a7f9b13";
        String valid = "{\"deviceId\":\"" + device + "\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"sensorType\":\"TEMPERATURE_SENSOR\",\"value\":24.5,\"unit\":\"CELSIUS\",\"messageId\":\"m-1\"}";
        Random random = new Random(20261018L);
        for (int i = 0; i < 400; i++) {
            byte[] bytes = valid.getBytes(StandardCharsets.UTF_8);
            int edits = 1 + random.nextInt(3);
            for (int e = 0; e < edits && bytes.length > 0; e++) {
                int at = random.nextInt(bytes.length);
                switch (random.nextInt(4)) {
                    case 0 -> bytes[at] = (byte) random.nextInt(256);
                    case 1 -> bytes = java.util.Arrays.copyOf(bytes, at);
                    case 2 -> { byte[] longer = new byte[bytes.length + 1]; System.arraycopy(bytes, 0, longer, 0, at); longer[at] = (byte) "{}[]\",:0123456789ntf".charAt(random.nextInt(20)); System.arraycopy(bytes, at, longer, at + 1, bytes.length - at); bytes = longer; }
                    default -> bytes[at] = (byte) ("\"{}:,".charAt(random.nextInt(5)));
                }
            }
            payloads.add(bytes);
            topics.add("nexus/devices/" + device + "/telemetry");
        }
        assertTrue(payloads.size() > 450, "corpus size " + payloads.size());

        List<String> differences = new ArrayList<>();
        for (int i = 0; i < payloads.size(); i++) {
            Outcome before = run(true, topics.get(i), payloads.get(i), null, null);
            Outcome after = run(false, topics.get(i), payloads.get(i), null, null);
            if (before.equals(after)) continue;
            boolean nullDeviation = new String(payloads.get(i), StandardCharsets.UTF_8).trim().equals("null")
                    && "IllegalArgumentException".equals(before.thrown()) && after.thrown() == null && after.acknowledgements() == 1
                    && after.rejections().equals(Map.of("MALFORMED_JSON", 1.0)) && after.serviceCalls().isEmpty();
            if (!nullDeviation) differences.add(i + " " + new String(payloads.get(i), StandardCharsets.UTF_8) + "\n  before=" + before + "\n  after =" + after);
        }
        assertEquals(List.of(), differences, "the extraction changed behaviour for these messages");
    }

    @Test
    void theOnlyDeviationIsTheJsonNullPoisonMessageWhichIsNowAcknowledgedAndDropped() throws Exception {
        String topic = "nexus/devices/3f2c8a54-9d1e-4b7a-8c61-0e5d2a7f9b13/telemetry";
        Outcome before = run(true, topic, "null".getBytes(StandardCharsets.UTF_8), null, null);
        Outcome after = run(false, topic, "null".getBytes(StandardCharsets.UTF_8), null, null);
        assertEquals("IllegalArgumentException", before.thrown(), "the old listener threw out of the MQTT callback, so the broker redelivered it forever");
        assertEquals(null, after.thrown());
        assertEquals(1, after.acknowledgements());
        assertEquals(Map.of("MALFORMED_JSON", 1.0), after.rejections());
    }

    @Test
    void p5_040_poisonClassificationAndTransientFailuresAreIdenticalToBeforeTheExtraction() throws Exception {
        String device = "3f2c8a54-9d1e-4b7a-8c61-0e5d2a7f9b13";
        byte[] valid = ("{\"deviceId\":\"" + device + "\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"sensorType\":\"TEMPERATURE_SENSOR\",\"value\":24.5,\"unit\":\"CELSIUS\"}").getBytes(StandardCharsets.UTF_8);
        String topic = "nexus/devices/" + device + "/telemetry";
        UUID id = UUID.fromString(device);
        List<RuntimeException> failures = List.of(new OnboardingTelemetryRejectedException("no"), new InvalidTelemetryException("bad", "REASON"),
                new TelemetryMessageConflictException(id, "m-1"), new CannotAcquireLockException("lock"), new IllegalStateException("boom"),
                new org.springframework.dao.DataAccessResourceFailureException("db down"));
        for (RuntimeException failure : failures) {
            Outcome before = run(true, topic, valid, failure, null);
            Outcome after = run(false, topic, valid, failure, null);
            assertEquals(before, after, failure.getClass().getSimpleName());
        }
        Outcome permanent = run(false, topic, valid, new TelemetryMessageConflictException(id, "m-1"), null);
        assertEquals(1, permanent.acknowledgements(), "a conflicting duplicate is acknowledged and dropped");
        Outcome infrastructure = run(false, topic, valid, new CannotAcquireLockException("lock"), null);
        assertEquals(0, infrastructure.acknowledgements(), "an infrastructure failure is not acknowledged, so the broker redelivers");
        assertEquals("RuntimeException", infrastructure.thrown());
    }

    @Test
    void p5_042_statusAndAcknowledgementTopicsReachTheSameHandlersWithTheSameBytes() throws Exception {
        String device = "3f2c8a54-9d1e-4b7a-8c61-0e5d2a7f9b13";
        Map<String, byte[]> messages = new TreeMap<>();
        messages.put("nexus/devices/" + device + "/status", "online".getBytes(StandardCharsets.UTF_8));
        messages.put("nexus/devices/" + device + "/status", " offline\n".getBytes(StandardCharsets.UTF_8));
        messages.put("nexus/devices/" + device + "/commands/ack", ("{\"commandId\":\"" + UUID.randomUUID() + "\",\"status\":\"ACCEPTED\",\"timestamp\":1767225600}").getBytes(StandardCharsets.UTF_8));
        messages.put("nexus/devices/not-a-uuid/status", "online".getBytes(StandardCharsets.UTF_8));
        messages.put("nexus/devices/" + device + "/commands/ack/extra", "{}".getBytes(StandardCharsets.UTF_8));
        for (var entry : messages.entrySet()) {
            assertEquals(run(true, entry.getKey(), entry.getValue(), null, null), run(false, entry.getKey(), entry.getValue(), null, null), entry.getKey());
        }
        Outcome status = run(false, "nexus/devices/" + device + "/status", "online".getBytes(StandardCharsets.UTF_8), null, null);
        assertEquals(List.of("status:nexus/devices/" + device + "/status:online"), status.handlerCalls());
        assertEquals(1, status.acknowledgements());
        assertTrue(status.serviceCalls().isEmpty(), "a status message never reaches the telemetry path");
    }

    @Test
    void theTransportGuardDropsAMessageForADeviceAssignedToAnotherTransportAndLeavesOthersAlone() throws Exception {
        String device = "3f2c8a54-9d1e-4b7a-8c61-0e5d2a7f9b13";
        UUID id = UUID.fromString(device);
        byte[] valid = ("{\"deviceId\":\"" + device + "\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"sensorType\":\"TEMPERATURE_SENSOR\",\"value\":24.5,\"unit\":\"CELSIUS\"}").getBytes(StandardCharsets.UTF_8);
        TransportAssignments assignments = mock(TransportAssignments.class);
        TransportGuard guard = new TransportGuard(assignments);

        when(assignments.assignedTo(id)).thenReturn(Optional.of(TransportKind.HTTP_WEBHOOK));
        Outcome refused = run(false, "nexus/devices/" + device + "/telemetry", valid, null, guard);
        assertTrue(refused.serviceCalls().isEmpty(), "nothing reached the core");
        assertEquals(1, refused.acknowledgements(), "acknowledged and dropped: redelivery cannot make it valid");
        assertEquals(Map.of("TRANSPORT_MISMATCH", 1.0), refused.rejections());
        Outcome statusRefused = run(false, "nexus/devices/" + device + "/status", "online".getBytes(StandardCharsets.UTF_8), null, guard);
        assertTrue(statusRefused.handlerCalls().isEmpty());
        assertEquals(1, statusRefused.acknowledgements());
        Outcome ackRefused = run(false, "nexus/devices/" + device + "/commands/ack", "{}".getBytes(StandardCharsets.UTF_8), null, guard);
        assertTrue(ackRefused.handlerCalls().isEmpty());

        when(assignments.assignedTo(id)).thenReturn(Optional.of(TransportKind.MQTT_JSON));
        Outcome accepted = run(false, "nexus/devices/" + device + "/telemetry", valid, null, guard);
        assertEquals(run(true, "nexus/devices/" + device + "/telemetry", valid, null, null), accepted, "an MQTT device is served exactly as before");

        when(assignments.assignedTo(id)).thenReturn(Optional.empty());
        assertEquals(1, run(false, "nexus/devices/" + device + "/telemetry", valid, null, guard).serviceCalls().size(), "an unknown device is left to the core");

        when(assignments.assignedTo(id)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        Outcome infrastructure = run(false, "nexus/devices/" + device + "/telemetry", valid, null, guard);
        assertEquals(0, infrastructure.acknowledgements(), "a failing assignment lookup is infrastructure: not acknowledged, redelivered");
        assertEquals("RuntimeException", infrastructure.thrown());
    }

    @Test
    void p5_048_theTelemetryMetersKeepTheirNamesAndReasonsAndTheAdapterMetersUseFixedTags() throws Exception {
        String topic = "nexus/devices/3f2c8a54-9d1e-4b7a-8c61-0e5d2a7f9b13/telemetry";
        Map<String, byte[]> poison = new TreeMap<>();
        poison.put("MALFORMED_JSON", "{".getBytes(StandardCharsets.UTF_8));
        poison.put("INVALID_FIELDS", "{\"timestamp\":\"2026-01-01T00:00:00Z\",\"value\":1}".getBytes(StandardCharsets.UTF_8));
        poison.put("INVALID_MESSAGE_ID", ("{\"deviceId\":\"3f2c8a54-9d1e-4b7a-8c61-0e5d2a7f9b13\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"value\":1,\"sensorType\":\"S\",\"unit\":\"U\",\"messageId\":\"a b\"}").getBytes(StandardCharsets.UTF_8));
        poison.put("INVALID_LEGACY_PAYLOAD", ("{\"deviceId\":\"3f2c8a54-9d1e-4b7a-8c61-0e5d2a7f9b13\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"value\":\"x\"}").getBytes(StandardCharsets.UTF_8));
        poison.put("TOPIC_DEVICE_MISMATCH", ("{\"deviceId\":\"9b1d3e57-2c44-4a0f-a6d8-71e5c0b3f492\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"value\":1,\"sensorType\":\"S\",\"unit\":\"U\"}").getBytes(StandardCharsets.UTF_8));
        for (var entry : poison.entrySet()) {
            Outcome old = run(true, topic, entry.getValue(), null, null);
            Outcome now = run(false, topic, entry.getValue(), null, null);
            assertEquals(Map.of(entry.getKey(), 1.0), old.rejections(), entry.getKey());
            assertEquals(old.rejections(), now.rejections(), "the Phase 4 meter name and reason value are unchanged: " + entry.getKey());
        }
        // adapter meters, when the late-injected collaborators exist, are additive and use only the fixed tag keys
        SimpleMeterRegistry adapterMeters = new SimpleMeterRegistry();
        var listener = new TelemetryMQTTListener(mock(MqttClient.class), mock(TelemetryService.class), MAPPER, VALIDATOR, "nexus/devices/+/telemetry",
                mock(MqttReconnectController.class), new SimpleMeterRegistry(), "nexus/devices/+/status", mock(DeviceStatusMessageHandler.class),
                "nexus/devices/+/commands/ack", mock(DeviceCommandAckMessageHandler.class));
        listener.setAdapterMetrics(new AdapterMetrics(adapterMeters));
        listener.init();
        listener.messageArrived(topic, message("{".getBytes(StandardCharsets.UTF_8)));
        assertEquals(1.0, adapterMeters.get("nexus.adapter.rejected").tag("adapter", MqttJsonAdapter.ID).tag("reason", "MALFORMED_JSON").counter().count());
        MqttPublisher unused = null;
        assertTrue(unused == null);
    }
}
