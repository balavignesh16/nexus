package com.nexus.action.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.action.application.CommandAcceptanceService;
import com.nexus.action.application.executor.CommandExecutor;
import com.nexus.action.domain.ActionContext;
import com.nexus.action.domain.ActionExecutionRequest;
import com.nexus.action.domain.ActionExecutionResult;
import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.action.domain.ActionType;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The outbox on the REAL wire (flag ON, the real bounded Paho publisher, a real Mosquitto broker): exactly one QoS 1
 * non-retained message whose bytes are the stored accepted payload, with the acceptance-time timestamp; direct publication
 * is refused so nothing else reaches the broker; and a disconnected backend client is rejected at acceptance with no durable
 * write (acceptance behaviour is unchanged).
 */
@SpringBootTest(properties = {"nexus.command.outbox.dispatcher-enabled=true", "nexus.command.outbox.enabled=true", "nexus.command.outbox.poll-interval=PT0.25S"})
@Testcontainers
class CommandOutboxMqttWireIntegrationTest {

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
        registry.add("mqtt.client.id", () -> "nexus-outbox-wire-" + UUID.randomUUID());
    }

    @Autowired DeviceCommandService commands;
    @Autowired CommandExecutor commandExecutor;
    @Autowired CommandAcceptanceService acceptance;
    @Autowired ActionHistoryRepository actions;
    @Autowired CommandOutboxRepository outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired SiteRepository sites;
    @Autowired BuildingRepository buildings;
    @Autowired SpaceRepository spaces;
    @Autowired DeviceRepository devices;
    @Autowired DeviceCapabilityRepository capabilities;
    @Autowired DeviceConnectivityRegistry connectivity;
    @Autowired MqttClient backendClient;
    @Autowired MqttConnectOptions connectOptions;
    @Autowired ObjectMapper mapper;

    private UUID deviceId;
    private MqttClient observer;
    private final BlockingQueue<MqttMessage> received = new ArrayBlockingQueue<>(20);

    @BeforeEach
    void setUp() throws Exception {
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Site-" + UUID.randomUUID(), "d", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "B", "d", now, now));
        Space space = spaces.save(new Space(UUID.randomUUID(), building, "S", "d", now, now));
        Device device = devices.save(new Device(UUID.randomUUID(), space, "ESP-Wire", DeviceType.CUSTOM, "Acme", "T1",
                "SN-WIRE-" + UUID.randomUUID(), "d", DeviceMode.SIMULATED));
        deviceId = device.getId();
        capabilities.save(new DeviceCapability(UUID.randomUUID(), deviceId, CapabilityType.LED, CapabilityDirection.WRITE, null, null));
        connectivity.applyObservedStatus(deviceId, DeviceConnectivityStatus.ONLINE);
        observer = new MqttClient("tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883), "observer-" + UUID.randomUUID(), new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        observer.connect(options);
        observer.setCallback(new MqttCallback() {
            @Override public void connectionLost(Throwable cause) { }
            @Override public void messageArrived(String t, MqttMessage message) { received.offer(message); }
            @Override public void deliveryComplete(IMqttDeliveryToken token) { }
        });
        observer.subscribe("nexus/devices/" + deviceId + "/commands", 1);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (!backendClient.isConnected()) backendClient.connect(connectOptions);
        if (observer != null && observer.isConnected()) observer.disconnect();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE state IN ('QUEUED','IN_FLIGHT','RETRY_WAIT')", Integer.class) > 0
                && System.nanoTime() < end) Thread.sleep(50);
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(25);
        }
    }

    @Test
    @Timeout(60)
    void theBrokerReceivesExactlyOneQos1NonRetainedMessageWithTheAcceptedBytesAndAcceptanceTimestamp() throws Exception {
        UUID id = commands.dispatchCommand(deviceId, new CreateDeviceCommandRequest(CapabilityType.LED, "SET_STATE", true)).requestId();
        MqttMessage message = received.poll(20, TimeUnit.SECONDS);
        assertNotNull(message, "the dispatcher published through the real client");
        await(() -> outbox.findById(id).orElseThrow().getState() == OutboxState.PUBLISHED, "published state");

        CommandOutboxEntity row = outbox.findById(id).orElseThrow();
        assertArrayEquals(row.getPayloadText().getBytes(java.nio.charset.StandardCharsets.UTF_8), message.getPayload(),
                "the wire bytes are byte-identical to the payload stored at acceptance");
        assertEquals(1, message.getQos());
        assertFalse(message.isRetained());
        JsonNode payload = mapper.readTree(message.getPayload());
        assertEquals(id.toString(), payload.get("commandId").asText());
        assertEquals("LED", payload.get("capability").asText());
        assertEquals("SET_STATE", payload.get("command").asText());
        assertTrue(payload.get("value").asBoolean());
        assertTrue(payload.get("timestamp").isIntegralNumber());
        assertEquals(row.getPayloadEpochSeconds(), payload.get("timestamp").asLong());
        assertEquals(ExecutionStatus.SUCCESS, actions.findById(id).orElseThrow().getStatus());
        assertNotNull(actions.findById(id).orElseThrow().getPublishedAt());
        assertEquals(1, outbox.findById(id).orElseThrow().getAttemptCount(), "one attempt");

        assertNull(received.poll(1500, TimeUnit.MILLISECONDS), "exactly one message: no duplicate publication on the happy path");
    }

    @Test
    @Timeout(60)
    void directPublicationIsRefusedSoNothingBypassesTheOutboxOnTheRealBroker() throws Exception {
        ActionExecutionRequest request = new ActionExecutionRequest(UUID.randomUUID(), null, ActionType.SEND_COMMAND,
                Map.of("deviceId", deviceId.toString(), "capability", "LED", "command", "SET_STATE", "value", true));
        ActionExecutionResult result = commandExecutor.execute(request, new ActionContext(null, null));
        assertEquals(ExecutionStatus.FAILURE, result.status());
        assertEquals(CommandExecutor.DIRECT_PUBLICATION_DISABLED, result.message());
        assertEquals(ExecutionStatus.FAILURE, commandExecutor.publishPrepared(request, commandExecutor.prepare(request)).status());
        assertNull(received.poll(2, TimeUnit.SECONDS), "no direct path put anything on the broker");
    }

    @Test
    @Timeout(60)
    void aDisconnectedBackendClientIsRejectedAtAcceptanceWithNoDurableWriteAndRecoversAfterReconnect() throws Exception {
        backendClient.disconnect();
        UUID id = UUID.randomUUID();
        ActionExecutionRequest request = new ActionExecutionRequest(id, null, ActionType.SEND_COMMAND,
                Map.of("deviceId", deviceId.toString(), "capability", "LED", "command", "SET_STATE", "value", true));
        assertThrows(CommandExecutor.CommandRejectedException.class, () -> acceptance.accept(request, new ActionContext(null, null), Instant.now()));
        assertTrue(actions.findById(id).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE command_id = ?", Integer.class, id));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM command_state_history WHERE command_id = ?", Integer.class, id));

        backendClient.connect(connectOptions);
        UUID ok = commands.dispatchCommand(deviceId, new CreateDeviceCommandRequest(CapabilityType.LED, "SET_STATE", false)).requestId();
        assertNotNull(received.poll(20, TimeUnit.SECONDS));
        await(() -> outbox.findById(ok).orElseThrow().getState() == OutboxState.PUBLISHED, "published after reconnect");
    }
}
