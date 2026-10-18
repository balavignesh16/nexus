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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ledger P5-112 and P5-117: a populated V40 database (devices, templates, a queued command, a published one) is upgraded to V41.
 * Every existing device and template reads MQTT_JSON, no row is lost or altered apart from the new column, the command rows
 * are identical, and the checksums of V1 to V40 are unchanged.
 */
@Testcontainers
class TransportUpgradeMigrationTest {

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }

    private static String dump(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery(sql)) {
            r.next();
            return r.getString(1) == null ? "" : r.getString(1);
        }
    }

    @Test
    void populatedV40UpgradeKeepsEveryRowDefaultsToMqttAndLeavesTheCommandPathAlone() throws Exception {
        Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()).target("40").load().migrate();
        UUID first, second, queued, published;
        String devicesBefore, templatesBefore, outboxBefore, historyBefore;
        long templateCount;
        List<String> checksums;
        try (Connection c = connect()) {
            first = OutboxSql.device(c);
            second = OutboxSql.device(c);
            Instant now = Instant.now();
            queued = OutboxSql.action(c, first, "ACCEPTED_FOR_DISPATCH", now);
            OutboxSql.insert(c, OutboxSql.queued(queued, first, now));
            published = OutboxSql.action(c, second, "SUCCESS", now);
            Instant earlier = now.minusSeconds(100);
            var row = OutboxSql.queued(published, second, earlier);
            row.put("state", "PUBLISHED");
            row.put("attempt_count", 1);
            row.put("next_attempt_at", null);
            row.put("first_publish_confirmed_at", java.sql.Timestamp.from(earlier.plusSeconds(1)));
            row.put("terminal_at", java.sql.Timestamp.from(earlier.plusSeconds(1)));
            OutboxSql.insert(c, row);
            templateCount = OutboxSql.count(c, "SELECT COUNT(*) FROM device_templates");
            devicesBefore = dump(c, "SELECT string_agg(to_jsonb(t)::text, '|' ORDER BY id) FROM devices t");
            templatesBefore = dump(c, "SELECT string_agg(to_jsonb(t)::text, '|' ORDER BY id) FROM device_templates t");
            outboxBefore = dump(c, "SELECT string_agg(to_jsonb(t)::text, '|' ORDER BY command_id) FROM command_outbox t");
            historyBefore = dump(c, "SELECT string_agg(to_jsonb(t)::text, '|' ORDER BY command_id, sequence) FROM command_state_history t");
            checksums = TransportSchemaV41Test.actualChecksumsThroughV40(c);
        }
        assertEquals(TransportSchemaV41Test.expectedChecksums(), checksums, "the pre-upgrade database is the pinned V1 to V40");
        assertTrue(templateCount > 0, "the migrations seed device templates, so the template default is exercised on real rows");
        assertFalse(devicesBefore.isEmpty());

        Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()).load().migrate();

        try (Connection c = connect()) {
            assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '41' AND success"));
            assertEquals(TransportSchemaV41Test.expectedChecksums(), TransportSchemaV41Test.actualChecksumsThroughV40(c), "V1 to V40 are byte-for-byte unchanged");
            assertEquals(2, OutboxSql.count(c, "SELECT COUNT(*) FROM devices WHERE transport = 'MQTT_JSON'"), "every existing device is MQTT_JSON");
            assertEquals(OutboxSql.count(c, "SELECT COUNT(*) FROM devices"), OutboxSql.count(c, "SELECT COUNT(*) FROM devices WHERE transport = 'MQTT_JSON'"));
            assertEquals(templateCount, OutboxSql.count(c, "SELECT COUNT(*) FROM device_templates WHERE default_transport = 'MQTT_JSON'"));
            assertEquals(devicesBefore, dump(c, "SELECT string_agg((to_jsonb(t) - 'transport')::text, '|' ORDER BY id) FROM devices t"), "no device column changed");
            assertEquals(templatesBefore, dump(c, "SELECT string_agg((to_jsonb(t) - 'default_transport')::text, '|' ORDER BY id) FROM device_templates t"));
            assertEquals(outboxBefore, dump(c, "SELECT string_agg(to_jsonb(t)::text, '|' ORDER BY command_id) FROM command_outbox t"), "the command outbox is untouched");
            assertEquals(historyBefore, dump(c, "SELECT string_agg(to_jsonb(t)::text, '|' ORDER BY command_id, sequence) FROM command_state_history t"));
            assertEquals(0, OutboxSql.count(c, "SELECT COUNT(*) FROM device_ingestion_credentials"));
            assertEquals(0, OutboxSql.count(c, "SELECT COUNT(*) FROM device_credential_events"));
            assertEquals("QUEUED", dump(c, "SELECT state FROM command_outbox WHERE command_id = '" + queued + "'"));
            assertEquals("PUBLISHED", dump(c, "SELECT state FROM command_outbox WHERE command_id = '" + published + "'"));
        }
    }
}
