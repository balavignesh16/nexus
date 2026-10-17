package com.nexus.action.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.action.application.executor.CommandExecutor;
import com.nexus.action.domain.ActionContext;
import com.nexus.action.domain.ActionExecutionRequest;
import com.nexus.action.domain.ActionExecutionResult;
import com.nexus.action.domain.ActionType;
import com.nexus.action.domain.ExecutionStatus;
import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.device.capability.CapabilityDirection;
import com.nexus.device.capability.CapabilityType;
import com.nexus.device.capability.DeviceCapability;
import com.nexus.device.capability.DeviceCapabilityRepository;
import com.nexus.device.connectivity.DeviceConnectivityRegistry;
import com.nexus.device.connectivity.DeviceConnectivityRepository;
import com.nexus.device.connectivity.DeviceConnectivityStatus;
import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceType;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 5D: proves the real, Spring-managed CommandExecutor - using the
 * actual primary MqttClient bean, not a mock - publishes a genuine MQTT
 * message to a real Mosquitto broker with the exact documented contract.
 * Mirrors the real-broker pattern already established by Tier 5B's
 * DeviceConnectivityMqttMechanicsTest.
 */
@SpringBootTest
@Testcontainers
class CommandDispatchMqttIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));

    @Container
    static GenericContainer<?> mosquitto = new GenericContainer<>("eclipse-mosquitto:2.0")
            .withExposedPorts(1883)
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("mosquitto-test/mosquitto.conf"),
                    "/mosquitto/config/mosquitto.conf")
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("mqtt.broker.url", () -> "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883));
        registry.add("mqtt.client.id", () -> "nexus-backend-test-" + UUID.randomUUID());
    }

    @Autowired
    private CommandExecutor commandExecutor;
    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private DeviceCapabilityRepository deviceCapabilityRepository;
    @Autowired
    private DeviceConnectivityRepository deviceConnectivityRepository;
    @Autowired
    private DeviceConnectivityRegistry deviceConnectivityRegistry;
    @Autowired
    private SiteRepository siteRepository;
    @Autowired
    private BuildingRepository buildingRepository;
    @Autowired
    private SpaceRepository spaceRepository;
    @Autowired
    private com.nexus.device.command.DeviceCommandService deviceCommandService;
    @Autowired
    private com.nexus.action.domain.ActionHistoryRepository actionHistoryRepository;
    @Autowired
    private com.nexus.pipeline.domain.PipelineTraceRepository pipelineTraceRepository;
    @Autowired
    private com.nexus.action.application.ActionExecutionEngine actionExecutionEngine;
    @Autowired
    private com.nexus.action.application.CommandAcceptanceService commandAcceptanceService;
    @Autowired
    private com.nexus.twin.domain.DesiredTwinStateRepository desiredRepository;
    @Autowired
    private com.nexus.twin.api.DigitalTwinController twinController;
    @Autowired
    private com.nexus.device.command.DeviceCommandAckMessageHandler ackHandler;
    @Autowired
    private com.nexus.device.binding.DeviceCapabilityBindingRepository bindings;
    @Autowired
    private com.nexus.device.capability.definition.CapabilityDefinitionRepository definitions;

    private MqttClient observer;
    private UUID deviceId;

    // Phase 4 dual mode: with the outbox flag OFF (default) these are the legacy synchronous behaviour; with it ON
    // (CommandDispatchMqttOutboxModeIntegrationTest) a command is ACCEPTED_FOR_DISPATCH first and the dispatcher publishes it.
    @Autowired
    private com.nexus.action.outbox.CommandOutboxSettings outboxSettings;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    protected boolean outboxMode() {
        return outboxSettings.enabled();
    }

    /** No-op with the flag OFF; with it ON waits until the dispatcher has resolved every queued command. */
    protected void awaitDelivery() {
        if (!outboxMode()) return;
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        try {
            while (jdbcTemplate.queryForObject("SELECT count(*) FROM command_outbox WHERE state IN ('QUEUED','IN_FLIGHT','RETRY_WAIT')", Integer.class) > 0) {
                if (System.nanoTime() > end) fail("the dispatcher did not deliver every queued command");
                Thread.sleep(25);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted while waiting for delivery");
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        // Every test gets its OWN site, building, space and device and nothing is bulk-deleted: a device that has command delivery
        // evidence cannot be deleted (migration V38 fails closed until retention purges the evidence), so a delete-everything
        // fixture cannot work once the outbox is in use. Assertions are all scoped to this test's deviceId.
        Instant now = Instant.now();
        String unique = UUID.randomUUID().toString();
        Site site = siteRepository.save(new Site(UUID.randomUUID(), "Site-" + unique, "desc", now, now));
        Building building = buildingRepository.save(new Building(UUID.randomUUID(), site, "Building-" + unique, "desc", now, now));
        Space space = spaceRepository.save(new Space(UUID.randomUUID(), building, "Space-" + unique, "desc", now, now));
        Device device = deviceRepository.save(new Device(UUID.randomUUID(), space, "ESP-Test", DeviceType.CUSTOM,
                "Acme", "T1", "SN-CMD-" + UUID.randomUUID(), "desc", com.nexus.device.domain.DeviceMode.SIMULATED));
        deviceId = device.getId();

        deviceCapabilityRepository.save(new DeviceCapability(UUID.randomUUID(), deviceId, CapabilityType.LED, CapabilityDirection.WRITE, null, null));
        // Written through the registry (not the raw repository) so its
        // in-memory cache - which InMemoryDeviceConnectivityRegistry hydrates
        // only once at startup - actually reflects ONLINE; CommandExecutor
        // reads the registry, never the repository directly.
        deviceConnectivityRegistry.applyObservedStatus(deviceId, DeviceConnectivityStatus.ONLINE);

        observer = new MqttClient("tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883),
                "observer-" + UUID.randomUUID(), new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        observer.connect(options);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (observer != null && observer.isConnected()) {
            observer.disconnect();
        }
    }

    @Test
    @Timeout(30)
    void realCommandExecutor_publishesToRealBroker_withExactContract() throws Exception {
        String topic = "nexus/devices/" + deviceId + "/commands";
        BlockingQueue<MqttMessage> received = new ArrayBlockingQueue<>(1);
        observer.setCallback(new MqttCallback() {
            @Override public void connectionLost(Throwable cause) { }
            @Override public void messageArrived(String t, MqttMessage message) { received.offer(message); }
            @Override public void deliveryComplete(IMqttDeliveryToken token) { }
        });
        observer.subscribe(topic, 1);

        ActionExecutionRequest request = new ActionExecutionRequest(
                UUID.randomUUID(), null, ActionType.SEND_COMMAND,
                Map.of("deviceId", deviceId.toString(), "capability", "LED", "command", "SET_STATE", "value", true));

        UUID expectedCommandId = request.requestId();
        if (outboxMode()) {
            // the outbox is the only publisher: the direct executor path is refused and the dispatcher delivers the SAME contract
            ActionExecutionResult direct = commandExecutor.execute(request, new ActionContext(null, null));
            assertEquals(ExecutionStatus.FAILURE, direct.status());
            assertEquals(CommandExecutor.DIRECT_PUBLICATION_DISABLED, direct.message());
            expectedCommandId = deviceCommandService.dispatchCommand(deviceId,
                    new com.nexus.device.command.api.dto.CreateDeviceCommandRequest(CapabilityType.LED, "SET_STATE", true)).requestId();
        } else {
            ActionExecutionResult result = commandExecutor.execute(request, new ActionContext(null, null));
            assertEquals(ExecutionStatus.SUCCESS, result.status());
        }

        MqttMessage message = received.poll(15, TimeUnit.SECONDS);
        assertNotNull(message, "the real broker must have delivered the published command to the subscriber");
        assertEquals(1, message.getQos());
        assertFalse(message.isRetained());

        ObjectMapper mapper = new ObjectMapper();
        JsonNode payload = mapper.readTree(message.getPayload());
        assertEquals(expectedCommandId.toString(), payload.get("commandId").asText());
        assertEquals("LED", payload.get("capability").asText());
        assertEquals("SET_STATE", payload.get("command").asText());
        assertTrue(payload.get("value").asBoolean());
    }

    @Test
    @Timeout(30)
    void manualCommand_viaDeviceCommandService_persistsWithNullMatchId() throws Exception {
        com.nexus.device.command.api.dto.CreateDeviceCommandRequest request =
                new com.nexus.device.command.api.dto.CreateDeviceCommandRequest(CapabilityType.LED, "SET_STATE", true);

        com.nexus.action.api.dto.ActionHistoryResponse response = deviceCommandService.dispatchCommand(deviceId, request);

        assertNull(response.matchId(), "a manually triggered command must never fabricate a matchId");

        com.nexus.action.domain.ActionHistoryRecord persisted = actionHistoryRepository.findById(response.requestId()).orElseThrow();
        assertNull(persisted.getMatchId(), "the persisted action_history row must have match_id = NULL, not a fabricated value");
        var desired = desiredRepository.findByDeviceId(deviceId);
        assertEquals(1, desired.size());
        assertEquals("legacy:LED", desired.getFirst().getTargetKey());
        assertEquals(response.requestId(), desired.getFirst().getCommandId());
        assertEquals(true, desired.getFirst().typedValue());
        awaitDelivery();
        var twin = twinController.getTwin(deviceId).getBody();
        assertNotNull(twin);
        assertTrue(twin.reportedState().isEmpty(), "command must not fabricate reported telemetry");
        assertEquals("DESIRED_UNVERIFIED", twin.effectiveState().get("legacy:LED").source());
        assertEquals("SUCCESS", twin.desiredState().get("legacy:LED").deliveryStatus());
    }

    @Test
    @Timeout(30)
    void laterAcceptedIntentSupersedesWithoutErasingPriorAction() {
        var on = new com.nexus.device.command.api.dto.CreateDeviceCommandRequest(CapabilityType.LED, "SET_STATE", true);
        var off = new com.nexus.device.command.api.dto.CreateDeviceCommandRequest(CapabilityType.LED, "SET_STATE", false);
        UUID first = deviceCommandService.dispatchCommand(deviceId, on).requestId();
        UUID second = deviceCommandService.dispatchCommand(deviceId, off).requestId();
        var desired = desiredRepository.findByDeviceId(deviceId);
        assertEquals(1, desired.size());
        assertEquals(second, desired.getFirst().getCommandId());
        assertEquals(false, desired.getFirst().typedValue());
        assertTrue(actionHistoryRepository.findById(first).isPresent());
    }

    @Test
    @Timeout(30)
    void concurrentSameTargetUsesDurableAcceptanceOrder() throws Exception {
        UUID onId = UUID.randomUUID();
        UUID offId = UUID.randomUUID();
        ActionExecutionRequest onRequest = new ActionExecutionRequest(onId, null, ActionType.SEND_COMMAND,
                Map.of("deviceId", deviceId.toString(), "capability", "LED", "command", "SET_STATE", "value", true));
        ActionExecutionRequest offRequest = new ActionExecutionRequest(offId, null, ActionType.SEND_COMMAND,
                Map.of("deviceId", deviceId.toString(), "capability", "LED", "command", "SET_STATE", "value", false));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var on = pool.submit(() -> {
                start.await();
                return commandAcceptanceService.accept(onRequest, new ActionContext(null, null), Instant.now());
            });
            var off = pool.submit(() -> {
                start.await();
                return commandAcceptanceService.accept(offRequest, new ActionContext(null, null), Instant.now());
            });
            start.countDown();
            on.get(20, TimeUnit.SECONDS);
            off.get(20, TimeUnit.SECONDS);
            var current = desiredRepository.findByDeviceId(deviceId);
            assertEquals(1, current.size());
            var onAction = actionHistoryRepository.findById(onId).orElseThrow();
            var offAction = actionHistoryRepository.findById(offId).orElseThrow();
            assertNotEquals(onAction.getTimestamp(), offAction.getTimestamp(),
                    "serialized acceptances must retain distinct acceptance instants");
            UUID laterAccepted = onAction.getTimestamp().isAfter(offAction.getTimestamp()) ? onId : offId;
            assertEquals(laterAccepted, current.getFirst().getCommandId(),
                    "the device-row lock must make the later durable acceptance the current desired intent");
            assertEquals(current.getFirst().getAcceptedForDispatchAt(),
                    actionHistoryRepository.findById(laterAccepted).orElseThrow().getTimestamp());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(30)
    void relayChannelsRemainIndependentUnderConcurrentCommands() throws Exception {
        deviceCapabilityRepository.save(new DeviceCapability(UUID.randomUUID(), deviceId,
                CapabilityType.RELAY, CapabilityDirection.WRITE, "BOOLEAN", Map.of("channels", List.of(1, 2))));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var first = pool.submit(() -> {
                start.await();
                return deviceCommandService.dispatchCommand(deviceId,
                        new com.nexus.device.command.api.dto.CreateDeviceCommandRequest(CapabilityType.RELAY,
                                "SET_STATE", Map.of("channel", 1, "state", true)));
            });
            var second = pool.submit(() -> {
                start.await();
                return deviceCommandService.dispatchCommand(deviceId,
                        new com.nexus.device.command.api.dto.CreateDeviceCommandRequest(CapabilityType.RELAY,
                                "SET_STATE", Map.of("channel", 2, "state", false)));
            });
            start.countDown();
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
            assertEquals(java.util.Set.of("channel:1", "channel:2"), desiredRepository.findByDeviceId(deviceId)
                    .stream().map(com.nexus.twin.domain.DesiredTwinState::getSubtargetKey).collect(java.util.stream.Collectors.toSet()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(30)
    void genericBoundRelayUsesBindingIdentityAndValidatedChannel() {
        var definition = definitions.findByKey("relay").orElseThrow();
        UUID bindingId = UUID.randomUUID();
        bindings.save(new com.nexus.device.binding.DeviceCapabilityBinding(bindingId, deviceId, definition,
                null, true, "BOOLEAN", Map.of("channels", List.of(1, 2)),
                Map.of("commandCapability", "RELAY"), Instant.now()));
        var request = new com.nexus.device.command.api.dto.CreateDeviceCommandRequest(null, "SET_STATE",
                Map.of("channel", 2, "state", true), "relay");
        var response = deviceCommandService.dispatchCommand(deviceId, request);
        if (outboxMode()) {
            assertEquals(ExecutionStatus.ACCEPTED_FOR_DISPATCH, response.status(), "queued, not yet published");
            awaitDelivery();
            assertEquals(ExecutionStatus.SUCCESS, actionHistoryRepository.findById(response.requestId()).orElseThrow().getStatus());
        } else {
            assertEquals(ExecutionStatus.SUCCESS, response.status());
        }
        var desired = desiredRepository.findByDeviceId(deviceId);
        assertEquals(1, desired.size());
        assertEquals("binding:" + bindingId, desired.getFirst().getTargetKey());
        assertEquals("channel:2", desired.getFirst().getSubtargetKey());
    }

    @Test
    @Timeout(30)
    void lateAckForSupersededCommandDoesNotRestoreOldDesiredIntent() {
        var on = new com.nexus.device.command.api.dto.CreateDeviceCommandRequest(CapabilityType.LED, "SET_STATE", true);
        var off = new com.nexus.device.command.api.dto.CreateDeviceCommandRequest(CapabilityType.LED, "SET_STATE", false);
        UUID first = deviceCommandService.dispatchCommand(deviceId, on).requestId();
        UUID second = deviceCommandService.dispatchCommand(deviceId, off).requestId();
        String payload = "{\"commandId\":\"" + first + "\",\"status\":\"ACCEPTED\",\"timestamp\":"
                + Instant.now().getEpochSecond() + "}";
        ackHandler.handle("nexus/devices/" + deviceId + "/commands/ack",
                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(com.nexus.action.domain.AcknowledgementStatus.ACCEPTED,
                actionHistoryRepository.findById(first).orElseThrow().getAckStatus());
        var current = desiredRepository.findByDeviceId(deviceId);
        assertEquals(second, current.getFirst().getCommandId());
        assertEquals(false, current.getFirst().typedValue());
    }

    @Test
    @Timeout(30)
    void fastAckIsNotOverwrittenByPublishResultUpdate() {
        UUID command = UUID.randomUUID();
        actionHistoryRepository.save(new com.nexus.action.domain.ActionHistoryRecord(command, null, deviceId,
                ActionType.SEND_COMMAND, ExecutionStatus.ACCEPTED_FOR_DISPATCH,
                Map.of("deviceId", deviceId.toString()), Instant.now(), "accepted"));
        actionHistoryRepository.updateAcknowledgement(command, deviceId,
                com.nexus.action.domain.AcknowledgementStatus.ACCEPTED, Instant.now(), Instant.now(), null);
        actionHistoryRepository.updatePublishResultIfAccepted(command, ExecutionStatus.SUCCESS,
                Instant.now(), "published");
        var action = actionHistoryRepository.findById(command).orElseThrow();
        assertEquals(ExecutionStatus.SUCCESS, action.getStatus());
        assertEquals(com.nexus.action.domain.AcknowledgementStatus.ACCEPTED, action.getAckStatus());
    }

    @Test
    @Timeout(30)
    void pipelineTrace_forDispatchedCommand_carriesRealDeviceId() throws Exception {
        pipelineTraceRepository.deleteAll();

        ActionExecutionRequest request = new ActionExecutionRequest(
                UUID.randomUUID(), null, ActionType.SEND_COMMAND,
                Map.of("deviceId", deviceId.toString(), "capability", "LED", "command", "SET_STATE", "value", true));

        // ActionExecutionEngine (not CommandExecutor directly) is what
        // records the trace - exercise it via the same engine the manual
        // and rule-triggered paths both use.
        actionExecutionEngine.process(java.util.List.of(request), new ActionContext(null, null), "corr-trace-test");

        java.util.List<com.nexus.pipeline.domain.PipelineTrace> traces = pipelineTraceRepository.findAll();
        assertTrue(traces.stream().anyMatch(t -> deviceId.toString().equals(t.deviceId())
                        && t.stage() == com.nexus.pipeline.domain.PipelineStage.ACTION),
                "the ACTION-stage PipelineTrace must carry the real deviceId, not null");
    }

    // ---------- Tier 5E: command acknowledgement loop, against a real broker ----------

    @Test
    @Timeout(30)
    void syntheticAck_publishedByObserver_isCorrelatedAndPersisted() throws Exception {
        // Dispatch a real command first, exactly like the device would
        // receive one, so a genuine ActionHistoryRecord (with a real
        // commandId/deviceId) exists to correlate the ACK against.
        com.nexus.device.command.api.dto.CreateDeviceCommandRequest commandRequest =
                new com.nexus.device.command.api.dto.CreateDeviceCommandRequest(CapabilityType.LED, "SET_STATE", true);
        com.nexus.action.api.dto.ActionHistoryResponse dispatched = deviceCommandService.dispatchCommand(deviceId, commandRequest);
        UUID commandId = dispatched.requestId();

        String ackTopic = "nexus/devices/" + deviceId + "/commands/ack";
        String ackPayload = String.format(
                "{\"commandId\":\"%s\",\"status\":\"ACCEPTED\",\"timestamp\":%d}",
                commandId, Instant.now().getEpochSecond());
        MqttMessage message = new MqttMessage(ackPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        message.setQos(1);
        message.setRetained(false);
        observer.publish(ackTopic, message);

        com.nexus.action.domain.ActionHistoryRecord persisted = pollUntilAcknowledged(commandId);

        assertEquals(com.nexus.action.domain.AcknowledgementStatus.ACCEPTED, persisted.getAckStatus());
        assertNotNull(persisted.getAckReceivedAt());
        assertEquals(deviceId, persisted.getDeviceId());
    }

    @Test
    @Timeout(30)
    void syntheticAck_wrongDeviceTopic_isNeverApplied() throws Exception {
        com.nexus.device.command.api.dto.CreateDeviceCommandRequest commandRequest =
                new com.nexus.device.command.api.dto.CreateDeviceCommandRequest(CapabilityType.LED, "SET_STATE", true);
        com.nexus.action.api.dto.ActionHistoryResponse dispatched = deviceCommandService.dispatchCommand(deviceId, commandRequest);
        UUID commandId = dispatched.requestId();

        UUID otherDeviceId = UUID.randomUUID();
        String ackTopic = "nexus/devices/" + otherDeviceId + "/commands/ack";
        String ackPayload = String.format(
                "{\"commandId\":\"%s\",\"status\":\"ACCEPTED\",\"timestamp\":%d}",
                commandId, Instant.now().getEpochSecond());
        MqttMessage message = new MqttMessage(ackPayload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        message.setQos(1);
        message.setRetained(false);
        observer.publish(ackTopic, message);

        // No positive way to observe "never happens" other than waiting past
        // the point a genuine ack (proven above) would already have landed,
        // then asserting the record is still un-acknowledged.
        Thread.sleep(3000);
        com.nexus.action.domain.ActionHistoryRecord persisted = actionHistoryRepository.findById(commandId).orElseThrow();
        assertNull(persisted.getAckStatus(), "an ACK arriving on a different device's topic must never be applied");
    }

    private com.nexus.action.domain.ActionHistoryRecord pollUntilAcknowledged(UUID commandId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            com.nexus.action.domain.ActionHistoryRecord record = actionHistoryRepository.findById(commandId).orElseThrow();
            if (record.getAckStatus() != null) {
                return record;
            }
            Thread.sleep(200);
        }
        fail("ActionHistoryRecord for " + commandId + " was never acknowledged within the timeout");
        return null;
    }
}
