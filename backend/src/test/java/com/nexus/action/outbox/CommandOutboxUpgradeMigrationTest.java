package com.nexus.action.outbox;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A V36 database that already holds commands in every historical shape is upgraded to V37 (and whatever follows):
 * nothing is rewritten, nothing becomes publishable work, and the checksums of V1 to V36 are unchanged.
 */
@Testcontainers
class CommandOutboxUpgradeMigrationTest {

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }

    private static List<String> checksumsThroughV36(Connection c) throws SQLException {
        List<String> list = new ArrayList<>();
        try (var s = c.createStatement();
             var r = s.executeQuery("SELECT version || ':' || checksum FROM flyway_schema_history WHERE version ~ '^[0-9]+$' AND version::int <= 36 ORDER BY installed_rank")) {
            while (r.next()) list.add(r.getString(1));
        }
        return list;
    }

    private static String actionHistoryDump(Connection c) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT COALESCE(string_agg(t::text, '|' ORDER BY request_id), '') FROM action_history t")) {
            r.next();
            return r.getString(1);
        }
    }

    @Test
    void populatedV36UpgradeKeepsHistoryUntouchedAndCreatesNoPublishableWork() throws Exception {
        Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()).target("36").load().migrate();
        UUID accepted, published, acknowledged, failed, referenced;
        List<String> checksumsBefore;
        String actionsBefore;
        long desiredBefore;
        try (Connection c = connect()) {
            UUID device = OutboxSql.device(c);
            Instant old = Instant.now().minusSeconds(3600);
            accepted = OutboxSql.action(c, device, "ACCEPTED_FOR_DISPATCH", old);           // stranded before the upgrade
            published = OutboxSql.action(c, device, "SUCCESS", old);
            acknowledged = OutboxSql.action(c, device, "SUCCESS", old);
            failed = OutboxSql.action(c, device, "FAILURE", old);
            referenced = OutboxSql.action(c, device, "SUCCESS", old);
            try (var s = c.createStatement()) {
                s.executeUpdate("UPDATE action_history SET published_at = NOW(), ack_deadline_at = NOW() + INTERVAL '30 seconds' WHERE request_id IN ('" + published + "','" + acknowledged + "')");
                s.executeUpdate("UPDATE action_history SET ack_status = 'ACCEPTED', ack_received_at = NOW() WHERE request_id = '" + acknowledged + "'");
                s.executeUpdate("INSERT INTO current_desired_twin_state(device_id,target_key,legacy_capability_type,subtarget_key,capability_key,value_type,boolean_value,source,command_id,requested_at,accepted_for_dispatch_at) "
                        + "VALUES ('" + device + "','legacy:LED','LED','','led','BOOLEAN',true,'MANUAL_COMMAND','" + referenced + "',NOW(),NOW())");
            }
            checksumsBefore = checksumsThroughV36(c);
            actionsBefore = actionHistoryDump(c);
            desiredBefore = OutboxSql.count(c, "SELECT COUNT(*) FROM current_desired_twin_state");
        }
        assertEquals(36, checksumsBefore.size(), "V1 to V36");
        assertFalse(actionsBefore.isEmpty());

        Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()).load().migrate();

        try (Connection c = connect()) {
            assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '37' AND success"));
            assertEquals(checksumsBefore, checksumsThroughV36(c), "V1 to V36 are byte-for-byte unchanged");
            assertEquals(actionsBefore, actionHistoryDump(c), "every historical command row is identical after the upgrade");
            assertEquals(desiredBefore, OutboxSql.count(c, "SELECT COUNT(*) FROM current_desired_twin_state"));
            assertEquals(0, OutboxSql.count(c, "SELECT COUNT(*) FROM command_outbox"),
                    "no historical command was backfilled as publishable work; the stranded ACCEPTED_FOR_DISPATCH command has no outbox row");
            assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM action_history WHERE request_id = '" + accepted + "' AND status = 'ACCEPTED_FOR_DISPATCH'"));
            assertTrue(OutboxSql.count(c, "SELECT COUNT(*) FROM action_history") == 5);
        }
    }
}
