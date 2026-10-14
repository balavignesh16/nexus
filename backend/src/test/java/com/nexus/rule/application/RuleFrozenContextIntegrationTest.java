package com.nexus.rule.application;

import com.nexus.device.capability.definition.CapabilityValueType;
import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.EventType;
import com.nexus.rule.domain.*;
import com.nexus.rule.snapshot.RuleEvaluationContext;
import com.nexus.twin.domain.DigitalTwin;
import com.nexus.twin.domain.DigitalTwinRepository;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Ledger 18/20/61 (snapshot part): one REPEATABLE READ context, never a READ COMMITTED mixture. */
@SpringBootTest
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class RuleFrozenContextIntegrationTest {
    @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl); r.add("spring.datasource.username", db::getUsername); r.add("spring.datasource.password", db::getPassword);
        r.add("spring.flyway.url", db::getJdbcUrl); r.add("spring.flyway.user", db::getUsername); r.add("spring.flyway.password", db::getPassword);
        r.add("nexus.rule.reconciliation-delay-ms", () -> "86400000");
    }
    @Autowired RuleEvaluationSnapshotService snapshots;
    @Autowired RuleEvaluator evaluator;
    @Autowired RuleMatcher matcher;
    @Autowired RuleLifecycleService lifecycle;
    @Autowired DigitalTwinRepository twins;
    @Autowired JdbcTemplate sql;
    @Autowired PlatformTransactionManager transactions;
    @org.springframework.boot.test.mock.mockito.MockBean java.time.Clock clock;
    final Instant now = Instant.parse("2026-10-13T12:00:00Z");
    @org.junit.jupiter.api.BeforeEach void time() { org.mockito.Mockito.when(clock.instant()).thenReturn(now); }

    record Fixture(UUID device, UUID rule) {}

    private Fixture fixture(double value) {
        UUID site = UUID.randomUUID(), building = UUID.randomUUID(), space = UUID.randomUUID(), device = UUID.randomUUID(), rule = UUID.randomUUID();
        sql.update("INSERT INTO sites(id,name) VALUES(?,'S2')", site);
        sql.update("INSERT INTO buildings(id,site_id,name) VALUES(?,?,'S2')", building, site);
        sql.update("INSERT INTO spaces(id,building_id,name,created_at,updated_at) VALUES(?,?,'S2',now(),now())", space, building);
        sql.update("INSERT INTO devices(id,space_id,name,device_type,manufacturer,model,serial_number,status,mode,created_at,updated_at,created_by,updated_by) VALUES(?,?,'S2','CUSTOM','NEXUS','TEST',?,'ACTIVE','SIMULATED',now(),now(),'test','test')", device, space, device.toString());
        sql.update("INSERT INTO device_capabilities(id,device_id,capability_type,direction,unit,metadata) VALUES(?,?,'TEMPERATURE','READ','CELSIUS','{}')", UUID.randomUUID(), device);
        sql.update("INSERT INTO device_connectivity(device_id,status,last_changed_at) VALUES(?,'ONLINE',?)", device, java.sql.Timestamp.from(now.minusSeconds(60)));
        twins.save(new DigitalTwin(device, now.minusSeconds(5), "TEMPERATURE", "TEMPERATURE", value, "CELSIUS", now, now.minusSeconds(5), Map.of(), null, null, null, CapabilityValueType.NUMBER, null));
        var definition = new Rule(rule, "S2 rule", true, 1, List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()),
            new RuleCondition(RuleField.VALUE, RuleOperator.GT, "10", com.nexus.device.capability.CapabilityType.TEMPERATURE)),
            List.of(new RuleAction("CREATE_ALERT", Map.of("severity", "INFO"))), Map.of());
        lifecycle.legacySave(definition, null);
        return new Fixture(device, rule);
    }

    private DomainEvent trigger(UUID device) {
        return new DomainEvent(UUID.randomUUID(), EventType.TWIN_UPDATED, device, now, "s2", Map.of("sensorType", "TEMPERATURE"), "INFO", "s2-" + UUID.randomUUID());
    }

    /** Commits from a separate connection, as concurrent telemetry/status/contract/lifecycle writers would. */
    private void concurrentCommit(UUID device, UUID rule) {
        try (Connection c = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword())) {
            c.setAutoCommit(true);
            try (var st = c.createStatement()) {
                st.executeUpdate("UPDATE digital_twins SET latest_value=1, numeric_value=1 WHERE device_id='" + device + "'");
                st.executeUpdate("UPDATE device_connectivity SET status='OFFLINE', last_changed_at=now() WHERE device_id='" + device + "'");
                st.executeUpdate("UPDATE device_capabilities SET unit='FAHRENHEIT' WHERE device_id='" + device + "'");
                st.executeUpdate("UPDATE rules SET execution_epoch=execution_epoch+1 WHERE rule_id='" + rule + "'");
            }
        } catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
    }

    @Test void snapshotServiceIsReachedThroughARealProxyAndRunsRepeatableRead() {
        assertTrue(AopUtils.isAopProxy(snapshots), "internal @Transactional calls would be ineffective");
        var f = fixture(25);
        var snapshot = snapshots.acquire(com.nexus.rule.trigger.TriggerObservation.legacy(trigger(f.device())), now);
        assertEquals("repeatable read", snapshot.isolation());
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(), "snapshot transaction has ended");
    }

    @Test void concurrentCommitsBetweenReadPhasesNeverMixIntoTheContext() {
        var f = fixture(25);
        long epochBefore = sql.queryForObject("SELECT execution_epoch FROM rules WHERE rule_id=?", Long.class, f.rule());
        snapshots.setReadProbe(phase -> { if (phase.equals("VERSIONS")) concurrentCommit(f.device(), f.rule()); });
        com.nexus.rule.snapshot.RuleEvaluationSnapshot snapshot;
        try { snapshot = snapshots.acquire(com.nexus.rule.trigger.TriggerObservation.legacy(trigger(f.device())), now); } finally { snapshots.setReadProbe(null); }

        // Every input predates the concurrent commit even though twin/connectivity/contract were read after it.
        assertEquals(25.0, snapshot.twins().get(f.device()).getFirst().latestValue());
        assertEquals("ONLINE", snapshot.connectivity().get(f.device()).status());
        var captured = snapshot.rules().stream().filter(r -> r.rule().ruleId().equals(f.rule())).findFirst().orElseThrow();
        assertEquals(epochBefore, captured.rule().executionIdentity().executionEpoch());
        assertEquals("CELSIUS", snapshot.capabilities().values().iterator().next().effectiveUnit());
        assertTrue(captured.executable(), () -> "diagnostics=" + captured.diagnostics());

        // Evaluation consumes only the captured inputs and reports GOOD despite live OFFLINE/1.0/FAHRENHEIT.
        var result = evaluator.evaluate(captured.rule(), trigger(f.device()),
            new RuleEvaluationContext(UUID.randomUUID(), snapshot, captured, com.nexus.rule.trigger.TriggerObservation.legacy(trigger(f.device()))));
        assertTrue(result.matched(), result.message());
        assertEquals("GOOD", result.evidence().getLast().stateQuality());
        assertEquals(25.0, result.evidence().getLast().actualValue());
        assertEquals("OFFLINE", sql.queryForObject("SELECT status FROM device_connectivity WHERE device_id=?", String.class, f.device()));
        assertEquals(snapshot.evaluationTime(), result.evidence().getLast().qualityEvaluatedAt());
    }

    @Test void readCommittedControlShowsTheMixtureTheSnapshotPrevents() {
        var f = fixture(25);
        var rc = new TransactionTemplate(transactions);
        rc.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        rc.setReadOnly(true);
        rc.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var mixed = rc.execute(status -> {
            String connectivityBefore = sql.queryForObject("SELECT status FROM device_connectivity WHERE device_id=?", String.class, f.device());
            concurrentCommit(f.device(), f.rule());
            Double twinAfter = sql.queryForObject("SELECT latest_value FROM digital_twins WHERE device_id=?", Double.class, f.device());
            return connectivityBefore + "/" + twinAfter;
        });
        assertEquals("ONLINE/1.0", mixed, "control: READ COMMITTED yields old connectivity with new twin");
    }

    @Test void falseZeroAndEmptyStringSurviveFreezing() {
        var nested = new LinkedHashMap<String, Object>();
        nested.put("flag", false); nested.put("zero", 0); nested.put("empty", ""); nested.put("list", new ArrayList<>(List.of(false, 0, "")));
        @SuppressWarnings("unchecked")
        var frozen = (Map<String, Object>) com.nexus.rule.snapshot.FrozenValues.freeze(nested);
        nested.put("flag", true); ((List<Object>) nested.get("list")).clear();
        assertEquals(false, frozen.get("flag")); assertEquals(0, frozen.get("zero")); assertEquals("", frozen.get("empty"));
        assertEquals(List.of(false, 0, ""), frozen.get("list"));
        assertThrows(UnsupportedOperationException.class, () -> frozen.put("x", 1));
    }

    @Test void capturedTwinsAreDetachedDeepCopiesOfNestedMetadataAndObjectValues() {
        var f = fixture(25);
        var inner = new ArrayList<Object>(List.of(false, 0, ""));
        var source = new DigitalTwin(f.device(), now.minusSeconds(4), "SHAPE", "SHAPE", new LinkedHashMap<>(Map.of("list", inner)), null, now, now.minusSeconds(4),
            new LinkedHashMap<>(Map.of("nested", new LinkedHashMap<>(Map.of("k", "v")))), null, null, null, CapabilityValueType.OBJECT, null);
        var copy = source.detachedCopy(com.nexus.rule.snapshot.FrozenValues::freeze);
        // Later mutation of the live object does not reach the captured copy.
        inner.clear(); source.metadata().clear();
        assertEquals(List.of(false, 0, ""), ((Map<?, ?>) copy.typedValue()).get("list"));
        assertEquals(Map.of("nested", Map.of("k", "v")), copy.metadata());
        // The captured copy cannot be mutated by a consumer either.
        assertThrows(UnsupportedOperationException.class, () -> copy.metadata().put("x", 1));
        assertThrows(UnsupportedOperationException.class, () -> ((Map<String, Object>) copy.typedValue()).put("x", 1));
        assertThrows(UnsupportedOperationException.class, () -> ((List<Object>) ((Map<?, ?>) copy.typedValue()).get("list")).add(1));
        // A snapshot twin is never the instance the repository returned.
        var snapshot = snapshots.acquire(com.nexus.rule.trigger.TriggerObservation.legacy(trigger(f.device())), now);
        var live = twins.findByDeviceId(f.device()).getFirst();
        assertNotSame(live, snapshot.twins().get(f.device()).getFirst());
    }

    @Test void matcherPersistsFrozenContextEvidenceWithoutOutstandingTransaction() {
        var f = fixture(25);
        matcher.onEvent(trigger(f.device()));
        var rows = sql.queryForList("SELECT evaluation_evidence::text AS e FROM rule_matches WHERE rule_id=?", f.rule());
        assertEquals(1, rows.size());
        String evidence = rows.getFirst().get("e").toString();
        assertTrue(evidence.contains("\"frozenContext\""), evidence);
        assertTrue(evidence.contains("repeatable read"), evidence);
        assertTrue(evidence.contains("\"status\": \"ONLINE\""), evidence);
    }

    @Test void snapshotFailureFailsClosedWithoutMatches() {
        var f = fixture(25);
        snapshots.setReadProbe(phase -> { throw new IllegalStateException("injected read failure"); });
        try { assertThrows(IllegalStateException.class, () -> matcher.onEvent(trigger(f.device()))); } finally { snapshots.setReadProbe(null); }
        assertEquals(0, sql.queryForObject("SELECT count(*) FROM rule_matches WHERE rule_id=?", Integer.class, f.rule()));
    }
}
