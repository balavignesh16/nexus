package com.nexus.telemetry.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fingerprint contract (R17, decision A2): deterministic, transport independent, type preserving and unambiguous. */
class TelemetryFingerprintTest {
    private static final ObjectMapper SPRING_LIKE = Jackson2ObjectMapperBuilder.json().build();
    private static final ObjectMapper PLAIN = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final UUID DEVICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant T = Instant.parse("2026-10-17T12:00:00Z");

    private static TelemetryRequest request(Object value) {
        return new TelemetryRequest(DEVICE, T, "TEMPERATURE", value, "CELSIUS", null, null, "id-1");
    }

    private static String fp(TelemetryRequest request) {
        return TelemetryFingerprint.of(request);
    }

    @Test
    void itIsALowercaseSha256HexAndStableAcrossCalls() {
        String once = fp(request(21.5));
        assertTrue(once.matches("[0-9a-f]{64}"), once);
        assertEquals(once, fp(request(21.5)));
    }

    @Test
    void theCanonicalTextIsExactlyTheDocumentedEncoding() {
        assertEquals("v1|sensorType=11:TEMPERATURE|capabilityKey=~|value=n:21.5|unit=7:CELSIUS|timestamp=1792238400.000000000|readbackCommandId=~",
                TelemetryFingerprint.canonical(request(21.5)));
    }

    @Test
    void propertyOrderAndNumberSpellingNeverChangeTheFingerprint() throws Exception {
        String a = "{\"deviceId\":\"" + DEVICE + "\",\"timestamp\":\"2026-10-17T12:00:00Z\",\"sensorType\":\"TEMPERATURE\",\"value\":21.5,\"unit\":\"CELSIUS\",\"messageId\":\"id-1\"}";
        String b = "{\"messageId\":\"id-1\",\"unit\":\"CELSIUS\",\"value\":2.15e1,\"sensorType\":\"TEMPERATURE\",\"timestamp\":\"2026-10-17T12:00:00.000Z\",\"deviceId\":\"" + DEVICE + "\"}";
        String c = "{\"deviceId\":\"" + DEVICE + "\",\"timestamp\":\"2026-10-17T12:00:00Z\",\"sensorType\":\"TEMPERATURE\",\"value\":21.50,\"unit\":\"CELSIUS\"}";
        String expected = fp(SPRING_LIKE.readValue(a, TelemetryRequest.class));
        assertEquals(expected, fp(SPRING_LIKE.readValue(b, TelemetryRequest.class)));
        assertEquals(expected, fp(SPRING_LIKE.readValue(c, TelemetryRequest.class)), "the messageId is the key, not an input");
        assertEquals(expected, fp(PLAIN.readValue(a, TelemetryRequest.class)), "the same logical message parses to the same fingerprint through either mapper (MQTT or HTTP)");
        assertEquals(fp(request(1)), fp(request(1.0)));
        assertEquals(fp(request(1)), fp(request(1.00)));
        assertEquals(fp(request(100)), fp(request(1.0e2)));
        assertEquals(fp(request(0)), fp(request(-0.0)));
    }

    @Test
    void valueTypesAreNeverConfused() {
        List<Object> distinct = List.of(true, false, "true", "false", 1, "1", 0, "0", "", "21.5", 21.5, 21.6);
        for (int i = 0; i < distinct.size(); i++)
            for (int j = i + 1; j < distinct.size(); j++)
                assertNotEquals(fp(request(distinct.get(i))), fp(request(distinct.get(j))), distinct.get(i) + " vs " + distinct.get(j));
    }

    @Test
    void everyProcessingInputChangesTheFingerprintAndTheKeyAndTransportDoNot() {
        TelemetryRequest base = request(21.5);
        String expected = fp(base);
        assertNotEquals(expected, fp(new TelemetryRequest(DEVICE, T, "HUMIDITY", 21.5, "CELSIUS", null, null, "id-1")), "sensorType");
        assertNotEquals(expected, fp(new TelemetryRequest(DEVICE, T, "TEMPERATURE", 21.5, "CELSIUS", "temperature", null, "id-1")), "capabilityKey");
        assertNotEquals(expected, fp(new TelemetryRequest(DEVICE, T, "TEMPERATURE", 21.5, "FAHRENHEIT", null, null, "id-1")), "unit");
        assertNotEquals(expected, fp(new TelemetryRequest(DEVICE, T, "TEMPERATURE", 21.5, null, null, null, "id-1")), "an absent unit is not an empty or a present one");
        assertNotEquals(fp(new TelemetryRequest(DEVICE, T, "TEMPERATURE", 21.5, "", null, null, "id-1")), fp(new TelemetryRequest(DEVICE, T, "TEMPERATURE", 21.5, null, null, null, "id-1")), "empty vs absent");
        assertNotEquals(expected, fp(new TelemetryRequest(DEVICE, T.plusNanos(1), "TEMPERATURE", 21.5, "CELSIUS", null, null, "id-1")), "timestamp, to the nanosecond");
        assertNotEquals(expected, fp(new TelemetryRequest(DEVICE, T, "TEMPERATURE", 21.5, "CELSIUS", null, UUID.randomUUID(), "id-1")), "readback command");
        assertEquals(expected, fp(new TelemetryRequest(DEVICE, T, "TEMPERATURE", 21.5, "CELSIUS", null, null, "another-id")), "the messageId is excluded");
        assertEquals(expected, fp(new TelemetryRequest(UUID.randomUUID(), T, "TEMPERATURE", 21.5, "CELSIUS", null, null, "id-1")), "the device is excluded: the key is already per device");
    }

    @Test
    void fieldBoundariesCannotBeForgedThroughValuesContainingSeparators() {
        TelemetryRequest one = new TelemetryRequest(DEVICE, T, "a", 1, "CELSIUS", "b", null, "id-1");
        TelemetryRequest forged = new TelemetryRequest(DEVICE, T, "a|capabilityKey=1:b", 1, "CELSIUS", null, null, "id-1");
        assertNotEquals(fp(one), fp(forged));
        assertNotEquals(fp(new TelemetryRequest(DEVICE, T, "x", "s", null, null, null, "i")), fp(new TelemetryRequest(DEVICE, T, "x", "s|unit=1:s", null, null, null, "i")));
    }

    @Test
    void structuredValuesIgnoreKeyOrderButKeepArrayOrder() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("a", 1);
        first.put("b", List.of(1, 2));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("b", List.of(1, 2));
        second.put("a", 1.0);
        assertEquals(fp(request(first)), fp(request(second)));
        assertNotEquals(fp(request(List.of(1, 2))), fp(request(List.of(2, 1))));
        assertNotEquals(fp(request(Map.of("a", 1))), fp(request(Map.of("a", "1"))));
    }

    @Test
    void anAbsentTimestampIsNeverInvented() {
        TelemetryRequest withoutTimestamp = new TelemetryRequest(DEVICE, null, "TEMPERATURE", 21.5, "CELSIUS", null, null, "id-1");
        assertTrue(TelemetryFingerprint.canonical(withoutTimestamp).contains("|timestamp=~|"), "encoded as absent, not defaulted to now");
        assertEquals(fp(withoutTimestamp), fp(new TelemetryRequest(DEVICE, null, "TEMPERATURE", 21.5, "CELSIUS", null, null, "id-1")));
    }
}
