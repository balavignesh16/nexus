package com.nexus.action.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure model tests: delivery states, payload representation and the shape of transition records. */
class OutboxModelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ----------------------------------------------------------------------------------------- state machine

    @Test
    void exactlyTheContractTransitionsAreLegal() {
        Map<OutboxState, Set<OutboxState>> legal = Map.of(
                OutboxState.QUEUED, EnumSet.of(OutboxState.IN_FLIGHT, OutboxState.DEAD_LETTER),
                OutboxState.RETRY_WAIT, EnumSet.of(OutboxState.IN_FLIGHT, OutboxState.DEAD_LETTER),
                OutboxState.IN_FLIGHT, EnumSet.of(OutboxState.PUBLISHED, OutboxState.RETRY_WAIT, OutboxState.DEAD_LETTER),
                OutboxState.PUBLISHED, EnumSet.noneOf(OutboxState.class),
                OutboxState.DEAD_LETTER, EnumSet.noneOf(OutboxState.class));
        for (OutboxState from : OutboxState.values())
            for (OutboxState to : OutboxState.values())
                assertEquals(legal.get(from).contains(to), from.canTransitionTo(to), from + " -> " + to);
    }

    @Test
    void terminalAndUnresolvedStatesPartitionTheStates() {
        assertTrue(OutboxState.PUBLISHED.isTerminal());
        assertTrue(OutboxState.DEAD_LETTER.isTerminal());
        for (OutboxState s : Set.of(OutboxState.QUEUED, OutboxState.IN_FLIGHT, OutboxState.RETRY_WAIT)) {
            assertFalse(s.isTerminal(), s.name());
            assertTrue(s.isUnresolved(), s.name());
        }
        assertFalse(OutboxState.PUBLISHED.isUnresolved());
        assertFalse(OutboxState.DEAD_LETTER.isUnresolved());
    }

    @Test
    void aDeadLetteredCommandIsNeverRequeued() {
        for (OutboxState to : OutboxState.values()) assertFalse(OutboxState.DEAD_LETTER.canTransitionTo(to), to.name());
    }

    // ----------------------------------------------------------------------------------------- payload

    @Test
    void payloadHashIsTheKnownSha256OfItsUtf8Bytes() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", OutboxPayload.sha256Hex("abc"));
    }

    @Test
    void aNonAsciiPayloadHasTheIndependentlyVerifiedUtf8Hash() {
        // u-umlaut, euro sign and an emoji (a surrogate pair in Java). The expected value is the SHA-256 of the UTF-8 bytes,
        // computed independently with Python's hashlib; PostgreSQL gives the same value (CommandOutboxSafetySchemaTest).
        String text = "{\"commandId\":\"x\",\"value\":\"ü€😀\"}";
        assertEquals("06576136942a9a8da9dca424378ea040f36aefd9f85ca21e95313010bf2edd56", OutboxPayload.sha256Hex(text));
        assertTrue(new OutboxPayload(text, OutboxPayload.sha256Hex(text), 1L).verify());
    }

    @Test
    void payloadKeepsTheWireContractFieldOrderAndIsStable() {
        UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
        OutboxPayload p = OutboxPayload.create(id, "LED", "SET_STATE", true, 1760000000L, MAPPER);
        assertEquals("{\"commandId\":\"" + id + "\",\"capability\":\"LED\",\"command\":\"SET_STATE\",\"value\":true,\"timestamp\":1760000000}", p.text());
        assertEquals(1760000000L, p.epochSeconds());
        assertTrue(p.verify());
        OutboxPayload again = OutboxPayload.create(id, "LED", "SET_STATE", true, 1760000000L, MAPPER);
        assertEquals(p, again, "identical inputs produce identical bytes");
        assertEquals(p.text(), new String(p.bytes(), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void payloadSupportsObjectValuesLikeRelayChannels() {
        UUID id = UUID.randomUUID();
        OutboxPayload p = OutboxPayload.create(id, "RELAY", "SET_STATE", Map.of("channel", 2, "state", true), 1760000000L, MAPPER);
        assertTrue(p.text().contains("\"value\":{"));
        assertTrue(p.verify());
    }

    @Test
    void aTamperedPayloadFailsVerification() {
        UUID id = UUID.randomUUID();
        OutboxPayload good = OutboxPayload.create(id, "LED", "SET_STATE", true, 1760000000L, MAPPER);
        OutboxPayload tampered = new OutboxPayload(good.text().replace("true", "false"), good.sha256(), good.epochSeconds());
        assertFalse(tampered.verify());
    }

    @Test
    void anInvalidHashOrEmptyTextCannotBeRepresented() {
        assertThrows(IllegalArgumentException.class, () -> new OutboxPayload("{}", "abc", 1L));
        assertThrows(IllegalArgumentException.class, () -> new OutboxPayload("{}", "A".repeat(64), 1L));
        assertThrows(IllegalArgumentException.class, () -> new OutboxPayload("", OutboxPayload.sha256Hex(""), 1L));
        assertThrows(IllegalArgumentException.class, () -> new OutboxPayload(null, OutboxPayload.sha256Hex("x"), 1L));
    }

    // ----------------------------------------------------------------------------------------- entity factory

    @Test
    void aQueuedEntityCarriesTheFrozenPayloadAndABoundedExpiry() {
        UUID command = UUID.randomUUID(), device = UUID.randomUUID();
        long epoch = Instant.parse("2026-10-15T10:00:00Z").getEpochSecond();
        OutboxPayload payload = OutboxPayload.create(command, "LED", "SET_STATE", true, epoch, MAPPER);
        Instant now = Instant.parse("2026-10-15T10:00:00.400Z");
        CommandOutboxEntity e = CommandOutboxEntity.queued(command, device, payload, Duration.ofSeconds(15), 3, "corr-1", now);
        assertEquals(OutboxState.QUEUED, e.getState());
        assertEquals(0, e.getAttemptCount());
        assertEquals(3, e.getMaxAttempts());
        assertEquals("nexus/devices/" + device + "/commands", e.getTopic());
        assertEquals(Instant.parse("2026-10-15T10:00:15Z"), e.getExpiresAt(), "expiry = payload timestamp + window");
        assertEquals(payload, e.payload());
        assertEquals(now, e.getNextAttemptAt());
        assertNull(e.getLeaseOwner());
        assertNull(e.getTerminalAt());
    }

    @Test
    void anEntityCannotBeCreatedOutsideTheBounds() {
        UUID command = UUID.randomUUID(), device = UUID.randomUUID();
        OutboxPayload payload = OutboxPayload.create(command, "LED", "SET_STATE", true, 1760000000L, MAPPER);
        Instant now = Instant.now();
        assertThrows(IllegalArgumentException.class, () -> CommandOutboxEntity.queued(command, device, payload, Duration.ofSeconds(21), 3, null, now));
        assertThrows(IllegalArgumentException.class, () -> CommandOutboxEntity.queued(command, device, payload, Duration.ofSeconds(1), 3, null, now));
        assertThrows(IllegalArgumentException.class, () -> CommandOutboxEntity.queued(command, device, payload, Duration.ofSeconds(15), 0, null, now));
        assertThrows(IllegalArgumentException.class, () -> CommandOutboxEntity.queued(command, device, payload, Duration.ofSeconds(15), 6, null, now));
        OutboxPayload broken = new OutboxPayload(payload.text(), "0".repeat(64), payload.epochSeconds());
        assertThrows(IllegalArgumentException.class, () -> CommandOutboxEntity.queued(command, device, broken, Duration.ofSeconds(15), 3, null, now));
    }

    // ----------------------------------------------------------------------------------------- transition records

    @Test
    void transitionRecordsAcceptOnlyCreationAndLegalTransitions() {
        UUID id = UUID.randomUUID();
        Instant at = Instant.now();
        new OutboxTransitionRecord(id, null, OutboxState.QUEUED, 0, "ACCEPTED", "c", at);
        new OutboxTransitionRecord(id, OutboxState.QUEUED, OutboxState.IN_FLIGHT, 1, "CLAIMED", "c", at);
        new OutboxTransitionRecord(id, OutboxState.IN_FLIGHT, OutboxState.PUBLISHED, 1, "PUBLISHED", "c", at);
        assertThrows(IllegalArgumentException.class, () -> new OutboxTransitionRecord(id, null, OutboxState.IN_FLIGHT, 0, "X", "c", at));
        assertThrows(IllegalArgumentException.class, () -> new OutboxTransitionRecord(id, OutboxState.QUEUED, OutboxState.PUBLISHED, 0, "X", "c", at));
        assertThrows(IllegalArgumentException.class, () -> new OutboxTransitionRecord(id, OutboxState.DEAD_LETTER, OutboxState.QUEUED, 0, "X", "c", at));
        assertThrows(IllegalArgumentException.class, () -> new OutboxTransitionRecord(id, OutboxState.QUEUED, OutboxState.IN_FLIGHT, -1, "X", "c", at));
        assertThrows(IllegalArgumentException.class, () -> new OutboxTransitionRecord(null, null, OutboxState.QUEUED, 0, "X", "c", at));
    }

    @Test
    void firmwareFreshnessLimitsDeriveTheTwentySecondCeiling() {
        assertEquals(Duration.ofSeconds(30), CommandDeliveryLimits.FIRMWARE_FRESHNESS_WINDOW);
        assertEquals(Duration.ofSeconds(10), CommandDeliveryLimits.CLOCK_UNCERTAINTY_MARGIN);
        assertEquals(Duration.ofSeconds(20), CommandDeliveryLimits.MAX_DELIVERY_WINDOW);
        assertTrue(CommandDeliveryLimits.MAX_DELIVERY_WINDOW.plus(CommandDeliveryLimits.CLOCK_UNCERTAINTY_MARGIN)
                .compareTo(CommandDeliveryLimits.FIRMWARE_FRESHNESS_WINDOW) <= 0,
                "window plus the uncertainty budget never exceeds the 30 second firmware check");
    }
}
