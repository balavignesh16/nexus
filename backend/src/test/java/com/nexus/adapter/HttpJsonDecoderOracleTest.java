package com.nexus.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.adapter.http.HttpJsonDecoder;
import com.nexus.adapter.spi.AckPayload;
import com.nexus.adapter.spi.Decoded;
import com.nexus.adapter.spi.TelemetryPayload;
import com.nexus.contract.PythonOracle;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The strict HTTP decoder against the standalone Python validator (the schema authority), item by item over the whole parity corpus plus seeded
 * mutations: the same verdict and the same fixed rejection code. There is no registry here on purpose: unlike the tolerant MQTT path, the HTTP
 * decoder must agree with the schemas exactly, so any difference is a defect in one of them.
 */
class HttpJsonDecoderOracleTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEVICE = "3f2c8a54-9d1e-4b7a-8c61-0e5d2a7f9b13";

    /** "ACCEPT" or "REJECT:<code>" from the Python result: the version and structural layers only (identity needs a topic HTTP does not have). */
    private static String pythonVerdict(JsonNode result) {
        for (String layer : new String[]{"version", "structural"}) {
            JsonNode entry = result.get("layers").get(layer);
            if (entry != null && entry.get(0).asText().equals("FAIL")) return "REJECT:" + entry.get(1).asText();
        }
        return "ACCEPT";
    }

    private static String javaVerdict(HttpJsonDecoder decoder, String kind, byte[] payload, String header) {
        Decoded<?> decoded = switch (kind) {
            case "telemetry" -> decoder.decodeTelemetry(payload, header);
            case "acknowledgement" -> decoder.decodeAcknowledgement(payload, header);
            default -> decoder.decodeStatus(payload);
        };
        return decoded instanceof Decoded.Refused<?> refused ? "REJECT:" + refused.rejection().code() : "ACCEPT";
    }

    @Test
    void theJavaDecoderAndThePythonValidatorAgreeOnEveryCorpusItemAndEveryMutation() throws Exception {
        Path root = PythonOracle.repositoryRoot();
        JsonNode corpus = JSON.readTree(Files.readAllBytes(root.resolve("contracts/parity/corpus.json")));
        List<Map<String, Object>> items = new ArrayList<>();
        Map<String, byte[]> payloads = new java.util.LinkedHashMap<>();
        Map<String, String> kinds = new java.util.LinkedHashMap<>();
        for (JsonNode item : corpus.get("items")) {
            String kind = item.get("kind").asText();
            if (kind.equals("command")) continue;
            String id = item.get("id").asText();
            payloads.put(id, item.has("payloadBase64") ? Base64.getDecoder().decode(item.get("payloadBase64").asText()) : item.get("payloadText").asText().getBytes(StandardCharsets.UTF_8));
            kinds.put(id, kind);
        }
        // seeded single- and multi-fault mutations of a valid telemetry and a valid acknowledgement
        String telemetry = "{\"deviceId\":\"" + DEVICE + "\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"sensorType\":\"TEMPERATURE_SENSOR\",\"value\":24.5,\"unit\":\"CELSIUS\",\"messageId\":\"m-1\"}";
        String ack = "{\"commandId\":\"a1b2c3d4-e5f6-4789-8abc-def012345678\",\"status\":\"ACCEPTED\",\"timestamp\":1767225600,\"reason\":\"STALE\"}";
        Random random = new Random(54L);
        for (int i = 0; i < 600; i++) {
            String base = i % 2 == 0 ? telemetry : ack;
            byte[] bytes = base.getBytes(StandardCharsets.UTF_8);
            for (int e = 0; e < 1 + random.nextInt(3); e++) {
                int at = random.nextInt(bytes.length);
                switch (random.nextInt(4)) {
                    case 0 -> bytes[at] = (byte) ("{}[]\",:0123456789ntfeE.-+ \t".charAt(random.nextInt(26)));
                    case 1 -> bytes = java.util.Arrays.copyOf(bytes, at);
                    case 2 -> { byte[] longer = new byte[bytes.length + 1]; System.arraycopy(bytes, 0, longer, 0, at); longer[at] = (byte) "\"{}:,0".charAt(random.nextInt(6)); System.arraycopy(bytes, at, longer, at + 1, bytes.length - at); bytes = longer; }
                    default -> bytes[at] = (byte) random.nextInt(128);
                }
            }
            String id = (i % 2 == 0 ? "telemetry" : "acknowledgement") + ":mutation-" + i;
            payloads.put(id, bytes);
            kinds.put(id, i % 2 == 0 ? "telemetry" : "acknowledgement");
        }
        // version and requires behaviour on valid bodies, which the corpus only touches lightly
        String[] versions = {"1.0", "1.1", "1.99", "2.0", "0.9", "1", "1.0.0", "01.0", "x", ""};
        int n = 0;
        for (String version : versions) {
            String body = telemetry.substring(0, telemetry.length() - 1) + ",\"contractVersion\":\"" + version + "\"}";
            payloads.put("telemetry:version-" + n, body.getBytes(StandardCharsets.UTF_8));
            kinds.put("telemetry:version-" + n++, "telemetry");
        }
        for (String requires : new String[]{"[]", "[\"x\"]", "[\"A\"]", "\"x\"", "[1]", "[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\",\"g\",\"h\",\"i\"]"}) {
            String body = ack.substring(0, ack.length() - 1) + ",\"contractVersion\":\"1.3\",\"requires\":" + requires + "}";
            payloads.put("acknowledgement:requires-" + n, body.getBytes(StandardCharsets.UTF_8));
            kinds.put("acknowledgement:requires-" + n++, "acknowledgement");
        }

        for (Map.Entry<String, byte[]> entry : payloads.entrySet()) {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("id", entry.getKey());
            item.put("kind", kinds.get(entry.getKey()));
            item.put("payloadBase64", Base64.getEncoder().encodeToString(entry.getValue()));
            items.add(item);
        }
        Path manifest = Files.createTempFile("http-oracle-corpus", ".json");
        JSON.writeValue(manifest.toFile(), Map.of("schemaVersion", 1, "items", items));
        Map<String, JsonNode> python = PythonOracle.validateCorpus(manifest);
        Files.deleteIfExists(manifest);

        HttpJsonDecoder decoder = new HttpJsonDecoder();
        List<String> differences = new ArrayList<>();
        int accepted = 0, rejected = 0;
        for (Map.Entry<String, byte[]> entry : payloads.entrySet()) {
            String id = entry.getKey();
            String kind = kinds.get(id);
            String actual = javaVerdict(decoder, kind, entry.getValue(), null);
            String expected = pythonVerdict(python.get(id));
            if (actual.startsWith("ACCEPT")) accepted++; else rejected++;
            if (!actual.equals(expected)) differences.add(id + " java=" + actual + " python=" + expected + " payload=" + new String(entry.getValue(), StandardCharsets.UTF_8).replace("\n", "\\n"));
        }
        assertTrue(accepted > 50 && rejected > 500, "the comparison exercised both verdicts: accepted=" + accepted + " rejected=" + rejected);
        assertEquals(List.of(), differences);
    }

    @Test
    void theHeaderVersionFollowsTheSameRulesAsThePythonResolver() {
        HttpJsonDecoder decoder = new HttpJsonDecoder();
        byte[] plain = ("{\"deviceId\":\"" + DEVICE + "\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"sensorType\":\"S\",\"value\":1,\"unit\":\"U\"}").getBytes(StandardCharsets.UTF_8);
        byte[] versioned = ("{\"contractVersion\":\"1.0\",\"deviceId\":\"" + DEVICE + "\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"sensorType\":\"S\",\"value\":1,\"unit\":\"U\"}").getBytes(StandardCharsets.UTF_8);
        assertEquals("ACCEPT", javaVerdict(decoder, "telemetry", plain, null));
        assertEquals("ACCEPT", javaVerdict(decoder, "telemetry", plain, "1.0"));
        assertEquals("ACCEPT", javaVerdict(decoder, "telemetry", plain, "1.7"));
        assertEquals("REJECT:UNSUPPORTED_CONTRACT_MAJOR", javaVerdict(decoder, "telemetry", plain, "2.0"));
        assertEquals("REJECT:INVALID_CONTRACT_VERSION", javaVerdict(decoder, "telemetry", plain, "abc"));
        assertEquals("ACCEPT", javaVerdict(decoder, "telemetry", versioned, "1.0"));
        assertEquals("REJECT:CONTRACT_VERSION_CONFLICT", javaVerdict(decoder, "telemetry", versioned, "1.1"));
    }

    @Test
    void decodedValuesAreTheTypedValuesTheCoreExpects() {
        HttpJsonDecoder decoder = new HttpJsonDecoder();
        var legacy = decoder.decodeTelemetry(("{\"deviceId\":\"" + DEVICE + "\",\"timestamp\":\"2026-10-17T12:00:00.123456+00:00\",\"sensorType\":\"TEMPERATURE\",\"value\":21.5,\"unit\":\"CELSIUS\",\"messageId\":\"m\"}").getBytes(StandardCharsets.UTF_8), null);
        TelemetryPayload payload = ((Decoded.Accepted<TelemetryPayload>) legacy).value();
        assertEquals(21.5, payload.value());
        assertEquals(java.time.Instant.parse("2026-10-17T12:00:00.123456Z"), payload.timestamp());
        assertEquals("m", payload.messageId());
        var integer = decoder.decodeTelemetry(("{\"deviceId\":\"" + DEVICE + "\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"sensorType\":\"S\",\"value\":5,\"unit\":\"U\"}").getBytes(StandardCharsets.UTF_8), null);
        assertEquals(5, ((Number) ((Decoded.Accepted<TelemetryPayload>) integer).value().value()).intValue());
        var generic = decoder.decodeTelemetry(("{\"deviceId\":\"" + DEVICE + "\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"capabilityKey\":\"motion\",\"value\":true}").getBytes(StandardCharsets.UTF_8), null);
        assertEquals(Boolean.TRUE, ((Decoded.Accepted<TelemetryPayload>) generic).value().value());
        var ack = decoder.decodeAcknowledgement("{\"commandId\":\"a1b2c3d4-e5f6-4789-8abc-def012345678\",\"status\":\"REJECTED\",\"timestamp\":1767225600,\"reason\":\"STALE\"}".getBytes(StandardCharsets.UTF_8), null);
        AckPayload a = ((Decoded.Accepted<AckPayload>) ack).value();
        assertEquals(AckPayload.Status.REJECTED, a.status());
        assertEquals("STALE", a.reason());
        assertEquals(1767225600L, a.deviceTimestamp().getEpochSecond());
        assertNotNull(decoder.decodeStatus("  online\n".getBytes(StandardCharsets.UTF_8)));
        assertTrue(((Decoded.Accepted<HttpJsonDecoder.StatusClaim>) decoder.decodeStatus("offline".getBytes(StandardCharsets.UTF_8))).value().online() == false);
    }
}
