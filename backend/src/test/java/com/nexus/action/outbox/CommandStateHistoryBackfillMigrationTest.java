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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V38 to V39 upgrade of a populated database (contract R16). The legacy DELIVERY_UNKNOWN fact is appended ONLY for a command
 * that is demonstrably stranded: SEND_COMMAND, still ACCEPTED_FOR_DISPATCH, no publication time, no ACK, no outbox row and
 * older than the 5 minute grace. Recently accepted rows (which a legacy dispatch could still be publishing), published,
 * failed, acknowledged and non-command rows are never classified, no ActionHistory row changes, no outbox row appears and
 * nothing is published. V1 to V38 checksums are unchanged.
 */
@Testcontainers
class CommandStateHistoryBackfillMigrationTest {

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }

    private static List<String> checksumsThroughV38(Connection c) throws SQLException {
        List<String> list = new ArrayList<>();
        try (var s = c.createStatement();
             var r = s.executeQuery("SELECT version || ':' || checksum FROM flyway_schema_history WHERE version ~ '^[0-9]+$' AND version::int <= 38 ORDER BY installed_rank")) {
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

    private static boolean hasUnknown(Connection c, UUID id) throws SQLException {
        return OutboxSql.count(c, "SELECT COUNT(*) FROM command_state_history WHERE command_id = '" + id + "' AND fact_type = 'DELIVERY_UNKNOWN'") == 1;
    }

    @Test
    void onlyDemonstrablyStrandedLegacyCommandsReceiveAnUnknownFactAndNothingElseChanges() throws Exception {
        Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()).target("38").load().migrate();
        UUID stranded, strandedLong, recent, justInsideGrace, publishedAtSet, acked, success, failure, withOutbox, alert;
        List<String> checksumsBefore;
        String actionsBefore;
        try (Connection c = connect()) {
            UUID device = OutboxSql.device(c);
            Instant now = Instant.now();
            stranded = OutboxSql.action(c, device, "ACCEPTED_FOR_DISPATCH", now.minusSeconds(10 * 60));
            strandedLong = OutboxSql.action(c, device, "ACCEPTED_FOR_DISPATCH", now.minusSeconds(86400L * 30));
            recent = OutboxSql.action(c, device, "ACCEPTED_FOR_DISPATCH", now.minusSeconds(60));
            justInsideGrace = OutboxSql.action(c, device, "ACCEPTED_FOR_DISPATCH", now.minusSeconds(4 * 60));
            publishedAtSet = OutboxSql.action(c, device, "ACCEPTED_FOR_DISPATCH", now.minusSeconds(3600));
            acked = OutboxSql.action(c, device, "ACCEPTED_FOR_DISPATCH", now.minusSeconds(3600));
            success = OutboxSql.action(c, device, "SUCCESS", now.minusSeconds(3600));
            failure = OutboxSql.action(c, device, "FAILURE", now.minusSeconds(3600));
            withOutbox = OutboxSql.action(c, device, "ACCEPTED_FOR_DISPATCH", now.minusSeconds(3600));
            alert = UUID.randomUUID();
            try (var s = c.createStatement()) {
                s.executeUpdate("UPDATE action_history SET published_at = NOW() - INTERVAL '50 minutes' WHERE request_id = '" + publishedAtSet + "'");
                s.executeUpdate("UPDATE action_history SET ack_status = 'ACCEPTED', ack_received_at = NOW() WHERE request_id = '" + acked + "'");
                s.executeUpdate("INSERT INTO action_history(request_id,match_id,device_id,action_type,status,parameters,timestamp,message) VALUES ('"
                        + alert + "',NULL,'" + device + "','CREATE_ALERT','ACCEPTED_FOR_DISPATCH','{}'::jsonb, NOW() - INTERVAL '1 hour','x')");
            }
            // an outbox row cannot exist before V37 shipped, but the guard is part of the contract: it must be honoured
            OutboxSql.insert(c, OutboxSql.queued(withOutbox, device, now));
            checksumsBefore = checksumsThroughV38(c);
            actionsBefore = actionHistoryDump(c);
        }

        Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()).load().migrate();

        try (Connection c = connect()) {
            assertTrue(hasUnknown(c, stranded), "stranded 10 minutes");
            assertTrue(hasUnknown(c, strandedLong), "stranded 30 days");
            for (UUID untouched : new UUID[]{recent, justInsideGrace, publishedAtSet, acked, success, failure, withOutbox, alert})
                assertEquals(0, OutboxSql.count(c, "SELECT COUNT(*) FROM command_state_history WHERE command_id = '" + untouched + "'"),
                        "must not be classified: " + untouched);
            assertEquals(2, OutboxSql.count(c, "SELECT COUNT(*) FROM command_state_history"), "exactly the two stranded commands");
            // the fact is conservative and honest
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT sequence, source, reason_code, to_state, attempt FROM command_state_history WHERE command_id = '" + stranded + "'")) {
                assertTrue(r.next());
                assertEquals(1, r.getInt("sequence"));
                assertEquals("MIGRATION", r.getString("source"));
                assertEquals("LEGACY_DELIVERY_UNRECORDED", r.getString("reason_code"));
                assertEquals("DELIVERY_UNKNOWN", r.getString("to_state"));
                assertEquals(0, r.getInt("attempt"));
            }
            // nothing was rewritten, replayed or made publishable
            assertEquals(actionsBefore, actionHistoryDump(c), "no ActionHistory row changed");
            assertEquals(checksumsBefore, checksumsThroughV38(c), "V1 to V38 checksums are unchanged");
            assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM command_outbox"), "no outbox row was created (only the pre-existing one)");
            assertEquals(0, OutboxSql.count(c, "SELECT COUNT(*) FROM command_outbox WHERE command_id IN ('" + stranded + "','" + strandedLong + "')"));
        }
    }
}
