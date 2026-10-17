package com.nexus.action.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The wire contract of a command (firmware/src/main.cpp and the simulator's command handler) enforced before persistence. */
class OutboxPayloadValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID id = UUID.randomUUID();
    private final long epoch = 1_800_000_000L;

    private OutboxPayload create(String capability, String command, Object value) {
        return OutboxPayload.create(id, capability, command, value, epoch, mapper);
    }

    private String rejected(OutboxPayload payload) {
        return assertThrows(IllegalArgumentException.class, () -> OutboxPayloadValidator.validate(id, payload, mapper)).getMessage();
    }

    @Test
    void aCompletePayloadIsAccepted() {
        assertDoesNotThrow(() -> OutboxPayloadValidator.validate(id, create("LED", "SET_STATE", true), mapper));
        assertDoesNotThrow(() -> OutboxPayloadValidator.validate(id, create("SERVO", "SET_ANGLE", 90), mapper));
        assertDoesNotThrow(() -> OutboxPayloadValidator.validate(id, create("RELAY", "SET_STATE", Map.of("channel", 1, "state", true)), mapper));
    }

    @Test
    void theWireFieldsAreExactlyTheFirmwareContractInOrder() {
        assertEquals("{\"commandId\":\"" + id + "\",\"capability\":\"LED\",\"command\":\"SET_STATE\",\"value\":true,\"timestamp\":" + epoch + "}",
                create("LED", "SET_STATE", true).text());
    }

    @Test
    void aMissingCapabilityCommandOrValueIsRefusedBeforePersistence() {
        assertTrue(rejected(create(null, "SET_STATE", true)).contains("capability"));
        assertTrue(rejected(create("", "SET_STATE", true)).contains("capability"));
        assertTrue(rejected(create("LED", null, true)).contains("command"));
        assertTrue(rejected(create("LED", " ", true)).contains("command"));
        assertTrue(rejected(create("LED", "SET_STATE", null)).contains("value"));
    }

    @Test
    void theTimestampMustBeAPositiveIntegerEqualToTheAcceptanceTime() {
        assertTrue(rejected(OutboxPayload.create(id, "LED", "SET_STATE", true, 0, mapper)).contains("timestamp"));
        String text = "{\"commandId\":\"" + id + "\",\"capability\":\"LED\",\"command\":\"SET_STATE\",\"value\":true,\"timestamp\":1800000000.5}";
        assertTrue(rejected(new OutboxPayload(text, OutboxPayload.sha256Hex(text), epoch)).contains("timestamp"), "a fractional timestamp is not an integer");
        String quoted = "{\"commandId\":\"" + id + "\",\"capability\":\"LED\",\"command\":\"SET_STATE\",\"value\":true,\"timestamp\":\"1800000000\"}";
        assertTrue(rejected(new OutboxPayload(quoted, OutboxPayload.sha256Hex(quoted), epoch)).contains("timestamp"), "a text timestamp is refused");
        assertTrue(rejected(new OutboxPayload(create("LED", "SET_STATE", true).text(), create("LED", "SET_STATE", true).sha256(), epoch + 1)).contains("timestamp"));
    }

    @Test
    void aWrongCommandIdAnUnexpectedFieldOrAnOversizePayloadIsRefused() {
        OutboxPayload other = OutboxPayload.create(UUID.randomUUID(), "LED", "SET_STATE", true, epoch, mapper);
        assertTrue(rejected(other).contains("commandId"));
        String extra = "{\"commandId\":\"" + id + "\",\"capability\":\"LED\",\"command\":\"SET_STATE\",\"value\":true,\"timestamp\":" + epoch + ",\"x\":1}";
        assertTrue(rejected(new OutboxPayload(extra, OutboxPayload.sha256Hex(extra), epoch)).contains("unexpected"));
        assertTrue(rejected(create("LED", "SET_STATE", "x".repeat(5000))).contains("too large"));
    }

    @Test
    void aNonObjectOrNonJsonPayloadIsRefused() {
        String array = "[1]";
        assertTrue(rejected(new OutboxPayload(array, OutboxPayload.sha256Hex(array), epoch)).contains("object"));
        String junk = "not json";
        assertTrue(rejected(new OutboxPayload(junk, OutboxPayload.sha256Hex(junk), epoch)).contains("JSON"));
    }
}
