package com.nexus.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.adapter.core.AdapterRegistry;
import com.nexus.adapter.mqtt.MqttJsonAdapter;
import com.nexus.adapter.mqtt.MqttJsonDecoder;
import com.nexus.adapter.mqtt.MqttPublisher;
import com.nexus.adapter.mqtt.MqttTopics;
import com.nexus.adapter.spi.AckPayload;
import com.nexus.adapter.spi.AdapterCapability;
import com.nexus.adapter.spi.Attempt;
import com.nexus.adapter.spi.CommandEnvelope;
import com.nexus.adapter.spi.ConnectivityObservation;
import com.nexus.adapter.spi.Decoded;
import com.nexus.adapter.spi.EvidenceKind;
import com.nexus.adapter.spi.RawMessage;
import com.nexus.adapter.spi.Rejection;
import com.nexus.adapter.spi.RejectionCode;
import com.nexus.adapter.spi.TelemetryPayload;
import com.nexus.adapter.spi.TransportKind;
import com.nexus.device.command.DeviceCommandAckMessageHandler;
import com.nexus.device.connectivity.DeviceConnectivityRegistry;
import com.nexus.device.connectivity.DeviceConnectivityStatus;
import com.nexus.device.connectivity.DeviceStatusMessageHandler;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.realtime.RealtimePublisher;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/** The MQTT adapter's decoders (never throw: P5-033; agree with the handlers they were extracted from), egress (verbatim bytes, P5-044) and descriptor. */
class MqttJsonAdapterTest {
    private static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json().build();
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final String DEVICE = "3f2c8a54-9d1e-4b7a-8c61-0e5d2a7f9b13";

    private static MqttJsonAdapter adapter(MqttPublisher publisher) {
        return new MqttJsonAdapter(MAPPER, VALIDATOR, new MqttPublisher() {
            @Override public Result publish(String topic, byte[] payload, Duration timeout) { return publisher.publish(topic, payload, timeout); }
            @Override public boolean connected() { return true; }
        });
    }

    // ------------------------------------------------------------------ P5-033: decoders never throw

    @Test
    void p5_033_noDecoderThrowsForAnyInputAndEveryRefusalIsAFixedPermanentCode() {
        MqttJsonAdapter adapter = adapter(mock(MqttPublisher.class));
        MqttJsonDecoder decoder = adapter.decoder();
        Random random = new Random(33L);
        List<String> topics = new ArrayList<>(List.of("nexus/devices/" + DEVICE + "/telemetry", "nexus/devices/" + DEVICE + "/status", "nexus/devices/" + DEVICE + "/commands/ack",
                "", "/", "a", "nexus/devices/not-a-uuid/status", "nexus/devices/" + DEVICE + "/commands/ack/x", "nexus//telemetry"));
        List<byte[]> payloads = new ArrayList<>();
        payloads.add(new byte[0]);
        for (String text : List.of("null", "[]", "{}", "123", "\"x\"", "true", "{", "}", "{\"a\":", "online", "offline", " online\n", "\u0000", "{\"commandId\":null}",
                "{\"deviceId\":\"" + DEVICE + "\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"value\":1,\"sensorType\":\"S\",\"unit\":\"U\"}",
                "{\"commandId\":\"" + UUID.randomUUID() + "\",\"status\":\"ACCEPTED\",\"timestamp\":99999999999999999999}",
                "{\"commandId\":\"" + UUID.randomUUID() + "\",\"status\":\"ACCEPTED\",\"timestamp\":9223372036854775807}",
                "{\"commandId\":\"" + UUID.randomUUID() + "\",\"status\":\"ACCEPTED\",\"timestamp\":-9223372036854775808}")) {
            payloads.add(text.getBytes(StandardCharsets.UTF_8));
        }
        for (int i = 0; i < 3000; i++) {
            byte[] bytes = new byte[random.nextInt(120)];
            random.nextBytes(bytes);
            payloads.add(bytes);
        }
        byte[] base = payloads.get(14).clone();
        for (int i = 0; i < 2000; i++) {
            byte[] mutated = base.clone();
            for (int e = 0; e < 1 + random.nextInt(4); e++) mutated[random.nextInt(mutated.length)] = (byte) random.nextInt(256);
            payloads.add(random.nextBoolean() ? mutated : java.util.Arrays.copyOf(mutated, random.nextInt(mutated.length)));
        }
        int refused = 0;
        for (byte[] payload : payloads) {
            for (String topic : topics) {
                RawMessage message = new RawMessage(payload, topic);
                for (Decoded<?> result : new Decoded<?>[]{decoder.decodeTelemetry(message), decoder.decodeAcknowledgement(message), decoder.decodeConnectivity(message),
                        adapter.decodeTelemetry(message), adapter.decodeAcknowledgement(message), adapter.decodeConnectivity(message)}) {
                    assertNotNull(result);
                    if (result instanceof Decoded.Refused<?> r) {
                        refused++;
                        assertEquals(Rejection.Classification.PERMANENT, r.rejection().classification());
                        assertNotNull(RejectionCode.valueOf(r.rejection().code().name()));
                    }
                }
            }
        }
        assertTrue(refused > 100_000, "the fuzz really exercised the refusal paths: " + refused);
        assertNotNull(decoder.decodeTelemetry(new RawMessage(new byte[0], null)), "a missing topic is handled too");
        assertNotNull(decoder.decodeAcknowledgement(new RawMessage(new byte[0], null)));
        assertNotNull(decoder.decodeConnectivity(new RawMessage(new byte[0], null)));
    }

    // ------------------------------------------------------------------ the decoders agree with the handlers they were extracted from

    private static Path corpusPath() {
        Path p = Path.of("../contracts/parity/corpus.json");
        return Files.exists(p) ? p : Path.of("contracts/parity/corpus.json");
    }

    @Test
    void theAcknowledgementAndStatusDecodersAgreeWithTheHandlersOnTheWholeParityCorpus() throws Exception {
        JsonNode corpus = MAPPER.readTree(Files.readAllBytes(corpusPath()));
        MqttJsonDecoder decoder = adapter(mock(MqttPublisher.class)).decoder();
        int ack = 0, status = 0;
        List<String> differences = new ArrayList<>();
        for (JsonNode item : corpus.get("items")) {
            String kind = item.get("kind").asText();
            if (!kind.equals("acknowledgement") && !kind.equals("status")) continue;
            byte[] payload = item.has("payloadBase64") ? Base64.getDecoder().decode(item.get("payloadBase64").asText()) : item.get("payloadText").asText().getBytes(StandardCharsets.UTF_8);
            if (kind.equals("acknowledgement")) {
                ack++;
                ActionHistoryRepository actions = mock(ActionHistoryRepository.class);
                String topic = "nexus/devices/" + DEVICE + "/commands/ack";
                String handler;
                try {
                    new DeviceCommandAckMessageHandler(actions, mock(RealtimePublisher.class), MAPPER).handle(topic, payload);
                    handler = mockingDetails(actions).getInvocations().stream().anyMatch(i -> i.getMethod().getName().equals("findById")) ? "ACCEPT" : "REJECT";
                } catch (RuntimeException e) {
                    handler = "THROWS";
                }
                Decoded<AckPayload> decoded = decoder.decodeAcknowledgement(new RawMessage(payload, topic));
                String actual = decoded.isAccepted() ? "ACCEPT" : "REJECT";
                // The handler throws DateTimeException for an out-of-range epoch second; a decoder must not throw, so it refuses.
                boolean expected = handler.equals(actual) || (handler.equals("THROWS") && actual.equals("REJECT"));
                if (!expected) differences.add(item.get("id").asText() + " handler=" + handler + " decoder=" + actual);
            } else {
                status++;
                DeviceRepository devices = mock(DeviceRepository.class);
                when(devices.existsById(any())).thenReturn(true);
                DeviceConnectivityRegistry registry = mock(DeviceConnectivityRegistry.class);
                String topic = "nexus/devices/" + DEVICE + "/status";
                new DeviceStatusMessageHandler(devices, registry, mock(RealtimePublisher.class)).handle(topic, payload);
                Optional<DeviceConnectivityStatus> applied = mockingDetails(registry).getInvocations().stream()
                        .filter(i -> i.getMethod().getName().equals("applyObservedStatus")).map(i -> (DeviceConnectivityStatus) i.getArgument(1)).findFirst();
                Decoded<ConnectivityObservation> decoded = decoder.decodeConnectivity(new RawMessage(payload, topic));
                String handlerVerdict = applied.map(Enum::name).orElse("REJECT");
                String decoderVerdict = decoded instanceof Decoded.Accepted<ConnectivityObservation> a ? a.value().state().name() : "REJECT";
                if (!handlerVerdict.equals(decoderVerdict)) differences.add(item.get("id").asText() + " handler=" + handlerVerdict + " decoder=" + decoderVerdict);
            }
        }
        assertTrue(ack >= 50 && status >= 20, "ack=" + ack + " status=" + status);
        assertEquals(List.of(), differences);
    }

    @Test
    void theTelemetryDecoderRefusesTheJsonNullCaseWithAFixedCodeInsteadOfThrowing() {
        MqttJsonDecoder decoder = adapter(mock(MqttPublisher.class)).decoder();
        var result = decoder.decodeTelemetry(new RawMessage("null".getBytes(StandardCharsets.UTF_8), "nexus/devices/" + DEVICE + "/telemetry"));
        assertEquals(RejectionCode.MALFORMED_JSON, ((Decoded.Refused<TelemetryPayload>) result).rejection().code());
    }

    @Test
    void theTelemetryDecoderReturnsTheLegacyAndGenericShapesAndTheTopicCheck() {
        MqttJsonDecoder decoder = adapter(mock(MqttPublisher.class)).decoder();
        String topic = "nexus/devices/" + DEVICE + "/telemetry";
        var legacy = decoder.decodeTelemetry(new RawMessage(("{\"deviceId\":\"" + DEVICE + "\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"sensorType\":\"TEMPERATURE_SENSOR\",\"value\":24.5,\"unit\":\"CELSIUS\"}").getBytes(StandardCharsets.UTF_8), topic));
        TelemetryPayload payload = ((Decoded.Accepted<TelemetryPayload>) legacy).value();
        assertEquals(24.5, payload.value());
        assertEquals("CELSIUS", payload.unit());
        assertEquals(null, payload.messageId(), "legacy firmware telemetry carries no messageId");
        var mismatch = decoder.decodeTelemetry(new RawMessage(("{\"deviceId\":\"" + UUID.randomUUID() + "\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"sensorType\":\"S\",\"value\":1,\"unit\":\"U\"}").getBytes(StandardCharsets.UTF_8), topic));
        assertEquals(RejectionCode.TOPIC_DEVICE_MISMATCH, ((Decoded.Refused<TelemetryPayload>) mismatch).rejection().code());
        assertEquals(DeviceConnectivityStatus.ONLINE.name(), ((Decoded.Accepted<ConnectivityObservation>) decoder.decodeConnectivity(
                new RawMessage(" online\n".getBytes(StandardCharsets.UTF_8), "nexus/devices/" + DEVICE + "/status"))).value().state().name());
        assertFalse(decoder.decodeConnectivity(new RawMessage("ONLINE".getBytes(StandardCharsets.UTF_8), "nexus/devices/" + DEVICE + "/status")).isAccepted(), "case-sensitive, like the handler");
    }

    // ------------------------------------------------------------------ egress

    @Test
    void p5_044_egressPutsTheStoredBytesVerbatimOnTheLogicalTopicAndStatesBrokerConfirmation() {
        AtomicReference<String> topic = new AtomicReference<>();
        AtomicReference<byte[]> bytes = new AtomicReference<>();
        AtomicReference<Duration> timeout = new AtomicReference<>();
        MqttJsonAdapter adapter = adapter((t, b, d) -> {
            topic.set(t);
            bytes.set(b);
            timeout.set(d);
            return new MqttPublisher.Result(MqttPublisher.Kind.CONFIRMED, null);
        });
        UUID device = UUID.fromString(DEVICE);
        byte[] stored = "{\"commandId\":\"a\",\"capability\":\"LED\",\"command\":\"SET_STATE\",\"value\":true,\"timestamp\":1767225600}".getBytes(StandardCharsets.UTF_8);
        Attempt attempt = adapter.deliver(new CommandEnvelope(UUID.randomUUID(), device, stored, "hash", 1767225600L, "c"), Duration.ofSeconds(7));
        assertEquals(Attempt.Outcome.CONFIRMED, attempt.outcome());
        assertEquals(EvidenceKind.BROKER_CONFIRMED, attempt.evidence());
        assertEquals("nexus/devices/" + DEVICE + "/commands", topic.get());
        assertEquals(MqttTopics.commands(device), topic.get());
        assertArrayEquals(stored, bytes.get(), "the bytes are not rebuilt, refreshed or reordered");
        assertEquals(Duration.ofSeconds(7), timeout.get());
    }

    @Test
    void theEgressClassificationIsTheBrokerPublishersWithoutAnyReinterpretation() {
        for (var entry : List.of(
                new MqttPublisher.Result(MqttPublisher.Kind.NOT_SENT, "CLIENT_NOT_CONNECTED"),
                new MqttPublisher.Result(MqttPublisher.Kind.NOT_SENT, "PERSISTENCE_REFUSED_32"),
                new MqttPublisher.Result(MqttPublisher.Kind.UNCERTAIN, "PUBLISH_TIMEOUT"),
                new MqttPublisher.Result(MqttPublisher.Kind.UNCERTAIN, "HANDOVER_ERROR"))) {
            MqttJsonAdapter adapter = adapter((t, b, d) -> entry);
            Attempt attempt = adapter.deliver(new CommandEnvelope(UUID.randomUUID(), UUID.fromString(DEVICE), new byte[]{1}, "h", 1L, "c"), Duration.ofSeconds(1));
            assertEquals(entry.kind().name(), attempt.outcome().name());
            assertEquals(entry.code(), attempt.code());
            assertEquals(null, attempt.evidence());
        }
    }

    // ------------------------------------------------------------------ descriptor

    @Test
    void theDescriptorDeclaresExactlyWhatTheAdapterImplementsAndReportsCountersOnly() {
        MqttJsonAdapter adapter = adapter(mock(MqttPublisher.class));
        assertEquals("mqtt-json", adapter.id());
        assertEquals(TransportKind.MQTT_JSON, adapter.transport());
        assertEquals(java.util.Set.of(1), adapter.supportedContractMajors());
        assertEquals(java.util.EnumSet.of(AdapterCapability.LIFECYCLE, AdapterCapability.TELEMETRY_DECODER, AdapterCapability.ACKNOWLEDGEMENT_DECODER,
                AdapterCapability.CONNECTIVITY_SOURCE, AdapterCapability.COMMAND_EGRESS), adapter.capabilities());
        new AdapterRegistry(List.of(adapter));   // throws if a declaration and an implementation disagree
        adapter.decodeTelemetry(new RawMessage("{".getBytes(StandardCharsets.UTF_8), "t"));
        adapter.decodeConnectivity(new RawMessage("online".getBytes(StandardCharsets.UTF_8), "nexus/devices/" + DEVICE + "/status"));
        var counters = adapter.diagnostics();
        assertEquals(1L, counters.get("brokerConnected"));
        assertEquals(1L, counters.get("telemetryRejected"));
        assertEquals(1L, counters.get("presenceAccepted"));
        counters.keySet().forEach(name -> assertTrue(name.matches("[a-zA-Z]+"), name));
        assertFalse(adapter.started());
        adapter.start();
        assertTrue(adapter.started());
        adapter.stop();
        assertFalse(adapter.started());
    }
}
