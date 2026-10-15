package com.nexus.action.outbox;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V37 on a real, disposable TimescaleDB/PostgreSQL: every constraint, trigger, index and foreign key of the
 * command_outbox table, driven with plain SQL so the database itself (not application code) is what is proven.
 */
@Testcontainers
class CommandOutboxSchemaTest {

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));

    /** Row count observed immediately after the migration, before any test inserts a row. */
    static long rowsRightAfterMigration = -1;

    @BeforeAll
    static void migrate() throws Exception {
        Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()).load().migrate();
        try (Connection c = connect()) {
            rowsRightAfterMigration = OutboxSql.count(c, "SELECT COUNT(*) FROM command_outbox");
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }

    /** Runs the body against a fresh device and command id; the command's action_history row already exists. */
    private static void withCommand(Consumer3 body) throws Exception {
        try (Connection c = connect()) {
            UUID device = OutboxSql.device(c);
            UUID command = OutboxSql.action(c, device, "ACCEPTED_FOR_DISPATCH", Instant.now());
            body.run(c, device, command);
        }
    }

    @FunctionalInterface
    private interface Consumer3 {
        void run(Connection c, UUID device, UUID command) throws Exception;
    }

    private static void assertRejected(String constraintHint, Consumer3 body) throws Exception {
        withCommand((c, device, command) -> {
            SQLException e = assertThrows(SQLException.class, () -> body.run(c, device, command));
            assertTrue(e.getMessage().contains(constraintHint),
                    "expected the database to reject with '" + constraintHint + "' but got: " + e.getMessage());
        });
    }

    // ------------------------------------------------------------------------------------------ fresh migration

    @Test
    void freshMigrationCreatesTableIndexesTriggersAndRecordsV37() throws Exception {
        try (Connection c = connect()) {
            assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '37' AND success"));
            assertEquals(0, rowsRightAfterMigration, "a new table starts empty: nothing is replayed");
            for (String index : new String[]{"idx_command_outbox_claim", "idx_command_outbox_device_unresolved", "idx_command_outbox_lease_expiry",
                    "idx_command_outbox_expiry", "idx_command_outbox_terminal", "uq_command_outbox_device_in_flight"})
                assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'command_outbox' AND indexname = '" + index + "'"), index);
            assertEquals(2, OutboxSql.count(c, "SELECT COUNT(*) FROM pg_trigger WHERE tgrelid = 'command_outbox'::regclass AND NOT tgisinternal"));
        }
    }

    @Test
    void aValidQueuedRowIsAccepted() throws Exception {
        withCommand((c, device, command) -> assertEquals(1, OutboxSql.insert(c, OutboxSql.queued(command, device, Instant.now()))));
    }

    // ------------------------------------------------------------------------------------------ payload integrity

    @Test
    void aCorrectPayloadHashIsAcceptedAndAWrongOneIsRejected() throws Exception {
        assertRejected("ck_outbox_payload_hash", (c, device, command) -> {
            Map<String, Object> row = OutboxSql.queued(command, device, Instant.now());
            row.put("payload_sha256", OutboxPayload.sha256Hex("something else"));
            OutboxSql.insert(c, row);
        });
    }

    @Test
    void aHashThatIsNotLowercaseHexIsRejected() throws Exception {
        // PostgreSQL reports only the first violated check, in name order; the shorter hash check sorts first
        assertRejected("ck_outbox_payload_hash", (c, device, command) -> {
            Map<String, Object> row = OutboxSql.queued(command, device, Instant.now());
            row.put("payload_sha256", ((String) row.get("payload_sha256")).toUpperCase());
            OutboxSql.insert(c, row);
        });
    }

    @Test
    void thePayloadMustCarryTheRowCommandIdAndTimestamp() throws Exception {
        assertRejected("ck_outbox_payload_command_id", (c, device, command) -> {
            Map<String, Object> row = OutboxSql.queued(command, device, Instant.now());
            String other = OutboxSql.payloadText(UUID.randomUUID(), (Long) row.get("payload_epoch_seconds"));
            row.put("payload_text", other);
            row.put("payload_sha256", OutboxPayload.sha256Hex(other));
            OutboxSql.insert(c, row);
        });
        assertRejected("ck_outbox_payload_timestamp", (c, device, command) -> {
            Map<String, Object> row = OutboxSql.queued(command, device, Instant.now());
            String other = OutboxSql.payloadText(command, (Long) row.get("payload_epoch_seconds") + 1);
            row.put("payload_text", other);
            row.put("payload_sha256", OutboxPayload.sha256Hex(other));
            OutboxSql.insert(c, row);
        });
    }

    @Test
    void aPayloadWithoutACommandIdOrNotAJsonObjectIsRejected() throws Exception {
        assertRejected("ck_outbox_payload_command_id", (c, device, command) -> {
            Map<String, Object> row = OutboxSql.queued(command, device, Instant.now());
            String text = "{\"capability\":\"LED\",\"timestamp\":" + row.get("payload_epoch_seconds") + "}";
            row.put("payload_text", text);
            row.put("payload_sha256", OutboxPayload.sha256Hex(text));
            OutboxSql.insert(c, row);
        });
        assertRejected("ck_outbox_payload", (c, device, command) -> {
            Map<String, Object> row = OutboxSql.queued(command, device, Instant.now());
            row.put("payload_text", "[1,2]");
            row.put("payload_sha256", OutboxPayload.sha256Hex("[1,2]"));
            OutboxSql.insert(c, row);
        });
    }

    @Test
    void theTopicMustBeTheCommandTopicOfTheRowDevice() throws Exception {
        assertRejected("ck_outbox_topic", (c, device, command) -> {
            Map<String, Object> row = OutboxSql.queued(command, device, Instant.now());
            row.put("topic", "nexus/devices/" + UUID.randomUUID() + "/commands");
            OutboxSql.insert(c, row);
        });
    }

    // ------------------------------------------------------------------------------------------ attempts and expiry

    @Test
    void attemptLimitsAreBoundedByTheDatabase() throws Exception {
        assertRejected("ck_outbox_max_attempts", (c, device, command) -> {
            Map<String, Object> row = OutboxSql.queued(command, device, Instant.now());
            row.put("max_attempts", 0);
            OutboxSql.insert(c, row);
        });
        assertRejected("ck_outbox_max_attempts", (c, device, command) -> {
            Map<String, Object> row = OutboxSql.queued(command, device, Instant.now());
            row.put("max_attempts", 6);
            OutboxSql.insert(c, row);
        });
        assertRejected("ck_outbox_attempt_count", (c, device, command) -> {
            Map<String, Object> row = OutboxSql.queued(command, device, Instant.now());
            row.put("state", "RETRY_WAIT");
            row.put("attempt_count", 4);
            row.put("max_attempts", 3);
            OutboxSql.insert(c, row);
        });
        assertRejected("ck_outbox_attempt_state", (c, device, command) -> {
            Map<String, Object> row = OutboxSql.queued(command, device, Instant.now());
            row.put("attempt_count", 1);   // a QUEUED row has never been attempted
            OutboxSql.insert(c, row);
        });
    }

    @Test
    void theDeliveryWindowIsPositiveAndNeverExceedsTwentySeconds() throws Exception {
        assertRejected("ck_outbox_expiry", (c, device, command) -> {
            Map<String, Object> row = OutboxSql.queued(command, device, Instant.now());
            row.put("expires_at", Timestamp.from(Instant.ofEpochSecond((Long) row.get("payload_epoch_seconds") + 21)));
            OutboxSql.insert(c, row);
        });
        assertRejected("ck_outbox_expiry", (c, device, command) -> {
            Map<String, Object> row = OutboxSql.queued(command, device, Instant.now());
            row.put("expires_at", Timestamp.from(Instant.ofEpochSecond((Long) row.get("payload_epoch_seconds"))));
            OutboxSql.insert(c, row);
        });
        withCommand((c, device, command) -> {
            Map<String, Object> row = OutboxSql.queued(command, device, Instant.now());
            row.put("expires_at", Timestamp.from(Instant.ofEpochSecond((Long) row.get("payload_epoch_seconds") + 20)));
            assertEquals(1, OutboxSql.insert(c, row), "exactly the 20 second ceiling is permitted");
        });
    }

    // ------------------------------------------------------------------------------------------ state coherence

    @Test
    void anUnknownStateIsRejected() throws Exception {
        // 'SENT' violates the state domain and, as a consequence, the state-dependent checks; PostgreSQL reports the first
        // violated check in name order, so assert the SQLSTATE (check_violation) and that an outbox check named it.
        withCommand((c, device, command) -> {
            Map<String, Object> row = OutboxSql.queued(command, device, Instant.now());
            row.put("state", "SENT");
            SQLException e = assertThrows(SQLException.class, () -> OutboxSql.insert(c, row));
            assertEquals("23514", e.getSQLState());
            assertTrue(e.getMessage().contains("ck_outbox_"), e.getMessage());
            assertEquals(0, OutboxSql.count(c, "SELECT COUNT(*) FROM command_outbox WHERE command_id = '" + command + "'"));
        });
    }

    @Test
    void leaseMetadataExistsIfAndOnlyIfTheRowIsInFlight() throws Exception {
        assertRejected("ck_outbox_lease", (c, device, command) -> {
            Map<String, Object> row = OutboxSql.queued(command, device, Instant.now());
            row.put("state", "IN_FLIGHT");
            row.put("attempt_count", 1);
            row.put("next_attempt_at", null);
            OutboxSql.insert(c, row);    // IN_FLIGHT without a lease
        });
        assertRejected("ck_outbox_lease", (c, device, command) -> {
            Map<String, Object> row = OutboxSql.queued(command, device, Instant.now());
            row.put("lease_owner", "worker-1");
            row.put("lease_expires_at", Timestamp.from(Instant.now().plusSeconds(6)));
            OutboxSql.insert(c, row);    // a lease on a QUEUED row
        });
        withCommand((c, device, command) -> {
            Map<String, Object> row = OutboxSql.queued(command, device, Instant.now());
            row.put("state", "IN_FLIGHT");
            row.put("attempt_count", 1);
            row.put("next_attempt_at", null);
            row.put("lease_owner", "worker-1");
            row.put("lease_expires_at", Timestamp.from(Instant.now().plusSeconds(6)));
            assertEquals(1, OutboxSql.insert(c, row));
        });
    }

    @Test
    void terminalRowsRecordTheirOutcomeCoherently() throws Exception {
        assertRejected("ck_outbox_terminal", (c, device, command) -> {
            Map<String, Object> row = OutboxSql.queued(command, device, Instant.now());
            row.put("state", "DEAD_LETTER");
            row.put("next_attempt_at", null);
            row.put("terminal_at", Timestamp.from(Instant.now()));
            OutboxSql.insert(c, row);    // a dead letter must say why
        });
        assertRejected("ck_outbox_terminal_reason", (c, device, command) -> {
            Map<String, Object> row = OutboxSql.queued(command, device, Instant.now());
            row.put("state", "DEAD_LETTER");
            row.put("next_attempt_at", null);
            row.put("terminal_at", Timestamp.from(Instant.now()));
            row.put("terminal_reason", "GAVE_UP");
            OutboxSql.insert(c, row);
        });
        assertRejected("ck_outbox_terminal", (c, device, command) -> {
            Map<String, Object> row = OutboxSql.queued(command, device, Instant.now());
            row.put("state", "PUBLISHED");
            row.put("attempt_count", 1);
            row.put("next_attempt_at", null);
            row.put("terminal_at", Timestamp.from(Instant.now()));
            OutboxSql.insert(c, row);    // PUBLISHED needs the confirmation time
        });
        withCommand((c, device, command) -> {
            Map<String, Object> row = OutboxSql.queued(command, device, Instant.now());
            row.put("state", "DEAD_LETTER");
            row.put("next_attempt_at", null);
            row.put("terminal_at", Timestamp.from(Instant.now()));
            row.put("terminal_reason", "EXPIRED");
            assertEquals(1, OutboxSql.insert(c, row), "a command may expire before any attempt");
        });
    }

    @Test
    void aQueuedRowNeedsItsEligibilityTime() throws Exception {
        assertRejected("ck_outbox_next_attempt", (c, device, command) -> {
            Map<String, Object> row = OutboxSql.queued(command, device, Instant.now());
            row.put("next_attempt_at", null);
            OutboxSql.insert(c, row);
        });
    }

    // ------------------------------------------------------------------------------------------ one row per command

    @Test
    void thereIsExactlyOneOutboxRowPerCommand() throws Exception {
        withCommand((c, device, command) -> {
            OutboxSql.insert(c, OutboxSql.queued(command, device, Instant.now()));
            SQLException e = assertThrows(SQLException.class, () -> OutboxSql.insert(c, OutboxSql.queued(command, device, Instant.now())));
            assertEquals("23505", e.getSQLState(), "primary key on command_id");
        });
    }

    @Test
    void anOutboxRowMustReferenceAnExistingActionHistoryCommand() throws Exception {
        try (Connection c = connect()) {
            UUID device = OutboxSql.device(c);
            SQLException e = assertThrows(SQLException.class, () -> OutboxSql.insert(c, OutboxSql.queued(UUID.randomUUID(), device, Instant.now())));
            assertEquals("23503", e.getSQLState());
        }
    }

    // ------------------------------------------------------------------------------------------ immutability and transitions

    @Test
    void identityPayloadAndBoundsAreImmutable() throws Exception {
        withCommand((c, device, command) -> {
            OutboxSql.insert(c, OutboxSql.queued(command, device, Instant.now()));
            for (String change : new String[]{
                    "payload_text = '{\"commandId\":\"" + command + "\",\"timestamp\":1}'",
                    "payload_sha256 = '" + "0".repeat(64) + "'",
                    "payload_epoch_seconds = payload_epoch_seconds + 1",
                    "expires_at = expires_at + INTERVAL '1 second'",
                    "max_attempts = 5",
                    "topic = 'nexus/devices/" + device + "/commands '",
                    "created_at = created_at - INTERVAL '1 hour'"}) {
                SQLException e = assertThrows(SQLException.class, () -> OutboxSql.update(c, command, change), change);
                assertTrue(e.getMessage().contains("immutable") || e.getMessage().contains("ck_outbox"), change + " -> " + e.getMessage());
            }
            assertEquals(1, OutboxSql.update(c, command, "last_error_code = 'X', updated_at = NOW()"), "mutable bookkeeping may change");
        });
    }

    @Test
    void onlyTheLegalTransitionsAreAllowedAndAttemptsCountClaims() throws Exception {
        withCommand((c, device, command) -> {
            OutboxSql.insert(c, OutboxSql.queued(command, device, Instant.now()));
            assertThrows(SQLException.class, () -> OutboxSql.update(c, command,
                    "state = 'PUBLISHED', attempt_count = 1, next_attempt_at = NULL, terminal_at = NOW(), first_publish_confirmed_at = NOW()"),
                    "QUEUED -> PUBLISHED skips the claim");
            assertThrows(SQLException.class, () -> OutboxSql.update(c, command,
                    "state = 'IN_FLIGHT', attempt_count = 0, next_attempt_at = NULL, lease_owner = 'w', lease_expires_at = NOW() + INTERVAL '6 seconds'"),
                    "claiming must increment attempt_count");
            assertEquals(1, OutboxSql.update(c, command,
                    "state = 'IN_FLIGHT', attempt_count = 1, next_attempt_at = NULL, lease_owner = 'w', lease_expires_at = NOW() + INTERVAL '6 seconds'"));
            assertThrows(SQLException.class, () -> OutboxSql.update(c, command, "attempt_count = 2"), "attempts change only on a claim");
            assertThrows(SQLException.class, () -> OutboxSql.update(c, command,
                    "state = 'QUEUED', lease_owner = NULL, lease_expires_at = NULL, next_attempt_at = NOW()"), "IN_FLIGHT -> QUEUED is not a transition");
            assertEquals(1, OutboxSql.update(c, command,
                    "state = 'RETRY_WAIT', lease_owner = NULL, lease_expires_at = NULL, next_attempt_at = NOW()"));
            assertEquals(1, OutboxSql.update(c, command,
                    "state = 'IN_FLIGHT', attempt_count = 2, next_attempt_at = NULL, lease_owner = 'w', lease_expires_at = NOW() + INTERVAL '6 seconds'"));
            assertEquals(1, OutboxSql.update(c, command,
                    "state = 'PUBLISHED', lease_owner = NULL, lease_expires_at = NULL, terminal_at = NOW(), first_publish_confirmed_at = NOW()"));
        });
    }

    @Test
    void terminalRowsAreImmutable() throws Exception {
        withCommand((c, device, command) -> {
            OutboxSql.insert(c, OutboxSql.queued(command, device, Instant.now()));
            OutboxSql.update(c, command, "state = 'DEAD_LETTER', next_attempt_at = NULL, terminal_at = NOW(), terminal_reason = 'EXPIRED'");
            assertThrows(SQLException.class, () -> OutboxSql.update(c, command, "last_error_code = 'LATE'"));
            assertThrows(SQLException.class, () -> OutboxSql.update(c, command,
                    "state = 'QUEUED', next_attempt_at = NOW(), terminal_at = NULL, terminal_reason = NULL"), "a dead letter is never requeued");
        });
    }

    @Test
    void atMostOneCommandPerDeviceIsInFlight() throws Exception {
        try (Connection c = connect()) {
            UUID device = OutboxSql.device(c), other = OutboxSql.device(c);
            UUID first = OutboxSql.action(c, device, "ACCEPTED_FOR_DISPATCH", Instant.now());
            UUID second = OutboxSql.action(c, device, "ACCEPTED_FOR_DISPATCH", Instant.now());
            UUID third = OutboxSql.action(c, other, "ACCEPTED_FOR_DISPATCH", Instant.now());
            for (UUID[] pair : new UUID[][]{{first, device}, {second, device}, {third, other}})
                OutboxSql.insert(c, OutboxSql.queued(pair[0], pair[1], Instant.now()));
            String claim = "state = 'IN_FLIGHT', attempt_count = 1, next_attempt_at = NULL, lease_owner = 'w', lease_expires_at = NOW() + INTERVAL '6 seconds'";
            assertEquals(1, OutboxSql.update(c, first, claim));
            SQLException e = assertThrows(SQLException.class, () -> OutboxSql.update(c, second, claim));
            assertEquals("23505", e.getSQLState(), "single in-flight per device is a unique index");
            assertEquals(1, OutboxSql.update(c, third, claim), "another device is unaffected");
        }
    }

    // ------------------------------------------------------------------------------------------ foreign keys and deletion

    @Test
    void aPendingObligationCannotBeDeletedAndItsActionHistoryRowCannotEither() throws Exception {
        withCommand((c, device, command) -> {
            OutboxSql.insert(c, OutboxSql.queued(command, device, Instant.now()));
            SQLException direct = assertThrows(SQLException.class, () -> {
                try (var s = c.createStatement()) { s.executeUpdate("DELETE FROM command_outbox WHERE command_id = '" + command + "'"); }
            });
            assertTrue(direct.getMessage().contains("cannot be deleted while its device exists"), direct.getMessage());
            SQLException parent = assertThrows(SQLException.class, () -> {
                try (var s = c.createStatement()) { s.executeUpdate("DELETE FROM action_history WHERE request_id = '" + command + "'"); }
            });
            assertEquals("23503", parent.getSQLState(), "ON DELETE RESTRICT protects the parent row");
            assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM command_outbox WHERE command_id = '" + command + "'"));
            assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM action_history WHERE request_id = '" + command + "'"));
        });
    }

    @Test
    void aResolvedObligationCanBePurgedAndThenItsActionHistoryRow() throws Exception {
        withCommand((c, device, command) -> {
            OutboxSql.insert(c, OutboxSql.queued(command, device, Instant.now()));
            OutboxSql.update(c, command, "state = 'DEAD_LETTER', next_attempt_at = NULL, terminal_at = NOW(), terminal_reason = 'RETRY_EXHAUSTED'");
            try (var s = c.createStatement()) {
                assertEquals(1, s.executeUpdate("DELETE FROM command_outbox WHERE command_id = '" + command + "'"));
                assertEquals(1, s.executeUpdate("DELETE FROM action_history WHERE request_id = '" + command + "'"));
            }
        });
    }

    @Test
    void removingTheDeviceIsRefusedWhileItHasADeliveryObligation() throws Exception {
        // V37 allowed the cascade and silently erased the obligation; V38 fails closed (details: CommandOutboxSafetySchemaTest).
        withCommand((c, device, command) -> {
            OutboxSql.insert(c, OutboxSql.queued(command, device, Instant.now()));
            SQLException e = assertThrows(SQLException.class, () -> {
                try (var s = c.createStatement()) { s.executeUpdate("DELETE FROM devices WHERE id = '" + device + "'"); }
            });
            assertTrue(e.getMessage().contains("DEVICE_HAS_UNRESOLVED_COMMANDS"), e.getMessage());
            assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM command_outbox WHERE command_id = '" + command + "'"));
            assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM devices WHERE id = '" + device + "'"));
        });
    }
}
