package com.nexus.telemetry.api.dto;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.io.IOException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The optional messageId (R17, decision A1, ledger P4-080 and P4-120): absent or null means "do not deduplicate", a valid
 * id is carried through, and a non-text value, a too long id or one outside the allowed characters is invalid input on the
 * same parsing and validation path the MQTT listener and the HTTP controller both use. Nothing is coerced.
 */
class TelemetryRequestMessageIdTest {
    private static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json().build();
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final UUID DEVICE = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static String json(String messageField) {
        return "{\"deviceId\":\"" + DEVICE + "\",\"timestamp\":\"2026-10-17T12:00:00Z\",\"sensorType\":\"TEMPERATURE\",\"value\":21.5,\"unit\":\"CELSIUS\"" + messageField + "}";
    }

    private static TelemetryRequest parse(String messageField) throws IOException {
        return MAPPER.readValue(json(messageField), TelemetryRequest.class);
    }

    private static boolean violatesMessageId(TelemetryRequest request) {
        return VALIDATOR.validate(request).stream().anyMatch(v -> v.getPropertyPath().toString().equals("messageId"));
    }

    @Test
    void anAbsentOrNullIdMeansNoDeduplicationAndIsValid() throws Exception {
        for (String field : new String[] {"", ",\"messageId\":null"}) {
            TelemetryRequest request = parse(field);
            assertNull(request.messageId(), field);
            assertTrue(VALIDATOR.validate(request).isEmpty(), "legacy payload stays valid: " + field);
        }
    }

    @Test
    void aPayloadExactlyAsTheCommittedFirmwareSendsItStillParsesUnchanged() throws Exception {
        TelemetryRequest request = MAPPER.readValue("{\"deviceId\":\"" + DEVICE + "\",\"timestamp\":\"2026-10-17T12:00:00Z\",\"sensorType\":\"TEMPERATURE\",\"value\":21.5,\"unit\":\"CELSIUS\"}", TelemetryRequest.class);
        assertEquals(DEVICE, request.deviceId());
        assertEquals(21.5, request.value());
        assertNull(request.messageId());
        assertNull(request.capabilityKey());
    }

    @Test
    void aValidIdIsCarriedThroughIncludingTheWholeAlphabetAndTheLengthLimit() throws Exception {
        for (String id : new String[] {"a", "A-z_0.9:Z", "550e8400-e29b-41d4-a716-446655440000", "x".repeat(64)}) {
            TelemetryRequest request = parse(",\"messageId\":\"" + id + "\"");
            assertEquals(id, request.messageId());
            assertTrue(VALIDATOR.validate(request).isEmpty(), id);
        }
    }

    @Test
    void aNonTextIdIsRejectedWhileParsingAndIsNeverCoercedToText() {
        for (String value : new String[] {"123", "1.5", "true", "false", "{\"a\":1}", "[\"x\"]"}) {
            JsonMappingException e = assertThrows(JsonMappingException.class, () -> parse(",\"messageId\":" + value), value);
            assertTrue(e.getPath().stream().anyMatch(r -> "messageId".equals(r.getFieldName())),
                    "the failure names the messageId field so the listener can classify it, was " + e.getPath());
        }
    }

    @Test
    void aTextIdOutsideTheFormatIsAViolationAndNeverTreatedAsAbsent() throws Exception {
        for (String id : new String[] {"", " ", " lead", "trail ", "has space", "slash/x", "semi;colon", "naïve", "tab\\t", "new\\nline", "x".repeat(65)}) {
            TelemetryRequest request = parse(",\"messageId\":\"" + id + "\"");
            assertTrue(violatesMessageId(request), "must be invalid: '" + id + "'");
        }
    }
}
