package com.nexus.telemetry.inbox;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A populated V39 database is upgraded to V40: the telemetry hypertable (schema and rows), every other existing row and
 * the checksums of V1 to V39 are untouched, and the only addition is the empty dedupe table (R17, R23; ledger P4-072,
 * P4-086, P4-108).
 */
@Testcontainers
class InboundMessageDedupeUpgradeMigrationTest {

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }

    private static String checksumsThroughV39(Connection c) throws SQLException {
        return InboxSql.text(c, "SELECT string_agg(version || ':' || checksum, ',' ORDER BY installed_rank) FROM flyway_schema_history WHERE version ~ '^[0-9]+$' AND version::int <= 39");
    }

    private static String telemetryColumns(Connection c) throws SQLException {
        return InboxSql.text(c, "SELECT string_agg(column_name || ' ' || data_type || ' ' || is_nullable, ',' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_name = 'telemetry_history'");
    }

    private static String commandEvidence(Connection c) throws SQLException {
        return InboxSql.text(c, "SELECT (SELECT COALESCE(string_agg(t::text, '|' ORDER BY request_id), '') FROM action_history t)"
                + " || '#' || (SELECT COUNT(*) FROM command_outbox) || '#' || (SELECT COUNT(*) FROM command_state_history)"
                + " || '#' || (SELECT COALESCE(string_agg(t::text, '|' ORDER BY command_id), '') FROM current_desired_twin_state t)");
    }

    private static void historicalCommand(Connection c, UUID device, String status, String extraSet) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var s = c.createStatement()) {
            s.executeUpdate("INSERT INTO action_history(request_id,match_id,device_id,action_type,status,parameters,timestamp,message) VALUES ('"
                    + id + "','" + UUID.randomUUID() + "','" + device + "','SEND_COMMAND','" + status + "','{}'::jsonb, NOW() - INTERVAL '1 hour','historical')");
            if (!extraSet.isEmpty()) s.executeUpdate("UPDATE action_history SET " + extraSet + " WHERE request_id = '" + id + "'");
        }
    }

    private static String telemetryRows(Connection c) throws SQLException {
        return InboxSql.text(c, "SELECT COALESCE(string_agg(t::text, '|' ORDER BY id), '') FROM telemetry_history t");
    }

    @Test
    void populatedV39UpgradeKeepsTelemetryAndHistoryUntouchedAndAddsOnlyTheEmptyTable() throws Exception {
        Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()).target("39").load().migrate();
        String checksumsBefore, columnsBefore, rowsBefore, hypertableBefore, commandsBefore;
        long devicesBefore;
        try (Connection c = connect()) {
            UUID device = InboxSql.device(c);
            try (var s = c.createStatement()) {
                for (int i = 0; i < 5; i++) {
                    s.executeUpdate("INSERT INTO telemetry_history(id,device_id,timestamp,sensor_type,value,unit) VALUES ('" + UUID.randomUUID() + "','" + device
                            + "', NOW() - INTERVAL '" + i + " minutes', 'TEMPERATURE', " + (20 + i) + ", 'CELSIUS')");
                }
            }
            historicalCommand(c, device, "ACCEPTED_FOR_DISPATCH", "");                                  // stranded before the upgrade
            historicalCommand(c, device, "SUCCESS", "published_at = NOW(), ack_deadline_at = NOW() + INTERVAL '30 seconds'");   // published
            historicalCommand(c, device, "SUCCESS", "published_at = NOW(), ack_deadline_at = NOW() + INTERVAL '30 seconds', ack_status = 'ACCEPTED', ack_received_at = NOW()");   // acknowledged
            historicalCommand(c, device, "FAILURE", "");                                                // failed
            assertEquals(0, InboxSql.count(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'inbound_message_dedupe'"), "V39 has no dedupe table");
            commandsBefore = commandEvidence(c);
            checksumsBefore = checksumsThroughV39(c);
            columnsBefore = telemetryColumns(c);
            rowsBefore = telemetryRows(c);
            hypertableBefore = InboxSql.text(c, "SELECT COUNT(*) || ':' || (SELECT COUNT(*) FROM timescaledb_information.dimensions WHERE hypertable_name = 'telemetry_history') FROM timescaledb_information.hypertables WHERE hypertable_name = 'telemetry_history'");
            devicesBefore = InboxSql.count(c, "SELECT COUNT(*) FROM devices");
        }
        assertEquals(39, checksumsBefore.split(",").length, "V1 to V39");
        assertFalse(rowsBefore.isEmpty());

        Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()).load().migrate();

        try (Connection c = connect()) {
            assertEquals(checksumsBefore, checksumsThroughV39(c), "V1 to V39 checksums are unchanged");
            assertEquals("40", InboxSql.text(c, "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1"));
            assertEquals(columnsBefore, telemetryColumns(c), "telemetry_history schema is unchanged (P4-086)");
            assertEquals(rowsBefore, telemetryRows(c), "telemetry rows are identical");
            assertEquals(hypertableBefore, InboxSql.text(c, "SELECT COUNT(*) || ':' || (SELECT COUNT(*) FROM timescaledb_information.dimensions WHERE hypertable_name = 'telemetry_history') FROM timescaledb_information.hypertables WHERE hypertable_name = 'telemetry_history'"),
                    "still one hypertable with the same dimensions");
            assertEquals(commandsBefore, commandEvidence(c), "published, acknowledged, failed and stranded command rows, and the outbox and state history, are identical (P4-072)");
            assertEquals(devicesBefore, InboxSql.count(c, "SELECT COUNT(*) FROM devices"));
            assertEquals(0, InboxSql.count(c, "SELECT COUNT(*) FROM inbound_message_dedupe"), "the upgrade invents no dedupe entry");
            assertTrue(InboxSql.count(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'inbound_message_dedupe'") == 1);
        }
    }
}
