package com.nexus.action.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ledger P4-090 on the REAL transport: the production dispatcher and the production bounded Paho publisher against a real Mosquitto broker with
 * the limit set to 5 per second. Twenty commands for twenty devices are accepted at once; they are published spread over time, never more than
 * 5 confirmed inside any one second (measured on the database clock from the recorded confirmation instants), each exactly once, and an
 * independent client that subscribes to every device receives each command exactly once with the stored bytes.
 */
@SpringBootTest(properties = {"nexus.command.outbox.dispatcher-enabled=true", "nexus.command.outbox.enabled=true", "nexus.command.outbox.poll-interval=PT0.25S",
        "nexus.command.flow.publish-rate-per-second=5"})
@Testcontainers
class CommandFlowMqttRateIntegrationTest {

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
        registry.add("mqtt.client.id", () -> "nexus-flow-rate-" + UUID.randomUUID());
    }

    @Autowired DeviceCommandService commands;
    @Autowired CommandFlowSettings flow;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired SiteRepository sites;
    @Autowired BuildingRepository buildings;
    @Autowired SpaceRepository spaces;
    @Autowired DeviceRepository devices;
    @Autowired DeviceCapabilityRepository capabilities;
    @Autowired DeviceConnectivityRegistry connectivity;

    private static void await(BooleanSupplier condition, String what, int seconds) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(25);
        }
    }

    @Test
    @Timeout(120)
    void twentyCommandsAreSpreadOverTimeNeverExceedFivePerSecondAndEachReachesTheBrokerOnce() throws Exception {
        assertEquals(5, flow.publishRatePerSecond(), "the production bean is configured from the property");
        Map<String, Integer> receivedCommandIds = new ConcurrentHashMap<>();
        MqttClient observer = new MqttClient("tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883), "observer-" + UUID.randomUUID(), new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        observer.connect(options);
        observer.setCallback(new MqttCallback() {
            @Override public void connectionLost(Throwable cause) { }
            @Override public void messageArrived(String t, MqttMessage message) throws Exception {
                receivedCommandIds.merge(mapper.readTree(message.getPayload()).get("commandId").asText(), 1, Integer::sum);
            }
            @Override public void deliveryComplete(IMqttDeliveryToken token) { }
        });
        observer.subscribe("nexus/devices/+/commands", 1);

        List<UUID> accepted = new ArrayList<>();
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Site-" + UUID.randomUUID(), "d", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "B", "d", now, now));
        Space space = spaces.save(new Space(UUID.randomUUID(), building, "S", "d", now, now));
        long started = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            UUID device = devices.save(new Device(UUID.randomUUID(), space, "ESP-Rate-" + i, DeviceType.CUSTOM, "Acme", "T1", "SN-RATE-" + UUID.randomUUID(), "d", DeviceMode.SIMULATED)).getId();
            capabilities.save(new DeviceCapability(UUID.randomUUID(), device, CapabilityType.LED, CapabilityDirection.WRITE, null, null));
            connectivity.applyObservedStatus(device, DeviceConnectivityStatus.ONLINE);
            accepted.add(commands.dispatchCommand(device, new CreateDeviceCommandRequest(CapabilityType.LED, "SET_STATE", true)).requestId());
        }
        await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE state = 'PUBLISHED'", Integer.class) == 20, "all 20 commands to be published", 60);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        int maxInOneSecond = jdbc.queryForObject("SELECT max(n) FROM (SELECT count(*) OVER (ORDER BY terminal_at RANGE BETWEEN INTERVAL '0.999 seconds' PRECEDING AND CURRENT ROW) AS n"
                + " FROM command_outbox WHERE state = 'PUBLISHED') w", Integer.class);
        assertTrue(maxInOneSecond <= 5, "confirmed publications in any one-second window: " + maxInOneSecond);
        assertTrue(elapsedMillis >= 2500, "20 commands at 5 per second cannot be published in under about 3 s; took " + elapsedMillis + " ms");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE state = 'DEAD_LETTER'", Integer.class), "none expired: 20 at 5 per second fit the 15 s window");
        for (UUID id : accepted) assertEquals(1, jdbc.queryForObject("SELECT attempt_count FROM command_outbox WHERE command_id = ?", Integer.class, id), "one attempt for " + id);
        await(() -> receivedCommandIds.size() == 20, "the observer to receive all 20 commands", 20);
        Thread.sleep(500);
        for (UUID id : accepted) assertEquals(1, receivedCommandIds.get(id.toString()), "the broker delivered command " + id + " exactly once");
        observer.disconnect();
    }
}
