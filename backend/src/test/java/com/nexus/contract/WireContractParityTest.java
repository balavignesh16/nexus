package com.nexus.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.action.application.executor.CommandValueValidator;
import com.nexus.action.outbox.OutboxPayload;
import com.nexus.device.capability.CapabilityType;
import com.nexus.device.capability.DeviceCapability;
import com.nexus.device.command.DeviceCommandAckMessageHandler;
import com.nexus.device.connectivity.DeviceConnectivityRegistry;
import com.nexus.device.connectivity.DeviceConnectivityStatus;
import com.nexus.device.connectivity.DeviceStatusMessageHandler;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.infrastructure.mqtt.MqttReconnectController;
import com.nexus.infrastructure.mqtt.TelemetryMQTTListener;
import com.nexus.realtime.RealtimePublisher;
import com.nexus.telemetry.application.TelemetryService;
import com.nexus.telemetry.application.TelemetrySource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P5.1 differential test (ledger P5-003, P5-006, P5-009, P5-022, P5-156, P5-157, P5-158).
 *
 * The same real-payload corpus (contracts/parity/corpus.json) is run through the UNMODIFIED backend decoding paths and
 * through the standalone Python validator (one bounded batch in one {@code python -I} process, no network). Every
 * disagreement must be a registered, classified difference (contracts/parity/known-differences.json); an unregistered
 * one fails, and so does a registered one that no longer differs. Python-versus-Python agreement proves nothing, so the
 * backend side drives the real classes:
 * <ul>
 *   <li>telemetry: {@link TelemetryMQTTListener#messageArrived} with a Spring-Boot-default ObjectMapper and Bean Validation;</li>
 *   <li>acknowledgement: {@link DeviceCommandAckMessageHandler#handle} (accepted means it reached the command lookup);</li>
 *   <li>status: {@link DeviceStatusMessageHandler#handle} (accepted means the connectivity registry was updated);</li>
 *   <li>command: {@link OutboxPayload#create} must reproduce the corpus text byte for byte, and
 *       {@link CommandValueValidator} is compared with the validator's capability layer.</li>
 * </ul>
 */
class WireContractParityTest {
    private static final ObjectMapper CORPUS = new ObjectMapper();
    /** What Spring Boot builds by default: unknown properties ignored, java.time support registered. */
    private static final ObjectMapper PRODUCTION_MAPPER = Jackson2ObjectMapperBuilder.json().build();
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final String DEVICE = "3f2c8a54-9d1e-4b7a-8c61-0e5d2a7f9b13";

    private static Path root;
    private static JsonNode corpus;
    private static Map<String, JsonNode> pythonResults;
    private static final Map<String, String> backend = new LinkedHashMap<>();

    @BeforeAll
    static void runBothSides() throws Exception {
        root = repositoryRoot();
        corpus = CORPUS.readTree(Files.readAllBytes(root.resolve("contracts/parity/corpus.json")));
        pythonResults = runPython();
        for (JsonNode item : corpus.get("items")) {
            backend.put(item.get("id").asText(), evaluateBackend(item));
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    void everyDifferenceIsRegisteredClassifiedAndStillReal() throws Exception {
        Map<String, String[]> actual = new TreeMap<>();
        for (JsonNode item : corpus.get("items")) {
            String id = item.get("id").asText();
            String backendVerdict = backend.get(id);
            String validatorVerdict = pythonVerdict(item, pythonResults.get(id));
            if (backendVerdict == null || validatorVerdict == null) continue; // a side has no verdict for this item
            if (!backendVerdict.equals(validatorVerdict)) actual.put(id, new String[]{backendVerdict, validatorVerdict});
        }
        Files.createDirectories(Path.of("target"));
        Map<String, Object> report = new TreeMap<>();
        actual.forEach((id, pair) -> report.put(id, Map.of("backend", pair[0], "validator", pair[1])));
        CORPUS.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/parity-differences.json").toFile(), report);

        Map<String, String[]> registered = new TreeMap<>();
        for (JsonNode entry : CORPUS.readTree(Files.readAllBytes(root.resolve("contracts/parity/known-differences.json"))).get("entries")) {
            String cls = entry.get("class").asText();
            assertFalse(cls.equals("A"), "class A (a schema mistake) is fixed, never registered: " + entry.get("clause"));
            assertTrue(List.of("B", "C", "D", "E").contains(cls), "unknown class " + cls);
            assertTrue(entry.hasNonNull("rationale") && entry.hasNonNull("clause"), "an entry needs a clause and a rationale");
            entry.get("cases").fields().forEachRemaining(c -> {
                String[] previous = registered.put(c.getKey(), new String[]{c.getValue().get("backend").asText(), c.getValue().get("validator").asText()});
                assertEquals(null, previous, "case registered twice: " + c.getKey());
            });
        }
        List<String> problems = reconcile(actual, registered);
        assertTrue(problems.isEmpty(), () -> problems.size() + " parity problem(s):\n" + String.join("\n", problems));
    }

    /** Compares the actual differences with the registry: unregistered, changed (verdicts moved) and stale (no longer differs). */
    static List<String> reconcile(Map<String, String[]> actual, Map<String, String[]> registered) {
        List<String> problems = new ArrayList<>();
        actual.forEach((id, pair) -> {
            String[] expected = registered.get(id);
            if (expected == null) problems.add("UNREGISTERED " + id + ": backend=" + pair[0] + " validator=" + pair[1]);
            else if (!expected[0].equals(pair[0]) || !expected[1].equals(pair[1]))
                problems.add("CHANGED " + id + ": registered backend=" + expected[0] + " validator=" + expected[1] + ", now backend=" + pair[0] + " validator=" + pair[1]);
        });
        registered.keySet().forEach(id -> {
            if (!actual.containsKey(id)) problems.add("STALE " + id + " no longer differs");
        });
        return problems;
    }

    @Test
    void theRegistryCheckRejectsUnregisteredChangedAndStaleDifferences() {
        // P5-157: the comparison itself is mutation-tested with synthetic data.
        Map<String, String[]> actual = new TreeMap<>();
        actual.put("a", new String[]{"ACCEPT", "REJECT"});
        actual.put("b", new String[]{"ACCEPT", "REJECT"});
        Map<String, String[]> registered = new TreeMap<>();
        registered.put("a", new String[]{"ACCEPT", "REJECT"});
        assertEquals(1, reconcile(actual, registered).size(), "b is unregistered");
        assertTrue(reconcile(actual, registered).get(0).startsWith("UNREGISTERED b"));
        registered.put("b", new String[]{"REJECT", "REJECT"});
        assertTrue(reconcile(actual, registered).get(0).startsWith("CHANGED b"), "the backend verdict moved");
        registered.put("b", new String[]{"ACCEPT", "REJECT"});
        assertEquals(0, reconcile(actual, registered).size());
        registered.put("c", new String[]{"ACCEPT", "REJECT"});
        assertTrue(reconcile(actual, registered).get(0).startsWith("STALE c"), "c no longer differs");
    }

    @Test
    void realProducersThatTheBackendAcceptsAreNeverRejectedByTheValidator() {
        // P5-158: every frozen fixture, simulator payload and backend-emitted command must be accepted by both.
        List<String> rejected = new ArrayList<>();
        for (JsonNode item : corpus.get("items")) {
            String origin = item.get("origin").asText();
            if (!origin.startsWith("fixture:firmware") && !origin.startsWith("fixture:simulator")) continue;
            String id = item.get("id").asText();
            if ("ACCEPT".equals(backend.get(id)) && !"ACCEPT".equals(pythonVerdict(item, pythonResults.get(id)))) rejected.add(id);
        }
        assertTrue(rejected.isEmpty(), "validator stricter than the backend on real producer payloads: " + rejected);
    }

    @Test
    void backendEmittedCommandsAreReproducedByteForByteAndValidate() throws Exception {
        int emitted = 0;
        for (JsonNode item : corpus.get("items")) {
            if (!item.has("emit")) continue;
            emitted++;
            JsonNode emit = item.get("emit");
            Object value = CORPUS.treeToValue(emit.get("value"), Object.class);
            OutboxPayload payload = OutboxPayload.create(UUID.fromString(corpus.get("commandId").asText()), emit.get("capability").asText(),
                    emit.get("command").asText(), value, 1767225600L, new ObjectMapper());
            assertEquals(payload.text(), item.get("payloadText").asText(), "the corpus must mirror OutboxPayload.create: " + item.get("id").asText());
            JsonNode python = pythonResults.get(item.get("id").asText());
            assertEquals("ACCEPT", python.get("verdict").asText(), "the validator must accept what the backend emits: " + item.get("id").asText());
        }
        assertTrue(emitted >= 7, "the corpus must contain the backend-emitted commands for every built-in capability");
    }

    @Test
    void corpusCoversEveryKindAndIsLargeEnoughToMeanSomething() {
        Map<String, Integer> perKind = new TreeMap<>();
        for (JsonNode item : corpus.get("items")) perKind.merge(item.get("kind").asText(), 1, Integer::sum);
        assertTrue(perKind.getOrDefault("telemetry", 0) >= 80, perKind.toString());
        assertTrue(perKind.getOrDefault("acknowledgement", 0) >= 30, perKind.toString());
        assertTrue(perKind.getOrDefault("status", 0) >= 20, perKind.toString());
        assertTrue(perKind.getOrDefault("command", 0) >= 35, perKind.toString());
    }

    // ------------------------------------------------------------------ the Python side (one bounded batch)

    private static Map<String, JsonNode> runPython() throws Exception {
        Path sdk = root.resolve("sdk/python");
        Path output = Files.createTempFile("nexus-contract-parity", ".json");
        String boot = "import sys; sys.path.insert(0, sys.argv.pop(1)); from nexus_contract.cli import main; raise SystemExit(main(sys.argv[1:]))";
        String python = System.getenv().getOrDefault("NEXUS_PYTHON", "python");
        ProcessBuilder builder = new ProcessBuilder(python, "-I", "-c", boot, sdk.toString(), "validate-corpus",
                "--manifest", root.resolve("contracts/parity/corpus.json").toString(), "--output", output.toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("the Python validator did not finish within 120 s");
        }
        assertEquals(0, process.exitValue(), "python validator failed (a missing interpreter or package fails the gate, it is never skipped): " + log);
        Map<String, JsonNode> results = new LinkedHashMap<>();
        CORPUS.readTree(Files.readAllBytes(output)).get("results").fields().forEachRemaining(e -> results.put(e.getKey(), e.getValue()));
        Files.deleteIfExists(output);
        return results;
    }

    /** Normalised validator verdict for one item, comparable with the backend's. Null when the validator has none to compare. */
    private static String pythonVerdict(JsonNode item, JsonNode result) {
        String kind = item.get("kind").asText();
        if (kind.equals("command")) {
            // The command comparison is the capability layer against CommandValueValidator.
            if (!item.has("capabilityProfile")) return null;
            String status = result.get("layers").get("capability").get(0).asText();
            return switch (status) {
                case "PASS" -> "ACCEPT";
                case "FAIL" -> "REJECT";
                default -> null;
            };
        }
        String verdict = result.get("verdict").asText();
        if (verdict.equals("ACCEPT")) return "ACCEPT";
        return kind.equals("telemetry") ? "REJECT:" + result.get("code").asText() : "REJECT";
    }

    // ------------------------------------------------------------------ the backend side (unmodified production classes)

    private static String evaluateBackend(JsonNode item) throws Exception {
        byte[] payload = item.has("payloadBase64") ? Base64.getDecoder().decode(item.get("payloadBase64").asText())
                : item.get("payloadText").asText().getBytes(StandardCharsets.UTF_8);
        return switch (item.get("kind").asText()) {
            case "telemetry" -> telemetry(payload, item.get("topicDeviceId").asText());
            case "acknowledgement" -> acknowledgement(payload);
            case "status" -> status(payload);
            case "command" -> command(item, payload);
            default -> throw new IllegalStateException("kind");
        };
    }

    private static String telemetry(byte[] payload, String topicDevice) throws Exception {
        MqttClient mqtt = mock(MqttClient.class);
        TelemetryService service = mock(TelemetryService.class);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        TelemetryMQTTListener listener = new TelemetryMQTTListener(mqtt, service, PRODUCTION_MAPPER, VALIDATOR, "nexus/devices/+/telemetry",
                mock(MqttReconnectController.class), meters, "nexus/devices/+/status", mock(DeviceStatusMessageHandler.class),
                "nexus/devices/+/commands/ack", mock(DeviceCommandAckMessageHandler.class));
        listener.init();
        MqttMessage message = new MqttMessage(payload);
        message.setQos(1);
        message.setId(1);
        try {
            listener.messageArrived("nexus/devices/" + topicDevice + "/telemetry", message);
        } catch (RuntimeException e) {
            // The production listener lets this escape to the MQTT client callback (no ack, no counter): recorded, not hidden.
            return "EXCEPTION:" + e.getClass().getSimpleName();
        }
        for (Counter counter : meters.find("nexus.telemetry.permanent.rejections").counters()) {
            if (counter.count() > 0) return "REJECT:" + counter.getId().getTag("reason");
        }
        if (mockingDetails(service).getInvocations().stream().anyMatch(i -> i.getMethod().getName().equals("processTelemetry"))) {
            verify(service).processTelemetry(any(), eq(TelemetrySource.MQTT));
            return "ACCEPT";
        }
        return "NO_OUTCOME";
    }

    private static String acknowledgement(byte[] payload) {
        ActionHistoryRepository actions = mock(ActionHistoryRepository.class);
        DeviceCommandAckMessageHandler handler = new DeviceCommandAckMessageHandler(actions, mock(RealtimePublisher.class), PRODUCTION_MAPPER);
        try {
            handler.handle("nexus/devices/" + DEVICE + "/commands/ack", payload);
        } catch (RuntimeException e) {
            return "EXCEPTION:" + e.getClass().getSimpleName();
        }
        boolean reachedLookup = mockingDetails(actions).getInvocations().stream().anyMatch(i -> i.getMethod().getName().equals("findById"));
        return reachedLookup ? "ACCEPT" : "REJECT";
    }

    private static String status(byte[] payload) {
        DeviceRepository devices = mock(DeviceRepository.class);
        when(devices.existsById(any())).thenReturn(true);
        DeviceConnectivityRegistry registry = mock(DeviceConnectivityRegistry.class);
        DeviceStatusMessageHandler handler = new DeviceStatusMessageHandler(devices, registry, mock(RealtimePublisher.class));
        try {
            handler.handle("nexus/devices/" + DEVICE + "/status", payload);
        } catch (RuntimeException e) {
            return "EXCEPTION:" + e.getClass().getSimpleName();
        }
        boolean applied = mockingDetails(registry).getInvocations().stream().anyMatch(i -> i.getMethod().getName().equals("applyObservedStatus")
                && (i.getArgument(1) == DeviceConnectivityStatus.ONLINE || i.getArgument(1) == DeviceConnectivityStatus.OFFLINE));
        return applied ? "ACCEPT" : "REJECT";
    }

    @SuppressWarnings("unchecked")
    private static String command(JsonNode item, byte[] payload) throws Exception {
        if (!item.has("capabilityProfile")) return null;
        JsonNode profile = item.get("capabilityProfile");
        CapabilityType type;
        try {
            type = CapabilityType.valueOf(profile.get("type").asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
        Map<String, Object> body;
        try {
            body = PRODUCTION_MAPPER.readValue(payload, Map.class);
        } catch (Exception e) {
            return null;
        }
        if (body.get("value") == null || body.get("value") instanceof List) return null; // structurally invalid; not a semantic question
        DeviceCapability capability = mock(DeviceCapability.class);
        if (profile.has("channels")) {
            List<Integer> channels = new ArrayList<>();
            profile.get("channels").forEach(c -> channels.add(c.asInt()));
            when(capability.getMetadata()).thenReturn(Map.of("channels", channels));
        }
        try {
            CommandValueValidator.validate(type, capability, (String) body.get("command"), body.get("value"));
            return "ACCEPT";
        } catch (CommandValueValidator.CommandValidationException e) {
            return "REJECT";
        }
    }

    private static Path repositoryRoot() {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.isRegularFile(directory.resolve("contracts/versions.json"))) directory = directory.getParent();
        if (directory == null) throw new AssertionError("contracts/versions.json not found above the working directory");
        return directory;
    }
}
