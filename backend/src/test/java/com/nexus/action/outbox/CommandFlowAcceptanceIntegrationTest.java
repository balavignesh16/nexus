package com.nexus.action.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.action.application.ActionExecutionEngine;
import com.nexus.action.application.CommandAcceptanceService;
import com.nexus.action.domain.ActionContext;
import com.nexus.action.domain.ActionExecutionRequest;
import com.nexus.action.domain.ActionType;
import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.device.capability.CapabilityDirection;
import com.nexus.device.capability.CapabilityType;
import com.nexus.device.capability.DeviceCapability;
import com.nexus.device.capability.DeviceCapabilityRepository;
import com.nexus.device.connectivity.DeviceConnectivityRegistry;
import com.nexus.device.connectivity.DeviceConnectivityStatus;
import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceMode;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceType;
import com.nexus.rule.application.RuleExecutionAuthorization;
import com.nexus.rule.application.RuleExecutionSuppressedException;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleAction;
import com.nexus.rule.domain.RuleCondition;
import com.nexus.rule.domain.RuleField;
import com.nexus.rule.domain.RuleOperator;
import com.nexus.rule.plan.RuleCandidateOutcomes;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Queue capacity in the real Spring application, outbox flag ON (contract R18; ledger P4-087 to P4-089, P4-091): the caps of this context are
 * tiny (3 per device, 5 in all) and publication is held open, so the unresolved obligations that occupy capacity can be held deterministically.
 * A manual command over a cap is HTTP 429 with a stable code and leaves NOTHING behind; a rule-originated command over a cap is a visible
 * suppression and leaves nothing behind; terminal outcomes release capacity; another device is never affected by a full one.
 */
@SpringBootTest(properties = {"nexus.command.outbox.dispatcher-enabled=true", "nexus.command.outbox.enabled=true", "nexus.command.outbox.poll-interval=PT0.25S",
        "nexus.command.flow.global-queue-cap=5", "nexus.command.flow.device-queue-cap=3", "nexus.command.flow.publish-rate-per-second=1000"})
@Testcontainers
@AutoConfigureMockMvc
@Import(CommandFlowAcceptanceIntegrationTest.ScriptedBroker.class)
class CommandFlowAcceptanceIntegrationTest {

    @TestConfiguration
    static class ScriptedBroker {
        @Bean
        @Primary
        FakeBroker fakeBroker() { return new FakeBroker(); }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));
    @Container
    static GenericContainer<?> mosquitto = new GenericContainer<>("eclipse-mosquitto:2.0")
            .withExposedPorts(1883)
            .withCopyFileToContainer(MountableFile.forClasspathResource("mosquitto-test/mosquitto.conf"), "/mosquitto/config/mosquitto.conf")
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("mqtt.broker.url", () -> "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883));
        registry.add("mqtt.client.id", () -> "nexus-flow-acceptance-" + UUID.randomUUID());
    }

    @Autowired FakeBroker broker;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired SiteRepository sites;
    @Autowired BuildingRepository buildings;
    @Autowired SpaceRepository spaces;
    @Autowired DeviceRepository devices;
    @Autowired DeviceCapabilityRepository capabilities;
    @Autowired DeviceConnectivityRegistry connectivity;
    @Autowired CommandAdmission admission;
    @Autowired CommandAcceptanceService acceptance;
    @Autowired ActionExecutionEngine engine;
    @Autowired MeterRegistry meters;
    @SpyBean RuleExecutionAuthorization ruleAuthorization;
    @SpyBean RuleCandidateOutcomes candidateOutcomes;

    private CountDownLatch release;
    private final List<UUID> devicesOfTest = new ArrayList<>();

    @BeforeEach
    void setUp() {
        release = new CountDownLatch(1);
        broker.calls.clear();
        holdPublish();
        reset(ruleAuthorization, candidateOutcomes);
    }

    @AfterEach
    void drain() throws Exception {
        release.countDown();
        await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE state IN ('QUEUED','IN_FLIGHT','RETRY_WAIT')", Integer.class) == 0,
                "every obligation of this test to resolve");
        devicesOfTest.clear();
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(25);
        }
    }

    private void holdPublish() {
        broker.behaviour = (topic, bytes) -> {
            try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return BrokerCommandPublisher.Attempt.confirmed();
        };
    }

    private UUID newDevice() {
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Site-" + UUID.randomUUID(), "d", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "B", "d", now, now));
        Space space = spaces.save(new Space(UUID.randomUUID(), building, "S", "d", now, now));
        UUID id = devices.save(new Device(UUID.randomUUID(), space, "ESP-Flow", DeviceType.CUSTOM, "Acme", "T1", "SN-FLOW-" + UUID.randomUUID(), "d", DeviceMode.SIMULATED)).getId();
        capabilities.save(new DeviceCapability(UUID.randomUUID(), id, CapabilityType.LED, CapabilityDirection.WRITE, null, null));
        connectivity.applyObservedStatus(id, DeviceConnectivityStatus.ONLINE);
        devicesOfTest.add(id);
        return id;
    }

    private org.springframework.test.web.servlet.ResultActions send(UUID device, boolean value) throws Exception {
        return mvc.perform(post("/api/v1/devices/" + device + "/commands").contentType("application/json")
                .content("{\"capability\":\"LED\",\"command\":\"SET_STATE\",\"value\":" + value + "}"));
    }

    private UUID accepted(UUID device, boolean value) throws Exception {
        String body = send(device, value).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(mapper.readTree(body).get("requestId").asText());
    }

    private long count(String table, UUID device) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE device_id = ?", Long.class, device);
    }

    private long unresolved() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE state IN ('QUEUED','IN_FLIGHT','RETRY_WAIT')", Long.class);
    }

    // ----------------------------------------------------------------------------------------------- manual commands

    @Test
    @Timeout(90)
    void aManualCommandOverTheDeviceCapIsRefusedWithHttp429AndLeavesNothingBehind() throws Exception {
        UUID device = newDevice();
        UUID first = accepted(device, true);
        UUID second = accepted(device, false);
        UUID third = accepted(device, true);                                            // 3 of 3: exactly at the cap
        long actionsBefore = count("action_history", device), outboxBefore = count("command_outbox", device);
        String desiredCommandBefore = jdbc.queryForObject("SELECT command_id::text FROM current_desired_twin_state WHERE device_id = ?", String.class, device);
        long transitionsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM twin_state_transitions WHERE device_id = ? AND transition_type = 'DESIRED_STATE_CHANGED'", Long.class, device);
        assertEquals(3, transitionsBefore, "one desired-state transition per accepted command");
        double rejectedBefore = meters.counter("nexus.command.flow.rejected", "scope", "DEVICE", "origin", "MANUAL").count();

        send(device, false)                                                             // one above the cap
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("COMMAND_QUEUE_FULL"))
                .andExpect(jsonPath("$.scope").value("DEVICE"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());

        assertEquals(actionsBefore, count("action_history", device), "no ActionHistory row exists for the refused command: nothing suggests it was accepted");
        assertEquals(outboxBefore, count("command_outbox", device), "no outbox row");
        assertEquals(desiredCommandBefore, jdbc.queryForObject("SELECT command_id::text FROM current_desired_twin_state WHERE device_id = ?", String.class, device),
                "the desired state still belongs to the last ACCEPTED command, not the refused one");
        assertEquals(third.toString(), desiredCommandBefore);
        assertEquals(transitionsBefore, jdbc.queryForObject("SELECT COUNT(*) FROM twin_state_transitions WHERE device_id = ? AND transition_type = 'DESIRED_STATE_CHANGED'", Long.class, device),
                "and no desired-state transition was recorded for it");
        assertEquals(rejectedBefore + 1, meters.counter("nexus.command.flow.rejected", "scope", "DEVICE", "origin", "MANUAL").count());
        synchronized (broker.calls) {
            assertTrue(broker.calls.stream().allMatch(c -> List.of(first, second, third).stream().anyMatch(id -> c.text().contains(id.toString()))),
                    "every publication belongs to an ACCEPTED command; the refused one never reached the broker");
        }
    }

    @Test
    @Timeout(90)
    void aFullDeviceNeverBlocksAnotherDeviceAndTheGlobalCapIsExactAcrossDevices() throws Exception {
        UUID a = newDevice(), b = newDevice(), c = newDevice();
        for (int i = 0; i < 3; i++) accepted(a, i % 2 == 0);                            // a at its cap
        send(a, true).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.scope").value("DEVICE"));
        accepted(b, true);                                                              // b is unaffected: 4 of 5 in all
        accepted(b, false);                                                             // 5 of 5: exactly at the global cap
        assertEquals(5, unresolved());
        send(c, true).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.scope").value("GLOBAL"))                 // one above
                .andExpect(jsonPath("$.code").value("COMMAND_QUEUE_FULL"));
        assertEquals(0, count("action_history", c));
        assertEquals(0, count("command_outbox", c));
        assertEquals(5, unresolved());
    }

    @Test
    @Timeout(90)
    void aTerminalOutcomeReleasesCapacityAndTheNextCommandIsAccepted() throws Exception {
        UUID device = newDevice();
        for (int i = 0; i < 3; i++) accepted(device, i % 2 == 0);
        send(device, true).andExpect(status().isTooManyRequests());
        release.countDown();                                                            // the held publications complete
        await(() -> unresolved() == 0, "all three to be published");
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE device_id = ? AND state = 'PUBLISHED'", Long.class, device));
        accepted(device, true);                                                         // published commands no longer occupy a slot
    }

    @Test
    void admissionRefusesToRunOutsideAnAcceptanceTransaction() {
        assertThrows(IllegalTransactionStateException.class, () -> admission.admit(UUID.randomUUID(), false),
                "the capacity check is only meaningful under the device lock of the acceptance transaction");
    }

    // ---------------------------------------------------------------------------------------------- rule-originated

    private Rule ruleWithCommandFor(UUID device) {
        return new Rule(UUID.randomUUID(), "Flow rule", true, 0,
                List.of(new RuleCondition(RuleField.EVENT_TYPE, RuleOperator.EQ, "TWIN_UPDATED")),
                List.of(new RuleAction("SEND_COMMAND", Map.of("deviceId", device.toString(), "capability", "LED", "command", "SET_STATE", "value", true))),
                Map.of());
    }

    private ActionExecutionRequest ruleRequest(UUID device) {
        return new ActionExecutionRequest(UUID.randomUUID(), UUID.randomUUID(), ActionType.SEND_COMMAND,
                Map.of("deviceId", device.toString(), "capability", "LED", "command", "SET_STATE", "value", true));
    }

    @Test
    @Timeout(90)
    void aRuleOriginatedCommandOverTheCapIsAVisibleSuppressionWithNothingDispatchedOrWritten() throws Exception {
        UUID device = newDevice();
        for (int i = 0; i < 3; i++) accepted(device, i % 2 == 0);
        doNothing().when(ruleAuthorization).check(any(), any());                        // authorisation is the Phase 3 contract's; this test is about capacity
        long actionsBefore = count("action_history", device), outboxBefore = count("command_outbox", device);
        String desiredBefore = jdbc.queryForObject("SELECT command_id::text FROM current_desired_twin_state WHERE device_id = ?", String.class, device);
        int callsBefore = broker.calls.size();
        ActionExecutionRequest request = ruleRequest(device);
        ActionContext context = new ActionContext(null, ruleWithCommandFor(device));

        RuleExecutionSuppressedException suppressed = assertThrows(RuleExecutionSuppressedException.class,
                () -> acceptance.accept(request, context, Instant.now(), "corr-flow"));
        assertEquals("QUEUE_CAPACITY_EXCEEDED_DEVICE", suppressed.getMessage(), "a stable, visible reason");
        assertEquals(actionsBefore, count("action_history", device));
        assertEquals(outboxBefore, count("command_outbox", device));
        assertEquals(desiredBefore, jdbc.queryForObject("SELECT command_id::text FROM current_desired_twin_state WHERE device_id = ?", String.class, device), "no desired state written");

        // through the engine the suppression is recorded on the persisted candidate (DENIED with the reason) and no failure result is fabricated
        ActionExecutionRequest viaEngine = ruleRequest(device);
        engine.process(List.of(viaEngine), context, "corr-flow-2");
        verify(candidateOutcomes, timeout(5000)).denied(eq(viaEngine.requestId()), eq("QUEUE_CAPACITY_EXCEEDED_DEVICE"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM action_history WHERE request_id = ?", Long.class, viaEngine.requestId()), "no ActionHistory row for a suppressed rule command");
        assertEquals(callsBefore, broker.calls.size(), "nothing was dispatched");
        assertTrue(meters.counter("nexus.command.flow.rejected", "scope", "DEVICE", "origin", "RULE").count() >= 2);
    }

    @Test
    @Timeout(90)
    void aRuleCommandIsAcceptedNormallyWhileThereIsCapacity() throws Exception {
        UUID device = newDevice();
        doNothing().when(ruleAuthorization).check(any(), any());
        ActionExecutionRequest request = ruleRequest(device);
        acceptance.accept(request, new ActionContext(null, ruleWithCommandFor(device)), Instant.now(), "corr-flow-ok");
        assertEquals(1, count("command_outbox", device), "below every cap a rule-originated command is queued exactly like a manual one");
    }
}
