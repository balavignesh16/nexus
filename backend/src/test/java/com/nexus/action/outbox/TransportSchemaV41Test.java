package com.nexus.action.outbox;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V41 (ledger P5-111, P5-113 to P5-117): transport assignment columns, per-device credentials and the append-only credential
 * events, on a freshly migrated disposable database. V1 to V40 keep exactly the checksums they had when V41 was added.
 */
@Testcontainers
class TransportSchemaV41Test {

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

    private static void exec(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    private static String refusal(Connection c, String sql) {
        SQLException e = assertThrows(SQLException.class, () -> exec(c, sql), sql);
        return e.getMessage();
    }

    private static final String HASH = "decode(repeat('ab', 32), 'hex')";

    private static String credential(UUID credential, UUID device, String lookup, String status) {
        String retire = status.equals("RETIRING") ? "NOW() + INTERVAL '1 day'" : "NULL";
        String revoked = status.equals("REVOKED") ? "NOW()" : "NULL";
        return "INSERT INTO device_ingestion_credentials(credential_id,device_id,lookup_id,secret_hash,hash_algorithm,status,created_at,retire_at,revoked_at) VALUES ('"
                + credential + "','" + device + "','" + lookup + "'," + HASH + ",'HMAC-SHA256','" + status + "',NOW()," + retire + "," + revoked + ")";
    }

    private static String event(UUID credential, String type) {
        return "INSERT INTO device_credential_events(event_id,credential_id,event_type,occurred_at,actor) VALUES ('" + UUID.randomUUID() + "','" + credential + "','" + type + "',NOW(),'cli:tester')";
    }

    static List<String> expectedChecksums() throws IOException {
        List<String> lines = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("src/test/resources/db/migration-checksums-v1-v40.txt"))) if (!line.isBlank()) lines.add(line.trim());
        return lines;
    }

    static List<String> actualChecksumsThroughV40(Connection c) throws SQLException {
        List<String> list = new ArrayList<>();
        try (var s = c.createStatement();
             var r = s.executeQuery("SELECT version || ':' || checksum FROM flyway_schema_history WHERE version ~ '^[0-9]+$' AND version::int <= 40 ORDER BY version::int")) {
            while (r.next()) list.add(r.getString(1));
        }
        return list;
    }

    @Test
    void p5_111_v41AppliesAfterV40OnAnEmptyDatabaseWithARecordedChecksum() throws Exception {
        try (Connection c = connect()) {
            assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '41' AND success AND checksum IS NOT NULL"));
            // Phase 6 added V42 after this test was written: what matters is that V41 is installed with a checksum and that nothing precedes it out of order.
            assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM flyway_schema_history WHERE success AND version = '41' AND installed_rank = (SELECT installed_rank FROM flyway_schema_history WHERE version = '40') + 1"));
            assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'devices' AND column_name = 'transport'"));
            assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'device_templates' AND column_name = 'default_transport'"));
        }
    }

    @Test
    void p5_117_v1ToV40KeepTheirChecksumsAndThePinnedListIsComplete() throws Exception {
        try (Connection c = connect()) {
            List<String> expected = expectedChecksums();
            assertEquals(40, expected.size());
            assertEquals(expected, actualChecksumsThroughV40(c), "an applied migration V1 to V40 changed: the pinned checksums are the ones of e29e215");
        }
    }

    @Test
    void p5_113_theTransportCheckAcceptsExactlyTheKnownValuesAndDefaultsToMqtt() throws Exception {
        try (Connection c = connect()) {
            UUID device = OutboxSql.device(c);
            assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM devices WHERE id = '" + device + "' AND transport = 'MQTT_JSON'"), "a new device defaults to MQTT_JSON");
            for (String ok : List.of("MQTT_JSON", "HTTP_WEBHOOK", "SIMULATOR")) exec(c, "UPDATE devices SET transport = '" + ok + "' WHERE id = '" + device + "'");
            for (String bad : List.of("mqtt_json", "HTTP", "BLE", "", "MQTT_JSON ")) {
                assertTrue(refusal(c, "UPDATE devices SET transport = '" + bad + "' WHERE id = '" + device + "'").contains("ck_devices_transport"), bad);
            }
            assertTrue(refusal(c, "UPDATE devices SET transport = NULL WHERE id = '" + device + "'").contains("null value"));
            assertTrue(refusal(c, "UPDATE device_templates SET default_transport = 'BLE'").contains("ck_templates_transport"));
            assertTrue(OutboxSql.count(c, "SELECT COUNT(*) FROM device_templates WHERE default_transport = 'MQTT_JSON'")
                    == OutboxSql.count(c, "SELECT COUNT(*) FROM device_templates"), "every template defaults to MQTT_JSON");
        }
    }

    @Test
    void p5_114_credentialConstraintsAreEnforcedByTheDatabase() throws Exception {
        try (Connection c = connect()) {
            UUID device = OutboxSql.device(c), other = OutboxSql.device(c);
            exec(c, credential(UUID.randomUUID(), device, "lookupAAAA1", "ACTIVE"));
            exec(c, credential(UUID.randomUUID(), device, "lookupAAAA2", "RETIRING"));
            exec(c, credential(UUID.randomUUID(), device, "lookupAAAA3", "REVOKED"));
            exec(c, credential(UUID.randomUUID(), device, "lookupAAAA4", "REVOKED"));
            assertTrue(refusal(c, credential(UUID.randomUUID(), device, "lookupAAAA5", "ACTIVE")).contains("uq_one_active_credential"), "a second ACTIVE credential");
            assertTrue(refusal(c, credential(UUID.randomUUID(), device, "lookupAAAA6", "RETIRING")).contains("uq_one_retiring_credential"), "a second RETIRING credential");
            assertTrue(refusal(c, credential(UUID.randomUUID(), other, "lookupAAAA1", "ACTIVE")).contains("lookup_id"), "a lookup id is globally unique");
            exec(c, credential(UUID.randomUUID(), other, "lookupBBBB1", "ACTIVE"));
            String base = "INSERT INTO device_ingestion_credentials(credential_id,device_id,lookup_id,secret_hash,hash_algorithm,status,created_at,retire_at,revoked_at) VALUES ('";
            UUID third = OutboxSql.device(c);
            assertTrue(refusal(c, base + UUID.randomUUID() + "','" + third + "','lookupCCCC1',decode('abcd','hex'),'HMAC-SHA256','ACTIVE',NOW(),NULL,NULL)").contains("ck_credential_hash_len"), "hash length");
            assertTrue(refusal(c, base + UUID.randomUUID() + "','" + third + "','lookupCCCC2'," + HASH + ",'HMAC-SHA256','PAUSED',NOW(),NULL,NULL)").contains("ck_credential_status"), "status");
            assertTrue(refusal(c, base + UUID.randomUUID() + "','" + third + "','lookupCCCC3'," + HASH + ",'HMAC-SHA256','RETIRING',NOW(),NULL,NULL)").contains("ck_credential_retire"), "RETIRING needs retire_at");
            assertTrue(refusal(c, base + UUID.randomUUID() + "','" + third + "','lookupCCCC4'," + HASH + ",'HMAC-SHA256','REVOKED',NOW(),NULL,NULL)").contains("ck_credential_revoke"), "REVOKED needs revoked_at");
            assertTrue(refusal(c, base + UUID.randomUUID() + "','" + third + "','lookupCCCC5'," + HASH + ",'HMAC-SHA256','ACTIVE',NOW(),NULL,NOW())").contains("ck_credential_revoke"), "revoked_at only when REVOKED");
            assertTrue(refusal(c, base + UUID.randomUUID() + "','" + third + "','short'," + HASH + ",'HMAC-SHA256','ACTIVE',NOW(),NULL,NULL)").contains("ck_credential_lookup_format"), "lookup id format");
            assertTrue(refusal(c, base + UUID.randomUUID() + "','" + third + "','lookupCCCC6'," + HASH + ",'hmac sha','ACTIVE',NOW(),NULL,NULL)").contains("ck_credential_algorithm"), "algorithm name");
            assertTrue(refusal(c, base + UUID.randomUUID() + "','" + UUID.randomUUID() + "','lookupCCCC7'," + HASH + ",'HMAC-SHA256','ACTIVE',NOW(),NULL,NULL)").contains("device_ingestion_credentials_device_id_fkey"), "a credential belongs to an existing device");
            exec(c, "UPDATE device_ingestion_credentials SET last_valid_contact_at = NOW() WHERE lookup_id = 'lookupAAAA1'");
        }
    }

    @Test
    void p5_115_credentialEventsAreAppendOnly() throws Exception {
        try (Connection c = connect()) {
            UUID device = OutboxSql.device(c), credential = UUID.randomUUID();
            exec(c, credential(credential, device, "lookupEEEE1", "ACTIVE"));
            exec(c, event(credential, "CREATED"));
            exec(c, event(credential, "ROTATED_IN"));
            assertTrue(refusal(c, "UPDATE device_credential_events SET actor = 'someone-else'").contains("append-only"));
            assertTrue(refusal(c, "DELETE FROM device_credential_events").contains("append-only"));
            assertTrue(refusal(c, event(credential, "TAMPERED")).contains("ck_credential_event_type"));
            assertTrue(refusal(c, "INSERT INTO device_credential_events(event_id,credential_id,event_type,occurred_at,actor) VALUES ('" + UUID.randomUUID() + "','" + UUID.randomUUID() + "','CREATED',NOW(),'x')").contains("credential_id_fkey"));
            assertEquals(2, OutboxSql.count(c, "SELECT COUNT(*) FROM device_credential_events WHERE credential_id = '" + credential + "'"));
        }
    }

    @Test
    void p5_116_deletingADeviceRemovesItsCredentialsAndEvents() throws Exception {
        try (Connection c = connect()) {
            UUID device = OutboxSql.device(c), keep = OutboxSql.device(c), credential = UUID.randomUUID(), kept = UUID.randomUUID();
            exec(c, credential(credential, device, "lookupFFFF1", "ACTIVE"));
            exec(c, event(credential, "CREATED"));
            exec(c, credential(kept, keep, "lookupFFFF2", "ACTIVE"));
            exec(c, event(kept, "CREATED"));
            exec(c, "DELETE FROM devices WHERE id = '" + device + "'");
            assertEquals(0, OutboxSql.count(c, "SELECT COUNT(*) FROM device_ingestion_credentials WHERE device_id = '" + device + "'"));
            assertEquals(0, OutboxSql.count(c, "SELECT COUNT(*) FROM device_credential_events WHERE credential_id = '" + credential + "'"));
            assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM device_credential_events WHERE credential_id = '" + kept + "'"), "other devices are untouched");
            assertFalse(refusal(c, "DELETE FROM device_credential_events").isEmpty(), "a direct delete is still refused while the parent exists");
        }
    }
}
