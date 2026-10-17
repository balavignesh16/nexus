package com.nexus.telemetry.inbox;

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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V40 inbound_message_dedupe on a disposable TimescaleDB/PostgreSQL (Flyway V1 to latest), created fresh. The key space is
 * per device and shared by MQTT and HTTP (source is a label, never a key), the constraints refuse malformed rows, device
 * removal takes the cache entries with it, and the original-record reference has no foreign key (R17, ledger P4-086).
 */
@Testcontainers
class InboundMessageDedupeSchemaTest {

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

    @Test
    void v40IsInstalledAsTheLatestMigrationOnAFreshDatabase() throws Exception {
        try (Connection c = connect()) {
            assertEquals("40", InboxSql.text(c, "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1"));
            assertEquals(1, InboxSql.count(c, "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'inbound_message_dedupe'"));
        }
    }

    @Test
    void theKeyIsPerDeviceAndSharedByMqttAndHttp() throws Exception {
        try (Connection c = connect()) {
            UUID a = InboxSql.device(c), b = InboxSql.device(c);
            assertEquals(1, InboxSql.dedupe(c, a, "msg-1", "MQTT", InboxSql.FP_A, null));
            SQLException sameDeviceOtherSource = assertThrows(SQLException.class, () -> InboxSql.dedupe(c, a, "msg-1", "HTTP", InboxSql.FP_A, null));
            assertEquals("23505", sameDeviceOtherSource.getSQLState(), "source must not split the key space (P4-078 shape)");
            assertEquals(1, InboxSql.dedupe(c, b, "msg-1", "HTTP", InboxSql.FP_A, null), "the same id on another device is independent");
            assertEquals(1, InboxSql.dedupe(c, a, "msg-2", "HTTP", InboxSql.FP_A, null), "a different id is independent");
        }
    }

    @Test
    void malformedRowsAreRefusedByTheDatabase() throws Exception {
        try (Connection c = connect()) {
            UUID device = InboxSql.device(c);
            for (String bad : new String[] {"", "has space", "slash/slash", "x".repeat(65), "naïve"}) {
                SQLException e = assertThrows(SQLException.class, () -> InboxSql.dedupe(c, device, bad, "MQTT", InboxSql.FP_A, null), "message id '" + bad + "'");
                assertTrue(e.getSQLState().equals("23514") || e.getSQLState().equals("22001"), "check or length violation, was " + e.getSQLState());
            }
            assertEquals("23514", assertThrows(SQLException.class, () -> InboxSql.dedupe(c, device, "ok", "COAP", InboxSql.FP_A, null)).getSQLState());
            assertEquals("23514", assertThrows(SQLException.class, () -> InboxSql.dedupe(c, device, "ok", "MQTT", "A".repeat(64), null)).getSQLState(), "upper-case hex");
            assertEquals("23514", assertThrows(SQLException.class, () -> InboxSql.dedupe(c, device, "ok", "MQTT", "a".repeat(63), null)).getSQLState(), "63 hex characters");
            assertEquals(1, InboxSql.dedupe(c, device, "A-z_0.9:Z", "MQTT", InboxSql.FP_A, null), "the whole allowed alphabet is accepted");
            assertEquals(1, InboxSql.dedupe(c, device, "x".repeat(64), "HTTP", InboxSql.FP_A, null), "64 characters is the limit");
        }
    }

    @Test
    void deletingTheDeviceRemovesItsEntriesAndNothingIsOrphaned() throws Exception {
        try (Connection c = connect()) {
            UUID gone = InboxSql.device(c), kept = InboxSql.device(c);
            InboxSql.dedupe(c, gone, "m1", "MQTT", InboxSql.FP_A, null);
            InboxSql.dedupe(c, gone, "m2", "HTTP", InboxSql.FP_B, null);
            InboxSql.dedupe(c, kept, "m1", "MQTT", InboxSql.FP_A, null);
            try (var s = c.createStatement()) {
                assertEquals(1, s.executeUpdate("DELETE FROM devices WHERE id = '" + gone + "'"), "a device with only a dedupe cache is deletable");
            }
            assertEquals(0, InboxSql.count(c, "SELECT COUNT(*) FROM inbound_message_dedupe WHERE device_id = '" + gone + "'"));
            assertEquals(1, InboxSql.count(c, "SELECT COUNT(*) FROM inbound_message_dedupe WHERE device_id = '" + kept + "'"));
            assertEquals(0, InboxSql.count(c, "SELECT COUNT(*) FROM inbound_message_dedupe d WHERE NOT EXISTS (SELECT 1 FROM devices x WHERE x.id = d.device_id)"));
            SQLException unknownDevice = assertThrows(SQLException.class, () -> InboxSql.dedupe(c, UUID.randomUUID(), "m1", "MQTT", InboxSql.FP_A, null));
            assertEquals("23503", unknownDevice.getSQLState(), "an entry cannot exist without its device");
        }
    }

    @Test
    void theOriginalRecordReferenceIsOptionalAndHasNoForeignKey() throws Exception {
        try (Connection c = connect()) {
            UUID device = InboxSql.device(c);
            assertEquals(1, InboxSql.dedupe(c, device, "no-ref", "MQTT", InboxSql.FP_A, null));
            assertEquals(1, InboxSql.dedupe(c, device, "stale-ref", "MQTT", InboxSql.FP_A, UUID.randomUUID()),
                    "the telemetry row may already be dropped by retention, so the column cannot reference the hypertable");
            assertEquals(1, InboxSql.count(c, "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_name = 'inbound_message_dedupe' AND constraint_type = 'FOREIGN KEY'"),
                    "only the device reference");
        }
    }

    @Test
    void receivedAtIsTheDatabaseClock() throws Exception {
        try (Connection c = connect()) {
            UUID device = InboxSql.device(c);
            InboxSql.dedupe(c, device, "clock", "MQTT", InboxSql.FP_A, null);
            long skewSeconds = InboxSql.count(c, "SELECT ABS(EXTRACT(EPOCH FROM (NOW() - received_at)))::bigint FROM inbound_message_dedupe WHERE device_id = '" + device + "' AND message_id = 'clock'");
            assertTrue(skewSeconds < 30, "received_at defaults to the database now(), was " + skewSeconds + " s away");
            assertEquals(1, InboxSql.count(c, "SELECT COUNT(*) FROM pg_indexes WHERE tablename = 'inbound_message_dedupe' AND indexname = 'idx_inbound_message_dedupe_received'"),
                    "the purge index exists");
        }
    }
}
