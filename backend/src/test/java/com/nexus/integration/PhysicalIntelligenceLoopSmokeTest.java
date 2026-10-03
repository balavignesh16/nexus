package com.nexus.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.action.domain.AcknowledgementStatus;
import com.nexus.action.domain.ActionHistoryRecord;
import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.device.capability.*;
import com.nexus.device.connectivity.*;
import com.nexus.device.domain.*;
import com.nexus.event.domain.DomainEventRepository;
import com.nexus.rule.domain.*;
import com.nexus.rule.persistence.*;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import com.nexus.twin.domain.DigitalTwinRepository;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.*;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class PhysicalIntelligenceLoopSmokeTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));
    @Container static GenericContainer<?> mosquitto = new GenericContainer<>("eclipse-mosquitto:2.0")
            .withExposedPorts(1883)
            .withCopyFileToContainer(MountableFile.forClasspathResource("mosquitto-test/mosquitto.conf"),
                    "/mosquitto/config/mosquitto.conf")
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("mqtt.broker.url", () -> brokerUrl());
        registry.add("mqtt.client.id", () -> "nexus-loop-smoke-" + UUID.randomUUID());
    }

    @Autowired SiteRepository sites;
    @Autowired BuildingRepository buildings;
    @Autowired SpaceRepository spaces;
    @Autowired DeviceRepository devices;
    @Autowired DeviceCapabilityRepository capabilities;
    @Autowired DeviceConnectivityRegistry connectivity;
    @Autowired RuleRegistry rules;
    @Autowired RuleMatchRepository matches;
    @Autowired DomainEventRepository events;
    @Autowired DigitalTwinRepository twins;
    @Autowired ActionHistoryRepository actions;

    private MqttClient simulatedDevice;
    private UUID deviceId;

    @BeforeEach void setUp() throws Exception {
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Loop Site", "smoke", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "Loop Building", "smoke", now, now));
        Space space = spaces.save(new Space(UUID.randomUUID(), building, "Loop Space", "smoke", now, now));
        Device device = devices.save(new Device(UUID.randomUUID(), space, "Loop ESP32", DeviceType.CUSTOM,
                "NEXUS", "SIM", "LOOP-" + UUID.randomUUID(), "smoke", DeviceMode.SIMULATED));
        device.update("Loop ESP32", DeviceStatus.ACTIVE, "smoke");
        device = devices.save(device);
        deviceId = device.getId();
        capabilities.save(new DeviceCapability(UUID.randomUUID(), deviceId, CapabilityType.TEMPERATURE,
                CapabilityDirection.READ, "CELSIUS", Map.of()));
        capabilities.save(new DeviceCapability(UUID.randomUUID(), deviceId, CapabilityType.LED,
                CapabilityDirection.WRITE, "BOOLEAN", Map.of()));
        connectivity.applyObservedStatus(deviceId, DeviceConnectivityStatus.ONLINE);

        rules.register(new Rule(UUID.randomUUID(), "Hot temperature turns LED on", true, 1,
                List.of(
                        new RuleCondition(RuleField.EVENT_TYPE, RuleOperator.EQ, "TWIN_UPDATED"),
                        new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, deviceId.toString()),
                        new RuleCondition(RuleField.VALUE, RuleOperator.GT, "30")
                ),
                List.of(new RuleAction("SEND_COMMAND", Map.of(
                        "deviceId", deviceId.toString(), "capability", "LED",
                        "command", "SET_STATE", "value", true))),
                Map.of()));

        simulatedDevice = new MqttClient(brokerUrl(), "sim-loop-" + UUID.randomUUID(), new MemoryPersistence());
        simulatedDevice.connect();
    }

    @AfterEach void tearDown() throws Exception {
        if (simulatedDevice != null && simulatedDevice.isConnected()) simulatedDevice.disconnect();
    }

    @Test
    @Timeout(40)
    void mqttTelemetryTraversesTwinRuleCommandAckAndPersistence() throws Exception {
        BlockingQueue<MqttMessage> commands = new ArrayBlockingQueue<>(1);
        simulatedDevice.setCallback(new MqttCallback() {
            public void connectionLost(Throwable cause) {}
            public void deliveryComplete(IMqttDeliveryToken token) {}
            public void messageArrived(String topic, MqttMessage message) { commands.offer(message); }
        });
        simulatedDevice.subscribe("nexus/devices/" + deviceId + "/commands", 1);

        String telemetry = """
                {"deviceId":"%s","timestamp":"%s","sensorType":"TEMPERATURE",
                 "value":35.0,"unit":"CELSIUS"}
                """.formatted(deviceId, Instant.now());
        MqttMessage reading = new MqttMessage(telemetry.getBytes(StandardCharsets.UTF_8));
        reading.setQos(1);
        simulatedDevice.publish("nexus/devices/" + deviceId + "/telemetry", reading);

        MqttMessage command = commands.poll(20, TimeUnit.SECONDS);
        assertNotNull(command, "matching telemetry must produce a command");
        JsonNode commandJson = new ObjectMapper().readTree(command.getPayload());
        UUID commandId = UUID.fromString(commandJson.get("commandId").asText());
        assertEquals("LED", commandJson.get("capability").asText());
        assertTrue(commandJson.get("value").asBoolean());

        String ack = """
                {"commandId":"%s","status":"ACCEPTED","timestamp":%d}
                """.formatted(commandId, Instant.now().getEpochSecond());
        MqttMessage acknowledgement = new MqttMessage(ack.getBytes(StandardCharsets.UTF_8));
        acknowledgement.setQos(1);
        simulatedDevice.publish("nexus/devices/" + deviceId + "/commands/ack", acknowledgement);

        ActionHistoryRecord action = awaitAck(commandId);
        assertEquals(AcknowledgementStatus.ACCEPTED, action.getAckStatus());
        assertEquals(deviceId, action.getDeviceId());
        assertFalse(twins.findByDeviceId(deviceId).isEmpty(), "Twin update must be persisted");
        assertFalse(events.findByDeviceIdOrderByTimestampDesc(deviceId,
                org.springframework.data.domain.PageRequest.of(0, 20)).isEmpty(), "event must be persisted");
        assertFalse(matches.findByDeviceId(deviceId,
                org.springframework.data.domain.PageRequest.of(0, 20)).isEmpty(), "rule match must be persisted");
    }

    private ActionHistoryRecord awaitAck(UUID commandId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            var record = actions.findById(commandId);
            if (record.isPresent() && record.get().getAckStatus() != null) return record.get();
            Thread.sleep(100);
        }
        fail("command ACK was not persisted");
        return null;
    }
    private static String brokerUrl() {
        return "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883);
    }
}
