package com.nexus.rule.application;

import com.nexus.action.outbox.BrokerCommandPublisher;
import com.nexus.action.outbox.FakeBroker;
import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.EventType;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleAction;
import com.nexus.rule.domain.RuleCondition;
import com.nexus.rule.domain.RuleField;
import com.nexus.rule.domain.RuleOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rule-originated commands with the outbox ON (ledger P4-011): the Phase 3 gates run exactly as before and BEFORE any outbox
 * row exists, the accepted command is delivered by the dispatcher (never published inline), and a command durably accepted
 * before its rule is paused still finishes delivery (contract R08.3: delivery never reauthorises).
 */
@SpringBootTest(properties = {"nexus.command.outbox.dispatcher-enabled=true", "nexus.command.outbox.enabled=true"})
@Testcontainers
@Import(RuleOriginOutboxIntegrationTest.Scripted.class)
class RuleOriginOutboxIntegrationTest {

    @TestConfiguration
    static class Scripted {
        @Bean
        @Primary
        FakeBroker broker() { return new FakeBroker(); }
    }

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));
    @Container
    static GenericContainer<?> mosquitto = new GenericContainer<>("eclipse-mosquitto:2.0")
            .withExposedPorts(1883)
            .withCopyFileToContainer(MountableFile.forClasspathResource("mosquitto-test/mosquitto.conf"), "/mosquitto/config/mosquitto.conf")
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", db::getJdbcUrl);
        r.add("spring.datasource.username", db::getUsername);
        r.add("spring.datasource.password", db::getPassword);
        r.add("spring.flyway.url", db::getJdbcUrl);
        r.add("spring.flyway.user", db::getUsername);
        r.add("spring.flyway.password", db::getPassword);
        r.add("mqtt.broker.url", () -> "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883));
        r.add("mqtt.client.id", () -> "outbox-rule-origin-" + UUID.randomUUID());
    }

    @Autowired JdbcTemplate sql;
    @Autowired RuleLifecycleService lifecycle;
    @Autowired RuleMatcher matcher;
    @Autowired FakeBroker broker;
    @Autowired com.nexus.device.connectivity.DeviceConnectivityRegistry connectivity;

    private record Fixture(UUID device, UUID ruleId) {}

    private Fixture fixture() {
        UUID site = UUID.randomUUID(), building = UUID.randomUUID(), space = UUID.randomUUID(), device = UUID.randomUUID();
        sql.update("INSERT INTO sites(id,name) VALUES(?,'RO')", site);
        sql.update("INSERT INTO buildings(id,site_id,name) VALUES(?,?,'RO')", building, site);
        sql.update("INSERT INTO spaces(id,building_id,name,created_at,updated_at) VALUES(?,?,'RO',now(),now())", space, building);
        sql.update("INSERT INTO devices(id,space_id,name,device_type,manufacturer,model,serial_number,status,mode,created_at,updated_at,created_by,updated_by)"
                + " VALUES(?,?,'RO','CUSTOM','NEXUS','TEST',?,'ACTIVE','SIMULATED',now(),now(),'test','test')", device, space, "SN-" + device);
        connectivity.applyObservedStatus(device, com.nexus.device.connectivity.DeviceConnectivityStatus.ONLINE);
        sql.update("INSERT INTO device_capabilities(id,device_id,capability_type,direction,unit,metadata) VALUES(?,?,'LED','WRITE','BOOLEAN','{}')", UUID.randomUUID(), device);
        UUID ruleId = UUID.randomUUID();
        lifecycle.legacySave(new Rule(ruleId, "Outbox rule", true, 1,
                List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString())),
                List.of(new RuleAction("SEND_COMMAND", Map.of("deviceId", device.toString(), "capability", "LED", "command", "SET_STATE", "value", true))),
                Map.of()), null);
        return new Fixture(device, ruleId);
    }

    private void fire(UUID device) {
        matcher.onEvent(new DomainEvent(UUID.randomUUID(), EventType.TWIN_UPDATED, device, Instant.now(), "outbox-rule", Map.of("value", 1), "INFO", "outbox-rule"));
    }

    private int count(String table, UUID device) {
        return sql.queryForObject("SELECT count(*) FROM " + table + " WHERE device_id=?", Integer.class, device);
    }

    private static void await(BooleanSupplier c, String what) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (!c.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(25);
        }
    }

    @Test
    @Timeout(90)
    void anActiveRuleMatchIsAcceptedThroughTheOutboxAndDeliveredByTheDispatcher() throws Exception {
        broker.reset();
        Fixture f = fixture();
        fire(f.device());
        await(() -> sql.queryForObject("SELECT count(*) FROM command_outbox WHERE device_id=? AND state='PUBLISHED'", Integer.class, f.device()) == 1, "delivery");

        UUID command = sql.queryForObject("SELECT request_id FROM action_history WHERE device_id=?", UUID.class, f.device());
        assertNotNull(sql.queryForObject("SELECT match_id FROM action_history WHERE request_id=?", UUID.class, command), "a rule-originated command keeps its match");
        assertEquals("RULE_ACTION", sql.queryForObject("SELECT source FROM current_desired_twin_state WHERE device_id=?", String.class, f.device()));
        assertEquals(1, count("current_twin_verification_state", f.device()));
        assertEquals("SUCCESS", sql.queryForObject("SELECT status FROM action_history WHERE request_id=?", String.class, command));
        assertEquals(1, broker.calls.size(), "one publication, by the dispatcher");
        assertTrue(broker.calls.get(0).text().contains(command.toString()));
        assertEquals(List.of("ACCEPTED", "QUEUED", "ATTEMPT_STARTED", "PUBLISHED"),
                sql.queryForList("SELECT fact_type FROM command_state_history WHERE command_id=? ORDER BY sequence", String.class, command));
    }

    @Test
    @Timeout(90)
    void aPausedRuleStillCreatesNeitherAnAcceptedCommandNorAnOutboxRow() throws Exception {
        broker.reset();
        Fixture f = fixture();
        lifecycle.transition(f.ruleId(), "pause", null);
        fire(f.device());
        Thread.sleep(1500);
        assertEquals(0, count("action_history", f.device()));
        assertEquals(0, count("command_outbox", f.device()));
        assertEquals(0, count("current_desired_twin_state", f.device()));
        assertEquals(0, count("current_twin_verification_state", f.device()));
        assertTrue(broker.calls.isEmpty());
    }

    @Test
    @Timeout(90)
    void aCommandAcceptedBeforeItsRuleWasPausedStillFinishesDelivery() throws Exception {
        broker.reset();
        CountDownLatch release = new CountDownLatch(1);
        broker.behaviour = (topic, bytes) -> {
            try { release.await(25, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return BrokerCommandPublisher.Attempt.confirmed();
        };
        Fixture f = fixture();
        fire(f.device());
        await(() -> sql.queryForObject("SELECT count(*) FROM command_outbox WHERE device_id=? AND state='IN_FLIGHT'", Integer.class, f.device()) == 1, "the dispatcher to claim");
        assertEquals("ACCEPTED_FOR_DISPATCH", sql.queryForObject("SELECT status FROM action_history WHERE device_id=?", String.class, f.device()));

        lifecycle.transition(f.ruleId(), "pause", null);
        release.countDown();

        await(() -> sql.queryForObject("SELECT count(*) FROM command_outbox WHERE device_id=? AND state='PUBLISHED'", Integer.class, f.device()) == 1, "delivery after the pause");
        assertEquals("SUCCESS", sql.queryForObject("SELECT status FROM action_history WHERE device_id=?", String.class, f.device()));
        assertEquals(1, broker.calls.size(), "delivery does not reauthorise and does not duplicate");
    }
}
