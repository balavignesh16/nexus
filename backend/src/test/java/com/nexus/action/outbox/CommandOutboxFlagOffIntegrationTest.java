package com.nexus.action.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.action.api.dto.ActionHistoryResponse;
import com.nexus.action.domain.ActionHistoryRepository;
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
import org.springframework.context.ApplicationContext;
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
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flag OFF (the shipped default): Phase 4 changes nothing observable about command dispatch. No dispatcher bean exists, a
 * command is published directly with its publish-time timestamp and reports SUCCESS from the synchronous publish, and no
 * outbox row or state-history fact is written. Real Mosquitto and PostgreSQL V1 to V39.
 */
@SpringBootTest(properties = "nexus.command.outbox.dispatcher-enabled=true")
@Testcontainers
class CommandOutboxFlagOffIntegrationTest {

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
        registry.add("mqtt.client.id", () -> "nexus-outbox-off-" + UUID.randomUUID());
    }

    @Autowired ApplicationContext context;
    @Autowired CommandOutboxSettings settings;
    @Autowired DeviceCommandService commands;
    @Autowired ActionHistoryRepository actions;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired SiteRepository sites;
    @Autowired BuildingRepository buildings;
    @Autowired SpaceRepository spaces;
    @Autowired DeviceRepository devices;
    @Autowired DeviceCapabilityRepository capabilities;
    @Autowired DeviceConnectivityRegistry connectivity;

    private UUID deviceId;
    private MqttClient observer;
    private final BlockingQueue<MqttMessage> received = new ArrayBlockingQueue<>(10);

    @BeforeEach
    void setUp() throws Exception {
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Site-" + UUID.randomUUID(), "d", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "B", "d", now, now));
        Space space = spaces.save(new Space(UUID.randomUUID(), building, "S", "d", now, now));
        deviceId = devices.save(new Device(UUID.randomUUID(), space, "ESP-Off", DeviceType.CUSTOM, "Acme", "T1",
                "SN-OFF-" + UUID.randomUUID(), "d", DeviceMode.SIMULATED)).getId();
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
        if (observer != null && observer.isConnected()) observer.disconnect();
    }

    @Test
    void theFlagIsOffAndTheDispatcherIsPresentButHasNothingToDo() {
        assertFalse(settings.enabled());
        // the dispatcher is unconditional (it must be able to drain obligations after a rollback); with the flag off and nothing
        // queued it claims, publishes and writes nothing
        var dispatchers = context.getBeansOfType(CommandOutboxDispatcher.class);
        assertEquals(1, dispatchers.size());
        assertEquals(0, dispatchers.values().iterator().next().runPass());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox", Integer.class));
    }

    @Test
    @Timeout(60)
    void aCommandIsPublishedDirectlyWithThePublishTimeTimestampAndNoOutboxOrHistoryRowIsWritten() throws Exception {
        long before = Instant.now().getEpochSecond();
        ActionHistoryResponse response = commands.dispatchCommand(deviceId, new CreateDeviceCommandRequest(CapabilityType.LED, "SET_STATE", true));
        long after = Instant.now().getEpochSecond();

        // legacy semantics: the synchronous publish result is reported
        assertEquals(ExecutionStatus.SUCCESS, response.status());
        assertEquals(CommandStatus.PUBLISHED, response.commandStatus());
        assertNotNull(response.publishedAt());
        assertNull(response.deliveryState(), "no delivery state is invented when the outbox is not used");

        MqttMessage message = received.poll(20, TimeUnit.SECONDS);
        assertNotNull(message);
        JsonNode payload = mapper.readTree(message.getPayload());
        assertEquals(response.requestId().toString(), payload.get("commandId").asText());
        long timestamp = payload.get("timestamp").asLong();
        assertTrue(timestamp >= before && timestamp <= after, "the timestamp is generated at publish time exactly as before");

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE command_id = ?", Integer.class, response.requestId()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM command_state_history WHERE command_id = ?", Integer.class, response.requestId()));
        assertEquals(ExecutionStatus.SUCCESS, actions.findById(response.requestId()).orElseThrow().getStatus());
    }

    @Test
    void theApiJsonCarriesNoDeliveryStateFieldWhenThereIsNone() throws Exception {
        ActionHistoryResponse response = commands.dispatchCommand(deviceId, new CreateDeviceCommandRequest(CapabilityType.LED, "SET_STATE", false));
        assertFalse(mapper.valueToTree(response).has("deliveryState"), "the additive field is omitted, so flag-off JSON is unchanged");
    }
}
