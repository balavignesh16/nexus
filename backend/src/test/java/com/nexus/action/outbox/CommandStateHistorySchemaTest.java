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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V39 command_state_history on a disposable TimescaleDB/PostgreSQL (Flyway V1 to latest): append-only, constrained, and
 * unable to block the retention of action_history (ledger P4-139): history leaves only WITH its action_history row.
 */
@Testcontainers
class CommandStateHistorySchemaTest {

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()).load().migrate();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }

    private static void fact(Connection c, UUID command, int sequence, String factType, String source, String reason, int attempt) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO command_state_history (command_id, sequence, fact_type, from_state, to_state,"
                + " attempt, reason_code, source, occurred_at) VALUES (?, ?, ?, NULL, 'QUEUED', ?, ?, ?, ?)")) {
            p.setObject(1, command);
            p.setInt(2, sequence);
            p.setString(3, factType);
            p.setInt(4, attempt);
            p.setString(5, reason);
            p.setString(6, source);
            p.setTimestamp(7, Timestamp.from(Instant.now()));
            p.executeUpdate();
        }
    }

    private static UUID command(Connection c) throws SQLException {
        return OutboxSql.action(c, OutboxSql.device(c), "ACCEPTED_FOR_DISPATCH", Instant.now());
    }

    @Test
    void aValidFactIsStoredAndTheSequenceIsUniquePerCommand() throws Exception {
        try (Connection c = connect()) {
            UUID command = command(c);
            fact(c, command, 1, "ACCEPTED", "ACCEPTANCE", "ACCEPTED", 0);
            fact(c, command, 2, "QUEUED", "ACCEPTANCE", "QUEUED", 0);
            assertEquals(2, OutboxSql.count(c, "SELECT COUNT(*) FROM command_state_history WHERE command_id = '" + command + "'"));
            SQLException dup = assertThrows(SQLException.class, () -> fact(c, command, 2, "QUEUED", "ACCEPTANCE", "QUEUED", 0));
            assertEquals("23505", dup.getSQLState(), "(command_id, sequence) is the primary key");
        }
    }

    @Test
    void historyIsAppendOnlyNoUpdateIsEverAllowed() throws Exception {
        try (Connection c = connect()) {
            UUID command = command(c);
            fact(c, command, 1, "ACCEPTED", "ACCEPTANCE", "ACCEPTED", 0);
            SQLException e = assertThrows(SQLException.class, () -> {
                try (var s = c.createStatement()) {
                    s.executeUpdate("UPDATE command_state_history SET reason_code = 'TAMPERED' WHERE command_id = '" + command + "'");
                }
            });
            assertTrue(e.getMessage().contains("append-only"), e.getMessage());
            assertEquals("ACCEPTED", stringOf(c, "SELECT reason_code FROM command_state_history WHERE command_id = '" + command + "'"));
        }
    }

    @Test
    void aDirectDeleteIsRefusedWhileTheActionHistoryRowExists() throws Exception {
        try (Connection c = connect()) {
            UUID command = command(c);
            fact(c, command, 1, "ACCEPTED", "ACCEPTANCE", "ACCEPTED", 0);
            SQLException e = assertThrows(SQLException.class, () -> {
                try (var s = c.createStatement()) {
                    s.executeUpdate("DELETE FROM command_state_history WHERE command_id = '" + command + "'");
                }
            });
            assertTrue(e.getMessage().contains("append-only"), e.getMessage());
            assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM command_state_history WHERE command_id = '" + command + "'"));
        }
    }

    @Test
    void historyLeavesWithItsActionHistoryRowSoItCanNeverBlockRetention() throws Exception {
        try (Connection c = connect()) {
            UUID device = OutboxSql.device(c);
            UUID withHistory = OutboxSql.action(c, device, "ACCEPTED_FOR_DISPATCH", Instant.now().minusSeconds(86400L * 120));
            UUID legacyUnknown = OutboxSql.action(c, device, "ACCEPTED_FOR_DISPATCH", Instant.now().minusSeconds(86400L * 120));
            fact(c, withHistory, 1, "ACCEPTED", "ACCEPTANCE", "ACCEPTED", 0);
            fact(c, withHistory, 2, "PUBLISHED", "DISPATCHER", "BROKER_CONFIRMED", 1);
            fact(c, legacyUnknown, 1, "DELIVERY_UNKNOWN", "MIGRATION", "LEGACY_DELIVERY_UNRECORDED", 0);
            try (var s = c.createStatement()) {
                // exactly the statement shape RetentionManager uses for the action_history category
                int deleted = s.executeUpdate("DELETE FROM action_history a WHERE a.timestamp < NOW() - INTERVAL '90 days'"
                        + " AND NOT EXISTS (SELECT 1 FROM command_outbox o WHERE o.command_id = a.request_id)"
                        + " AND NOT EXISTS (SELECT 1 FROM current_desired_twin_state d WHERE d.command_id = a.request_id)");
                assertTrue(deleted >= 2, "both old rows are eligible");
            }
            assertEquals(0, OutboxSql.count(c, "SELECT COUNT(*) FROM command_state_history WHERE command_id IN ('" + withHistory + "','" + legacyUnknown + "')"));
        }
    }

    @Test
    void anOutboxObligationStillProtectsItsActionRowAndTherefore_ItsHistory() throws Exception {
        try (Connection c = connect()) {
            UUID device = OutboxSql.device(c);
            UUID command = OutboxSql.action(c, device, "ACCEPTED_FOR_DISPATCH", Instant.now().minusSeconds(86400L * 120));
            OutboxSql.insert(c, OutboxSql.queued(command, device, Instant.now()));
            fact(c, command, 1, "ACCEPTED", "ACCEPTANCE", "ACCEPTED", 0);
            assertThrows(SQLException.class, () -> {
                try (var s = c.createStatement()) {
                    s.executeUpdate("DELETE FROM action_history WHERE request_id = '" + command + "'");
                }
            });
            assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM command_state_history WHERE command_id = '" + command + "'"));
        }
    }

    @Test
    void theConstraintsRejectUnknownFactsSourcesStatesAndMalformedCodes() throws Exception {
        try (Connection c = connect()) {
            UUID command = command(c);
            assertThrows(SQLException.class, () -> fact(c, command, 1, "SHIPPED", "ACCEPTANCE", "X", 0), "unknown fact type");
            assertThrows(SQLException.class, () -> fact(c, command, 1, "ACCEPTED", "NOBODY", "X", 0), "unknown source");
            assertThrows(SQLException.class, () -> fact(c, command, 0, "ACCEPTED", "ACCEPTANCE", "X", 0), "sequence starts at 1");
            assertThrows(SQLException.class, () -> fact(c, command, 1, "ACCEPTED", "ACCEPTANCE", "lower case", 0), "reason code format");
            assertThrows(SQLException.class, () -> fact(c, command, 1, "ACCEPTED", "ACCEPTANCE", "X", 6), "attempt bound");
            assertThrows(SQLException.class, () -> fact(c, UUID.randomUUID(), 1, "ACCEPTED", "ACCEPTANCE", "X", 0), "unknown command");
            assertEquals(0, OutboxSql.count(c, "SELECT COUNT(*) FROM command_state_history WHERE command_id = '" + command + "'"));
        }
    }

    @Test
    void rerunningTheBackfillStatementIsIdempotent() throws Exception {
        try (Connection c = connect()) {
            UUID device = OutboxSql.device(c);
            UUID stranded = OutboxSql.action(c, device, "ACCEPTED_FOR_DISPATCH", Instant.now().minusSeconds(3600));
            String backfill = "INSERT INTO command_state_history (command_id, sequence, fact_type, from_state, to_state, attempt, reason_code, source, occurred_at)"
                    + " SELECT a.request_id, 1, 'DELIVERY_UNKNOWN', NULL, 'DELIVERY_UNKNOWN', 0, 'LEGACY_DELIVERY_UNRECORDED', 'MIGRATION', now() FROM action_history a"
                    + " WHERE a.request_id = '" + stranded + "' AND NOT EXISTS (SELECT 1 FROM command_state_history h WHERE h.command_id = a.request_id)";
            try (var s = c.createStatement()) {
                assertEquals(1, s.executeUpdate(backfill));
                assertEquals(0, s.executeUpdate(backfill), "a command that already has a fact is skipped");
            }
        }
    }

    private static String stringOf(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery(sql)) {
            r.next();
            return r.getString(1);
        }
    }
}
