package com.nexus.rule.trigger;

import com.nexus.action.application.executor.CommandExecutor;
import com.nexus.rule.application.RuleLifecycleService;
import com.nexus.rule.domain.*;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.telemetry.application.TelemetryService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.time.Instant;
import java.util.*;

/**
 * Shared disposable PostgreSQL + real Mosquitto fixture for Phase 3 typed-trigger tests. The Clock is mocked and
 * advanced explicitly so temporal and deadline boundaries never depend on sleeping.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
public abstract class TypedTriggerSupport {
    @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));
    @Container static GenericContainer<?> broker = new GenericContainer<>("eclipse-mosquitto:2.0").withExposedPorts(1883)
        .withCopyFileToContainer(MountableFile.forClasspathResource("mosquitto-test/mosquitto.conf"), "/mosquitto/config/mosquitto.conf").waitingFor(Wait.forListeningPort());
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl); r.add("spring.datasource.username", db::getUsername); r.add("spring.datasource.password", db::getPassword);
        r.add("spring.flyway.url", db::getJdbcUrl); r.add("spring.flyway.user", db::getUsername); r.add("spring.flyway.password", db::getPassword);
        r.add("mqtt.broker.url", () -> "tcp://" + broker.getHost() + ":" + broker.getMappedPort(1883));
        r.add("mqtt.client.id", () -> "p32-" + UUID.randomUUID());
        r.add("nexus.rule.reconciliation-delay-ms", () -> "86400000");
        r.add("nexus.rule.ack-timeout-scan-ms", () -> "86400000");
        r.add("nexus.rule.timer-scan-ms", () -> "86400000");
        r.add("nexus.rule.legacy-plan-recovery-scan-ms", () -> "86400000");
        r.add("nexus.twin.verification-scan-ms", () -> "86400000");
    }

    @Autowired protected JdbcTemplate sql;
    @Autowired protected RuleLifecycleService lifecycle;
    @Autowired protected TelemetryService telemetry;
    @Autowired protected RuleTriggerRouter router;
    @Autowired protected com.nexus.device.connectivity.DeviceConnectivityRegistry connectivity;
    @org.springframework.boot.test.mock.mockito.MockBean protected java.time.Clock clock;
    @org.springframework.boot.test.mock.mockito.SpyBean protected CommandExecutor executor;
    @org.springframework.boot.test.mock.mockito.SpyBean protected com.nexus.action.application.executor.CreateAlertExecutor alerts;
    @org.springframework.boot.test.mock.mockito.SpyBean protected com.nexus.rule.plan.RuleActionPlanStore planStore;

    protected volatile Instant now = Instant.now();
    @BeforeEach void freezeClock() {
        // Whole microseconds: PostgreSQL stores timestamptz at microsecond precision, so boundary tests must not depend on sub-microsecond rounding.
        now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        // Earlier tests in this class share the database; their still-pending deadlines must not be consumed by this test's workers.
        if (isolatePendingTimers()) {
            // Earlier tests share this database. Their rules must neither create facts (facts are recorded only for active
            // subscriptions) nor run timers in this test.
            sql.update("UPDATE rules SET lifecycle='PAUSED', enabled=false WHERE lifecycle='ACTIVE'");
        }
        if (isolatePendingTimers()) sql.update("UPDATE rule_timers SET status='INVALIDATED', invalidation_reason='TEST_ISOLATION' WHERE status='PENDING'");
        org.mockito.Mockito.when(clock.instant()).thenAnswer(inv -> now);
        org.mockito.Mockito.when(clock.getZone()).thenReturn(java.time.ZoneOffset.UTC);
    }

    /** Restart tests keep deadlines across context recreation and override this. */
    protected boolean isolatePendingTimers() { return true; }

    /** Device with a READ TEMPERATURE (CELSIUS) and a WRITE LED legacy capability, ONLINE. */
    protected UUID device() {
        UUID site = UUID.randomUUID(), building = UUID.randomUUID(), space = UUID.randomUUID(), device = UUID.randomUUID();
        sql.update("INSERT INTO sites(id,name) VALUES(?,'P32')", site);
        sql.update("INSERT INTO buildings(id,site_id,name) VALUES(?,?,'P32')", building, site);
        sql.update("INSERT INTO spaces(id,building_id,name,created_at,updated_at) VALUES(?,?,'P32',now(),now())", space, building);
        sql.update("INSERT INTO devices(id,space_id,name,device_type,manufacturer,model,serial_number,status,mode,created_at,updated_at,created_by,updated_by) VALUES(?,?,'P32','CUSTOM','NEXUS','TEST',?,'ACTIVE','SIMULATED',now(),now(),'test','test')", device, space, device.toString());
        sql.update("INSERT INTO device_capabilities(id,device_id,capability_type,direction,unit,metadata) VALUES(?,?,'TEMPERATURE','READ','CELSIUS','{}')", UUID.randomUUID(), device);
        sql.update("INSERT INTO device_capabilities(id,device_id,capability_type,direction,unit,metadata) VALUES(?,?,'LED','WRITE','BOOLEAN','{}')", UUID.randomUUID(), device);
        connectivity.applyObservedStatus(device, com.nexus.device.connectivity.DeviceConnectivityStatus.ONLINE);
        return device;
    }

    protected static RuleCondition temperatureAbove(String threshold) {
        return new RuleCondition(RuleField.VALUE, RuleOperator.GT, threshold, com.nexus.device.capability.CapabilityType.TEMPERATURE);
    }

    protected static RuleAction alert() { return new RuleAction("CREATE_ALERT", Map.of("severity", "INFO")); }

    protected static RuleAction ledCommand(UUID device, boolean on) {
        return new RuleAction("SEND_COMMAND", Map.of("deviceId", device.toString(), "capability", "LED", "command", "SET_STATE", "value", on));
    }

    /** Active rule scoped to the device with explicit subscriptions. */
    protected UUID typedRule(UUID device, String name, int priority, List<TriggerSubscription> triggers, List<RuleCondition> extra, List<RuleAction> actions) {
        return typedRule(device, name, priority, triggers, extra, actions, null, null);
    }

    protected UUID typedRule(UUID device, String name, int priority, List<TriggerSubscription> triggers, List<RuleCondition> extra, List<RuleAction> actions,
            RepeatPolicy repeat, ScheduleSpec schedule) {
        UUID id = UUID.randomUUID();
        var conditions = new ArrayList<RuleCondition>();
        conditions.add(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()));
        conditions.addAll(extra);
        lifecycle.legacySave(new Rule(id, name, true, priority, conditions, actions, Map.of(), null, null,
            new RuleRuntime(triggers, repeat, schedule)), null);
        return id;
    }

    /** Active rule with a single temporal condition (TEMPORAL_DUE is the only legal subscription for such a rule). */
    protected UUID temporalRule(UUID device, String name, TemporalSpec spec, boolean capability, RuleOperator operator, String expected, List<RuleAction> actions) {
        UUID id = UUID.randomUUID();
        var condition = capability
            ? new RuleCondition(RuleField.VALUE, operator, expected, com.nexus.device.capability.CapabilityType.TEMPERATURE, null, null, "c1", spec)
            : new RuleCondition(RuleField.VALUE, RuleOperator.EQ, "OFFLINE", null, null, null, "c1", spec);
        lifecycle.legacySave(new Rule(id, name, true, 1, List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()), condition), actions, Map.of(), null, null,
            new RuleRuntime(List.of(new TriggerSubscription(TriggerType.TEMPORAL_DUE)), null, null)), null);
        return id;
    }

    protected String phase(UUID rule) { return sql.queryForObject("SELECT phase FROM rule_temporal_state WHERE rule_id=? ORDER BY updated_at DESC LIMIT 1", String.class, rule); }
    protected int pendingTimers(UUID rule) { return count("SELECT count(*) FROM rule_timers WHERE rule_id=? AND status='PENDING'", rule); }
    protected int dueFacts(UUID rule) { return count("SELECT count(*) FROM rule_trigger_facts WHERE trigger_type='TEMPORAL_DUE' AND attributes->>'ruleId'=?", rule.toString()); }
    protected int temporalEvents(UUID rule, String event) { return count("SELECT count(*) FROM rule_temporal_evidence WHERE rule_id=? AND event=?", rule, event); }

    protected Instant ingest(UUID device, double value, Instant deviceTime) {
        telemetry.processTelemetry(new TelemetryRequest(device, deviceTime, "TEMPERATURE", value, "CELSIUS"));
        return deviceTime;
    }

    protected int count(String sqlText, Object... args) { return sql.queryForObject(sqlText, Integer.class, args); }
    protected int facts(String type, UUID device) { return count("SELECT count(*) FROM rule_trigger_facts WHERE trigger_type=? AND device_id=?", type, device); }
    protected int matches(UUID rule) { return count("SELECT count(*) FROM rule_matches WHERE rule_id=?", rule); }
}
