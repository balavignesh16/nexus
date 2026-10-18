package com.nexus.action.outbox;

import com.nexus.action.outbox.OutboxDeliveryOperations.AttemptResult;
import com.nexus.action.outbox.OutboxDeliveryOperations.Claimed;
import com.nexus.action.outbox.OutboxDeliveryOperations.Disposition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spike S1 (adapter-contract.md section 20) turned into a permanent test, ledger P5-117: the Phase 4 command path (V37 CHECKs and
 * guard triggers, V39 history, the fenced operations) works unchanged for a device assigned HTTP_WEBHOOK, because the outbox topic
 * is the LOGICAL address nexus/devices/&lt;id&gt;/commands and nothing in V37 to V40 refers to a transport. The spike was run against
 * a disposable database BEFORE the V41 migration file was committed.
 */
@Testcontainers
class HttpTransportSchemaCompatibilityTest {

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));

    static OutboxTestKit kit;
    static final Duration LEASE = Duration.ofSeconds(6);

    @BeforeAll
    static void start() {
        OutboxTestKit.migrate(db);
        kit = new OutboxTestKit(db);
    }

    @AfterAll
    static void stop() {
        kit.close();
    }

    @BeforeEach
    void clean() {
        kit.jdbc.execute("TRUNCATE command_state_history, command_outbox");
    }

    private UUID httpDevice() {
        UUID device = kit.device();
        kit.jdbc.update("UPDATE devices SET transport = 'HTTP_WEBHOOK' WHERE id = ?", device);
        assertEquals("HTTP_WEBHOOK", kit.jdbc.queryForObject("SELECT transport FROM devices WHERE id = ?", String.class, device));
        return device;
    }

    private Claimed claim(UUID command) {
        Optional<Claimed> claimed = kit.ops.claim(command, "owner-" + UUID.randomUUID(), LEASE);
        assertTrue(claimed.isPresent());
        return claimed.get();
    }

    @Test
    void anHttpAssignedDevicesCommandRowSatisfiesTheV37TopicCheckAndCarriesTheLogicalTopic() {
        UUID device = httpDevice();
        UUID command = kit.enqueueDue(device, 0);
        assertEquals("nexus/devices/" + device + "/commands", kit.jdbc.queryForObject("SELECT topic FROM command_outbox WHERE command_id = ?", String.class, command));
        // The CHECK binds the logical string for every transport: an HTTP-looking address is refused even for an HTTP device.
        SQLException refused = assertThrows(SQLException.class, () -> {
            try (var c = kit.dataSource.getConnection()) {
                var row = OutboxSql.queued(UUID.randomUUID(), device, java.time.Instant.now());
                row.put("topic", "http/devices/" + device + "/commands");
                OutboxSql.insert(c, row);
            }
        });
        assertTrue(refused.getMessage().contains("ck_outbox_topic"), refused.getMessage());
    }

    @Test
    void theGuardTriggersStillProtectTheImmutableColumnsOfAnHttpDevicesCommand() {
        UUID command = kit.enqueueDue(httpDevice(), 0);
        for (String change : new String[]{"topic = 'nexus/devices/' || device_id || '/other'", "payload_text = payload_text || ' '", "device_id = gen_random_uuid()"}) {
            assertThrows(Exception.class, () -> kit.jdbc.update("UPDATE command_outbox SET " + change + " WHERE command_id = ?", command), change);
        }
    }

    @Test
    void claimAndConfirmedOutcomeGivePublishedWithTheNormalHistoryForAnHttpDevice() {
        UUID command = kit.enqueueDue(httpDevice(), 0);
        Claimed claimed = claim(command);
        assertEquals("IN_FLIGHT", kit.state(command));
        assertEquals(Disposition.PUBLISHED, kit.ops.complete(claimed, AttemptResult.CONFIRMED, null).disposition());
        assertEquals("PUBLISHED", kit.state(command));
        assertNotNull(kit.jdbc.queryForObject("SELECT first_publish_confirmed_at FROM command_outbox WHERE command_id = ?", java.sql.Timestamp.class, command));
        assertEquals(java.util.List.of("ATTEMPT_STARTED", "PUBLISHED"), kit.facts(command).subList(0, 2));
    }

    @Test
    void notSentRetriesAndUncertainDeadLettersExactlyAsForMqtt() {
        UUID retry = kit.enqueueDue(httpDevice(), 0);
        assertEquals(Disposition.RETRY_SCHEDULED, kit.ops.complete(claim(retry), AttemptResult.NOT_SENT, "CLIENT_GONE").disposition());
        assertEquals("RETRY_WAIT", kit.state(retry));
        UUID uncertain = kit.enqueueDue(httpDevice(), 1);
        var resolution = kit.ops.complete(claim(uncertain), AttemptResult.UNCERTAIN, "RESET_AFTER_FIRST_BYTE");
        assertEquals(Disposition.DEAD_LETTERED, resolution.disposition());
        assertTrue(resolution.uncertain());
        assertEquals("DEAD_LETTER", kit.state(uncertain));
        assertEquals("UNCERTAIN_EXPIRED", kit.terminalReason(uncertain));
    }

    @Test
    void anExpiredLeaseIsRecoveredAsUncertainForAnHttpDeviceAndAnOlderUncertainCommandHoldsTheNextOne() {
        UUID device = httpDevice();
        UUID first = kit.enqueueDue(device, 0);
        claim(first);
        kit.expireLease(first);
        assertEquals(1, kit.ops.recoverExpiredLeases(10).size());
        assertEquals("DEAD_LETTER", kit.state(first));
        assertEquals("UNCERTAIN_EXPIRED", kit.terminalReason(first));
        UUID next = kit.enqueueDue(device, 1);
        assertTrue(kit.ops.candidates(10).isEmpty() || !kit.ops.candidates(10).contains(next),
                "the R32 uncertain-delivery hold applies to an HTTP device exactly as to an MQTT one");
    }
}
