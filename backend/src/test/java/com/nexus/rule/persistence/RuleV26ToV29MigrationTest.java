package com.nexus.rule.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** V26-V31 on a populated V24/V25 database: forward-only, historical attribution untouched, constraints enforce the contracts. */
@Testcontainers
class RuleV26ToV29MigrationTest {
    @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));

    private Connection connect() throws SQLException { return DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword()); }
    private List<String> checksums(Statement sql) throws SQLException {
        var rows = sql.executeQuery("SELECT version||':'||checksum FROM flyway_schema_history WHERE version::int <= 25 ORDER BY installed_rank");
        var list = new ArrayList<String>(); while (rows.next()) list.add(rows.getString(1)); rows.close(); return list;
    }

    @Test void populatedMigrationPreservesHistoryAndEnforcesNewContracts() throws Exception {
        Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()).target("24").load().migrate();
        UUID site = UUID.randomUUID(), building = UUID.randomUUID(), space = UUID.randomUUID(), device = UUID.randomUUID(), rule = UUID.randomUUID(), event = UUID.randomUUID(), legacyMatch = UUID.randomUUID();
        List<String> before;
        try (var c = connect(); var sql = c.createStatement()) {
            sql.executeUpdate("INSERT INTO sites(id,name) VALUES('" + site + "','M')");
            sql.executeUpdate("INSERT INTO buildings(id,site_id,name) VALUES('" + building + "','" + site + "','M')");
            sql.executeUpdate("INSERT INTO spaces(id,building_id,name,created_at,updated_at) VALUES('" + space + "','" + building + "','M',now(),now())");
            sql.executeUpdate("INSERT INTO devices(id,space_id,name,device_type,manufacturer,model,serial_number,status,mode,created_at,updated_at,created_by,updated_by) VALUES('" + device + "','" + space + "','M','CUSTOM','N','T','" + device + "','ACTIVE','SIMULATED',now(),now(),'t','t')");
            sql.executeUpdate("INSERT INTO device_connectivity(device_id,status,last_changed_at) VALUES('" + device + "','ONLINE',now())");
            sql.executeUpdate("INSERT INTO rules(rule_id,name,enabled,priority,conditions,actions) VALUES('" + rule + "','Legacy',true,1,'[{\"field\":\"EVENT_TYPE\",\"operator\":\"EQ\",\"expectedValue\":\"TWIN_UPDATED\"}]','[{\"actionType\":\"CREATE_ALERT\",\"parameters\":{}}]')");
            sql.executeUpdate("INSERT INTO rule_matches(match_id,rule_id,rule_snapshot,event_id,evaluation_evidence,matched_at) VALUES('" + legacyMatch + "','" + rule + "','{}','" + event + "','{\"outcome\":\"matched\"}',now())");
        }
        Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()).target("25").load().migrate();
        try (var c = connect(); var sql = c.createStatement()) { before = checksums(sql); }
        var upToV29 = Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()).target("29").load().migrate();
        assertEquals(4, upToV29.migrations.size(), "V26, V27, V28 and V29");
        UUID oldFact = UUID.randomUUID(), oldTimerRule = rule;
        try (var c = connect(); var sql = c.createStatement()) {
            // A fact that already exists when V30 arrives must be marked ROUTED so an upgrade never replays history.
            sql.executeUpdate("INSERT INTO rule_trigger_facts(fact_id,trigger_type,source_key,device_id,occurred_at,recorded_at) VALUES('" + oldFact + "','ACCEPTED_TELEMETRY','pre-v30','" + device + "',now(),now())");
        }
        var toV30 = Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()).target("30").load().migrate();
        assertEquals(1, toV30.migrations.size(), "V30 only");
        UUID pendingBeforeV31 = UUID.randomUUID();
        try (var c = connect(); var sql = c.createStatement()) {
            // An unrouted fact recorded before V31 knows nothing about rule authority.
            sql.executeUpdate("INSERT INTO rule_trigger_facts(fact_id,trigger_type,source_key,device_id,occurred_at,recorded_at) VALUES('" + pendingBeforeV31 + "','ACCEPTED_TELEMETRY','pre-v31','" + device + "',now(),now())");
        }
        var flyway = Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()).load();
        var result = flyway.migrate();
        flyway.validate();
        assertEquals(1, result.migrations.size(), "V31 only");
        try (var c = connect(); var sql = c.createStatement()) {
            var upgraded = sql.executeQuery("SELECT routing_status FROM rule_trigger_facts WHERE fact_id='" + oldFact + "'");
            assertTrue(upgraded.next()); assertEquals("ROUTED", upgraded.getString(1), "pre-existing facts are not replayed");
            assertEquals(before, checksums(sql), "V1-V25 checksums are unchanged");
            var pre31 = sql.executeQuery("SELECT routing_status,rule_authority FROM rule_trigger_facts WHERE fact_id='" + pendingBeforeV31 + "'");
            assertTrue(pre31.next()); assertEquals("PENDING", pre31.getString(1), "V31 does not change a fact's disposition"); assertNull(pre31.getString(2), "rule authority is unknown for a fact recorded earlier");
            sql.executeUpdate("UPDATE rule_trigger_facts SET routing_status='EXPIRED' WHERE fact_id='" + pendingBeforeV31 + "'");
            var legacy = sql.executeQuery("SELECT event_id,trigger_type,trigger_fact_id FROM rule_matches WHERE match_id='" + legacyMatch + "'");
            assertTrue(legacy.next());
            assertEquals(event, legacy.getObject(1, UUID.class));
            assertNull(legacy.getString(2)); assertNull(legacy.getObject(3));
            var link = sql.executeQuery("SELECT status,generation FROM device_connectivity WHERE device_id='" + device + "'");
            assertTrue(link.next()); assertEquals("ONLINE", link.getString(1)); assertEquals(0, link.getLong(2), "existing rows start at generation 0");

            UUID fact = UUID.randomUUID();
            sql.executeUpdate("INSERT INTO rule_trigger_facts(fact_id,trigger_type,source_key,device_id,occurred_at,recorded_at) VALUES('" + fact + "','ACCEPTED_TELEMETRY','k1','" + device + "',now(),now())");
            var disposition = sql.executeQuery("SELECT routing_status,routing_attempts FROM rule_trigger_facts WHERE fact_id='" + fact + "'");
            assertTrue(disposition.next()); assertEquals("PENDING", disposition.getString(1), "a new fact starts PENDING"); assertEquals(0, disposition.getInt(2));
            assertThrows(SQLException.class, () -> sql.executeUpdate("UPDATE rule_trigger_facts SET routing_status='DONE' WHERE fact_id='" + fact + "'"));
            sql.executeUpdate("UPDATE rule_trigger_facts SET routing_status='QUARANTINED' WHERE fact_id='" + fact + "'");
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO rule_trigger_facts(fact_id,trigger_type,source_key,occurred_at,recorded_at) VALUES(gen_random_uuid(),'ACCEPTED_TELEMETRY','k1',now(),now())"), "source identity is unique per trigger type");
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO rule_trigger_facts(fact_id,trigger_type,source_key,occurred_at,recorded_at) VALUES(gen_random_uuid(),'LEGACY_DOMAIN_EVENT','k2',now(),now())"), "legacy events are DomainEvents, never typed facts");
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO rule_trigger_facts(fact_id,trigger_type,source_key,occurred_at,recorded_at,chain_depth) VALUES(gen_random_uuid(),'MANUAL','k3',now(),now(),9)"), "chain depth is capped at 8");
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO rule_trigger_facts(fact_id,trigger_type,source_key,occurred_at,recorded_at) VALUES(gen_random_uuid(),'RULE_MATCHED','k4',now(),now())"), "a chained fact must name its persisted parent match");
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO rule_matches(match_id,rule_id,rule_snapshot,evaluation_evidence,matched_at) VALUES(gen_random_uuid(),'" + rule + "','{}','{}',now())"), "a match needs exactly one source");
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO rule_matches(match_id,rule_id,rule_snapshot,event_id,trigger_type,trigger_fact_id,evaluation_evidence,matched_at) VALUES(gen_random_uuid(),'" + rule + "','{}','" + event + "','MANUAL','" + fact + "','{}',now())"), "never both a DomainEvent and a typed fact");
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO rule_matches(match_id,rule_id,rule_snapshot,trigger_type,trigger_fact_id,evaluation_evidence,matched_at) VALUES(gen_random_uuid(),'" + rule + "','{}','LEGACY_DOMAIN_EVENT','" + fact + "','{}',now())"), "a typed match cannot claim the legacy source");
            sql.executeUpdate("INSERT INTO rule_matches(match_id,rule_id,rule_snapshot,trigger_type,trigger_fact_id,evaluation_evidence,matched_at) VALUES(gen_random_uuid(),'" + rule + "','{}','ACCEPTED_TELEMETRY','" + fact + "','{}',now())");

            // Version-bound runtime tables require an immutable version row.
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO rule_timers(timer_id,kind,rule_id,rule_version_id,rule_revision,scope_device_id,generation,due_at,created_at,updated_at) VALUES(gen_random_uuid(),'SCHEDULE','" + rule + "',gen_random_uuid(),1,'" + device + "',0,now(),now(),now())"));
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO rule_temporal_state(rule_id,rule_version_id,rule_revision,condition_id,scope_device_id,generation,operator,phase,updated_at) VALUES('" + rule + "',gen_random_uuid(),1,'c','" + device + "',0,'FOR_DURATION','IDLE',now())"));
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO rule_action_plans(plan_id,fact_id,decided_at,status) VALUES(gen_random_uuid(),gen_random_uuid(),now(),'APPROVED')"), "a plan needs a committed fact");
            sql.executeUpdate("INSERT INTO rule_action_plans(plan_id,fact_id,decided_at,status) VALUES(gen_random_uuid(),'" + fact + "',now(),'APPROVED')");
            assertThrows(SQLException.class, () -> sql.executeUpdate("INSERT INTO rule_action_plans(plan_id,fact_id,decided_at,status) VALUES(gen_random_uuid(),'" + fact + "',now(),'APPROVED')"), "one plan per fact");
        }
    }
}
