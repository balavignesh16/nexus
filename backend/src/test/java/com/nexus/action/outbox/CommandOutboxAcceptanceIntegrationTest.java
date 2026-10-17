package com.nexus.action.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.action.api.dto.ActionHistoryResponse;
import com.nexus.action.application.ActionExecutionEngine;
import com.nexus.action.application.ActionExecutorRegistry;
import com.nexus.action.application.CommandAcceptanceService;
import com.nexus.action.application.executor.CommandExecutor;
import com.nexus.action.domain.ActionContext;
import com.nexus.action.domain.ActionExecutedEvent;
import com.nexus.action.domain.ActionExecutionFailedEvent;
import com.nexus.action.domain.ActionExecutionRequest;
import com.nexus.action.domain.ActionExecutionResult;
import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.action.domain.ActionType;
import com.nexus.action.domain.CommandStatus;
import com.nexus.action.domain.ExecutionStatus;
import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.device.capability.CapabilityDirection;
import com.nexus.device.capability.CapabilityType;
import com.nexus.device.capability.DeviceCapability;
import com.nexus.device.capability.DeviceCapabilityRepository;
import com.nexus.device.command.DeviceCommandService;
import com.nexus.device.command.api.dto.CreateDeviceCommandRequest;
import com.nexus.device.connectivity.DeviceConnectivityRegistry;
import com.nexus.device.connectivity.DeviceConnectivityStatus;
import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceMode;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceType;
import com.nexus.pipeline.application.PipelineService;
import com.nexus.pipeline.domain.PipelineTrace;
import com.nexus.pipeline.domain.PipelineTraceRepository;
import com.nexus.realtime.RealtimePublisher;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import com.nexus.twin.domain.DesiredTwinStateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * P4.2 with the outbox flag ON in the real Spring application (PostgreSQL V1 to V39 and Mosquitto). The broker publisher is
 * a scripted double so that the moment between "durably accepted" and "published" can be held open deterministically; the
 * real wire is covered by {@link CommandOutboxMqttWireIntegrationTest}.
 */
@SpringBootTest(properties = {"nexus.command.outbox.dispatcher-enabled=true", "nexus.command.outbox.enabled=true", "nexus.command.outbox.poll-interval=PT0.25S"})
@Testcontainers
@RecordApplicationEvents
@AutoConfigureMockMvc
@Import(CommandOutboxAcceptanceIntegrationTest.ScriptedBroker.class)
class CommandOutboxAcceptanceIntegrationTest {

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
        registry.add("mqtt.client.id", () -> "nexus-outbox-acceptance-" + UUID.randomUUID());
    }

    @Autowired FakeBroker broker;
    @Autowired DeviceCommandService commands;
    @Autowired ActionHistoryRepository actions;
    @Autowired CommandOutboxRepository outbox;
    @Autowired CommandOutboxSettings settings;
    @Autowired(required = false) CommandOutboxDispatcher dispatcher;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired SiteRepository sites;
    @Autowired BuildingRepository buildings;
    @Autowired SpaceRepository spaces;
    @Autowired DeviceRepository devices;
    @Autowired DeviceCapabilityRepository capabilities;
    @Autowired DeviceConnectivityRegistry connectivity;
    @Autowired DesiredTwinStateRepository desired;
    @Autowired PipelineTraceRepository traces;
    @Autowired CommandExecutor commandExecutor;
    @Autowired CommandAcceptanceService acceptance;
    @Autowired ActionExecutorRegistry registry;
    @Autowired ApplicationEventPublisher events;
    @SpyBean RealtimePublisher realtime;
    @Autowired MockMvc mvc;
    @Autowired PipelineService pipeline;
    @Autowired ApplicationEvents applicationEvents;
    @SpyBean CommandStateHistory stateHistory;
    @SpyBean com.nexus.twin.application.VerificationService verificationSpy;

    private UUID deviceId;
    private CountDownLatch release;

    @BeforeEach
    void setUp() {
        release = new CountDownLatch(1);
        broker.calls.clear();
        broker.behaviour = (topic, bytes) -> BrokerCommandPublisher.Attempt.confirmed();
        reset(stateHistory);
        reset(verificationSpy);
        reset(realtime);
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Site-" + UUID.randomUUID(), "d", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "B", "d", now, now));
        Space space = spaces.save(new Space(UUID.randomUUID(), building, "S", "d", now, now));
        Device device = devices.save(new Device(UUID.randomUUID(), space, "ESP-Outbox", DeviceType.CUSTOM, "Acme", "T1",
                "SN-OB-" + UUID.randomUUID(), "d", DeviceMode.SIMULATED));
        deviceId = device.getId();
        capabilities.save(new DeviceCapability(UUID.randomUUID(), deviceId, CapabilityType.LED, CapabilityDirection.WRITE, null, null));
        connectivity.applyObservedStatus(deviceId, DeviceConnectivityStatus.ONLINE);
    }

    @AfterEach
    void drain() throws Exception {
        release.countDown();
        await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE state IN ('QUEUED','IN_FLIGHT','RETRY_WAIT')", Integer.class) == 0,
                "every obligation of this test to resolve");
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(25);
        }
    }

    private void holdPublish() {
        broker.behaviour = (topic, bytes) -> {
            try { release.await(25, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return BrokerCommandPublisher.Attempt.confirmed();
        };
    }

    private ActionHistoryResponse send(boolean value) {
        return commands.dispatchCommand(deviceId, new CreateDeviceCommandRequest(CapabilityType.LED, "SET_STATE", value));
    }

    // ----------------------------------------------------------------------------------- flag and wiring

    @Test
    void theDispatcherExistsOnlyBecauseTheFlagIsOn() {
        assertTrue(settings.enabled());
        assertNotNull(dispatcher);
    }

    // ------------------------------------------------------------------------------------- atomic acceptance

    @Test
    @Timeout(60)
    void aManualCommandIsAcceptedAtomicallyAndQueuedNeverReportedAsSuccessUntilPublished() throws Exception {
        holdPublish();
        ActionHistoryResponse response = send(true);
        UUID id = response.requestId();

        // the accepted-but-unpublished command: ACCEPTED_FOR_DISPATCH everywhere, with a delivery state
        assertEquals(ExecutionStatus.ACCEPTED_FOR_DISPATCH, response.status());
        assertEquals(CommandStatus.ACCEPTED_FOR_DISPATCH, response.commandStatus());
        assertTrue(List.of("QUEUED", "IN_FLIGHT").contains(response.deliveryState()), response.deliveryState());
        assertNull(response.publishedAt());

        // the whole acceptance unit is durable
        assertEquals(ExecutionStatus.ACCEPTED_FOR_DISPATCH, actions.findById(id).orElseThrow().getStatus());
        assertEquals(1, desired.findByDeviceId(deviceId).size());
        assertEquals(id, desired.findByDeviceId(deviceId).get(0).getCommandId());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM current_twin_verification_state WHERE desired_command_id = ?", Integer.class, id), "verification state exists");
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM twin_state_transitions WHERE command_id = ?", Integer.class, id) >= 1, "the desired-state transition exists");
        CommandOutboxEntity row = outbox.findById(id).orElseThrow();
        assertEquals(deviceId, row.getDeviceId());
        assertEquals("nexus/devices/" + deviceId + "/commands", row.getTopic());
        assertTrue(row.payload().verify());

        // the stored payload is the complete wire contract with the acceptance-time integer timestamp
        JsonNode payload = mapper.readTree(row.getPayloadText());
        assertEquals(id.toString(), payload.get("commandId").asText());
        assertEquals("LED", payload.get("capability").asText());
        assertEquals("SET_STATE", payload.get("command").asText());
        assertTrue(payload.get("value").asBoolean());
        assertTrue(payload.get("timestamp").isIntegralNumber());
        assertEquals(row.getPayloadEpochSeconds(), payload.get("timestamp").asLong());
        assertEquals(row.getPayloadEpochSeconds() + 15, row.getExpiresAt().getEpochSecond(), "the window is measured from the payload timestamp");

        // history facts: written in the same transaction
        List<String> facts = jdbc.queryForList("SELECT fact_type FROM command_state_history WHERE command_id = ? ORDER BY sequence", String.class, id);
        assertEquals(List.of("ACCEPTED", "QUEUED"), facts.subList(0, 2));

        // events and trace: no SUCCESS for a command that is only queued
        assertTrue(applicationEvents.stream(ActionExecutedEvent.class).noneMatch(e -> e.result().executionId().equals(id)),
                "no ActionExecuted(SUCCESS) event for a queued command");
        assertTrue(applicationEvents.stream(ActionExecutionFailedEvent.class).noneMatch(e -> e.result().executionId().equals(id)));
        List<PipelineTrace> actionTraces = traces.findByCorrelationIdOrderByStartTimestampAsc(id.toString()).stream()
                .filter(t -> t.getStage() == com.nexus.pipeline.domain.PipelineStage.ACTION).toList();
        assertFalse(actionTraces.isEmpty());
        assertEquals("ACCEPTED", actionTraces.get(0).getStatus(), "the pipeline trace says ACCEPTED, not SUCCESS, for a queued command");

        // nothing is published while the publisher is held
        assertEquals("ACCEPTED_FOR_DISPATCH", actions.findById(id).orElseThrow().getStatus().name());

        release.countDown();
        await(() -> outbox.findById(id).orElseThrow().getState() == OutboxState.PUBLISHED, "publication");
        assertEquals(ExecutionStatus.SUCCESS, actions.findById(id).orElseThrow().getStatus());
        assertNotNull(actions.findById(id).orElseThrow().getPublishedAt());
        assertEquals(1, broker.calls.size());
        assertEquals(row.getPayloadText(), broker.calls.get(0).text(), "the published bytes are the accepted payload, unchanged");
    }

    @Test
    @Timeout(60)
    void aFailureAfterTheDesiredStateWasWrittenRollsBackTheWholeAcceptanceUnit() {
        doThrow(new IllegalStateException("injected failure after the outbox row")).when(stateHistory)
                .record(any(OutboxTransitionRecord.class));
        long actionsBefore = actions.count();
        int historyBefore = jdbc.queryForObject("SELECT COUNT(*) FROM command_state_history", Integer.class);
        assertThrows(RuntimeException.class, () -> send(true));
        assertEquals(actionsBefore, actions.count(), "no ActionHistory row");
        assertEquals(historyBefore, jdbc.queryForObject("SELECT COUNT(*) FROM command_state_history", Integer.class), "no state-history row");
        assertTrue(desired.findByDeviceId(deviceId).isEmpty(), "no desired state");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE device_id = ?", Integer.class, deviceId), "no outbox row");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM current_twin_verification_state WHERE device_id = ?", Integer.class, deviceId), "no verification state");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM twin_state_transitions WHERE device_id = ?", Integer.class, deviceId), "no desired transition");
        assertTrue(broker.calls.isEmpty(), "nothing was published");
    }

    @Test
    @Timeout(60)
    void aRejectedCommandWritesNothing() {
        connectivity.applyObservedStatus(deviceId, DeviceConnectivityStatus.OFFLINE);
        long actionsBefore = actions.count();
        ActionExecutionRequest request = new ActionExecutionRequest(UUID.randomUUID(), null, ActionType.SEND_COMMAND,
                Map.of("deviceId", deviceId.toString(), "capability", "LED", "command", "SET_STATE", "value", true));
        assertThrows(CommandExecutor.CommandRejectedException.class, () -> acceptance.accept(request, new ActionContext(null, null), Instant.now()));
        assertEquals(actionsBefore, actions.count());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE device_id = ?", Integer.class, deviceId));
    }

    // ----------------------------------------------------------------------------------------- failures

    @Test
    @Timeout(60)
    void aNonPublicationIsReportedAsAFailureWithAnHonestReasonAndTheTwinIsToldOnce() throws Exception {
        broker.behaviour = (topic, bytes) -> BrokerCommandPublisher.Attempt.notSent("CLIENT_NOT_CONNECTED");
        UUID id = send(true).requestId();
        await(() -> outbox.findById(id).orElseThrow().getState() == OutboxState.DEAD_LETTER, "dead letter");
        var record = actions.findById(id).orElseThrow();
        assertEquals(ExecutionStatus.FAILURE, record.getStatus());
        assertTrue(record.getMessage().contains("not sent"));
        assertNull(record.getPublishedAt());
        assertEquals("PERMANENT_FAILURE", outbox.findById(id).orElseThrow().getTerminalReason().name());
        assertEquals(1, broker.calls.size(), "one attempt, no retry in P4.2");
        // desired state is retained, verification is told the command failed, nothing is cleared
        assertEquals(1, desired.findByDeviceId(deviceId).size());
        verify(verificationSpy, timeout(5000).times(1)).commandFailed(deviceId, id, "MQTT_PUBLICATION_FAILED");   // a PROVEN non-publication tells the twin, once
        Thread.sleep(800);
        assertEquals(1, broker.calls.size(), "still exactly one attempt");
    }

    @Test
    @Timeout(60)
    void anUncertainPublishIsNeverReportedAsPublishedOrAsAPlainFailure() throws Exception {
        broker.behaviour = (topic, bytes) -> BrokerCommandPublisher.Attempt.uncertain("PUBLISH_TIMEOUT");
        UUID id = send(true).requestId();
        await(() -> outbox.findById(id).orElseThrow().getState() == OutboxState.DEAD_LETTER, "dead letter");
        var record = actions.findById(id).orElseThrow();
        assertTrue(record.getMessage().startsWith("DELIVERY_UNCONFIRMED"));
        assertNull(record.getPublishedAt());
        assertEquals("UNCERTAIN_EXPIRED", outbox.findById(id).orElseThrow().getTerminalReason().name());
        Thread.sleep(500);
        verify(verificationSpy, org.mockito.Mockito.never()).commandFailed(any(), any(), any());   // uncertainty is not a definitive failure
    }

    @Test
    @Timeout(60)
    void aSecondCommandOfTheSameDeviceWaitsBehindTheFirstAndIsSentAfterIt() throws Exception {
        holdPublish();
        UUID first = send(true).requestId();
        await(() -> outbox.findById(first).orElseThrow().getState() == OutboxState.IN_FLIGHT, "first in flight");
        UUID second = send(false).requestId();
        Thread.sleep(800);
        assertEquals(OutboxState.QUEUED, outbox.findById(second).orElseThrow().getState(), "the newer command cannot overtake the in-flight one");
        assertEquals(1, broker.calls.size());
        release.countDown();
        await(() -> outbox.findById(second).orElseThrow().getState() == OutboxState.PUBLISHED, "second published");
        assertTrue(broker.calls.get(0).text().contains(first.toString()));
        assertTrue(broker.calls.get(1).text().contains(second.toString()));
    }

    // ---------------------------------------------------------------------------- direct publication bypass

    @Test
    @Timeout(60)
    void noDirectPublicationPathExistsWhileTheOutboxIsEnabled() {
        ActionExecutionRequest request = new ActionExecutionRequest(UUID.randomUUID(), null, ActionType.SEND_COMMAND,
                Map.of("deviceId", deviceId.toString(), "capability", "LED", "command", "SET_STATE", "value", true));
        // 1) CommandExecutor.execute
        ActionExecutionResult viaExecute = commandExecutor.execute(request, new ActionContext(null, null));
        assertEquals(ExecutionStatus.FAILURE, viaExecute.status());
        assertEquals(CommandExecutor.DIRECT_PUBLICATION_DISABLED, viaExecute.message());
        // 2) CommandExecutor.publishPrepared with a command that passed prepare
        ActionExecutionResult viaPrepared = commandExecutor.publishPrepared(request, commandExecutor.prepare(request));
        assertEquals(ExecutionStatus.FAILURE, viaPrepared.status());
        assertEquals(CommandExecutor.DIRECT_PUBLICATION_DISABLED, viaPrepared.message());
        // 3) the engine constructed WITHOUT the coordinator (the four-argument constructor)
        ActionExecutionEngine bareEngine = new ActionExecutionEngine(registry, events, realtime, pipeline);
        bareEngine.process(List.of(request), new ActionContext(null, null), "corr-bypass");
        assertTrue(applicationEvents.stream(ActionExecutionFailedEvent.class).anyMatch(e -> e.result().executionId().equals(request.requestId())),
                "the bare engine reports a failure rather than publishing");
        assertTrue(broker.calls.isEmpty(), "no publish reached the broker through any direct path");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE command_id = ?", Integer.class, request.requestId()));
    }

    // ------------------------------------------------------------------------ further P4.2 evidence

    @Test
    @Timeout(60)
    void aConstraintThatRefusesTheOutboxRowRejectsTheWholeAcceptanceAndStoresNoIntent() throws Exception {
        jdbc.execute("ALTER TABLE command_outbox ADD CONSTRAINT ck_test_refuse_everything CHECK (false) NOT VALID");
        try {
            long actionsBefore = actions.count();
            ActionHistoryResponse response = send(true);   // the manual path reports a rejection as a FAILURE row, as for any rejected command
            assertEquals(ExecutionStatus.FAILURE, response.status());
            assertTrue(response.message().contains("could not be queued"), response.message());
            assertTrue(desired.findByDeviceId(deviceId).isEmpty(), "no desired intent is stored");
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE device_id = ?", Integer.class, deviceId));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM current_twin_verification_state WHERE device_id = ?", Integer.class, deviceId));
            assertEquals(actionsBefore + 1, actions.count(), "only the rejection record exists, written outside the rolled-back acceptance");
            assertTrue(broker.calls.isEmpty());
        } finally {
            jdbc.execute("ALTER TABLE command_outbox DROP CONSTRAINT ck_test_refuse_everything");
        }
    }

    @Test
    @Timeout(90)
    void commandsOfOneDeviceAreOrderedByAStrictlyIncreasingCreationKeyInAcceptanceOrder() throws Exception {
        holdPublish();
        List<UUID> accepted = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) accepted.add(send(i % 2 == 0).requestId());
        List<UUID> byKey = jdbc.queryForList("SELECT command_id FROM command_outbox WHERE device_id = ? ORDER BY created_at", UUID.class, deviceId);
        assertEquals(accepted, byKey, "created_at order is the acceptance order");
        Integer distinct = jdbc.queryForObject("SELECT COUNT(DISTINCT created_at) FROM command_outbox WHERE device_id = ?", Integer.class, deviceId);
        assertEquals(25, distinct, "no two commands of a device share a creation key");
        release.countDown();
        await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE device_id = ? AND state = 'PUBLISHED'", Integer.class, deviceId) == 25, "all published");
        List<String> sent = broker.calls.stream().map(FakeBroker.Call::text).toList();
        for (int i = 0; i < 25; i++) assertTrue(sent.get(i).contains(accepted.get(i).toString()), "send " + i + " is accepted command " + i);
        assertEquals(1, broker.maxConcurrencyOnAnyTopic());
    }

    @Test
    @Timeout(60)
    void deliveryDoesNotReevaluateConnectivityAfterAcceptance() throws Exception {
        holdPublish();
        UUID id = send(true).requestId();
        await(() -> outbox.findById(id).orElseThrow().getState() == OutboxState.IN_FLIGHT, "claim");
        connectivity.applyObservedStatus(deviceId, DeviceConnectivityStatus.OFFLINE);
        release.countDown();
        await(() -> outbox.findById(id).orElseThrow().getState() == OutboxState.PUBLISHED, "delivery of an already accepted command");
        assertEquals(ExecutionStatus.SUCCESS, actions.findById(id).orElseThrow().getStatus());
    }

    @Test
    @Timeout(60)
    void theHttpApiReportsAcceptedForDispatchWithADeliveryStateAndTheRealtimeBroadcastNeverSaysSuccessBeforePublication() throws Exception {
        holdPublish();
        var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/devices/" + deviceId + "/commands")
                        .contentType("application/json").content("{\"capability\":\"LED\",\"command\":\"SET_STATE\",\"value\":true}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("ACCEPTED_FOR_DISPATCH"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.commandStatus").value("ACCEPTED_FOR_DISPATCH"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.deliveryState").exists())
                .andReturn();
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        UUID id = UUID.fromString(body.get("requestId").asText());
        org.mockito.ArgumentCaptor<ActionExecutionResult> queued = org.mockito.ArgumentCaptor.forClass(ActionExecutionResult.class);
        verify(realtime, timeout(5000).atLeastOnce()).publishActionExecuted(queued.capture(), any());
        assertTrue(queued.getAllValues().stream().filter(r -> r.executionId().equals(id)).allMatch(r -> r.status() == ExecutionStatus.ACCEPTED_FOR_DISPATCH),
                "no broadcast carries SUCCESS before the broker publication");
        release.countDown();
        await(() -> outbox.findById(id).orElseThrow().getState() == OutboxState.PUBLISHED, "publication");
        org.mockito.ArgumentCaptor<ActionExecutionResult> all = org.mockito.ArgumentCaptor.forClass(ActionExecutionResult.class);
        verify(realtime, timeout(5000).atLeast(2)).publishActionExecuted(all.capture(), any());
        assertTrue(all.getAllValues().stream().anyMatch(r -> r.executionId().equals(id) && r.status() == ExecutionStatus.SUCCESS),
                "SUCCESS is broadcast only after the dispatcher recorded the publication");
    }

    @Test
    @Timeout(60)
    void noHttpRouteCanRequeueResendOrReviveACommand() throws Exception {
        holdPublish();
        UUID id = send(true).requestId();
        await(() -> outbox.findById(id).orElseThrow().getState() == OutboxState.IN_FLIGHT, "the single attempt");
        String[] paths = {
                "/api/v1/commands/" + id + "/requeue", "/api/v1/commands/" + id + "/resend", "/api/v1/commands/" + id + "/retry",
                "/api/v1/actions/" + id + "/requeue", "/api/v1/actions/" + id + "/resend",
                "/api/v1/command-outbox/" + id + "/requeue", "/api/v1/command-outbox/" + id, "/api/v1/command-outbox",
                "/api/v1/dead-letters/" + id + "/requeue", "/api/v1/dead-letters/" + id, "/api/v1/dead-letters",
                "/api/v1/devices/" + deviceId + "/commands/" + id + "/requeue", "/api/v1/devices/" + deviceId + "/commands/" + id + "/resend"};
        for (String path : paths) {
            for (var builder : List.of(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path),
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path),
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(path),
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(path))) {
                int status = mvc.perform(builder.contentType("application/json").content("{}")).andReturn().getResponse().getStatus();
                assertTrue(status == 404 || status == 405, builder + " " + path + " must not exist but answered " + status);
            }
        }
        assertEquals(1, broker.calls.size(), "probing the routes sent nothing: only the original attempt exists");
    }
}
