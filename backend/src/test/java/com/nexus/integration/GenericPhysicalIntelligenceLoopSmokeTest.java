package com.nexus.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.action.domain.AcknowledgementStatus;
import com.nexus.action.domain.ActionHistoryRecord;
import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.action.domain.ExecutionStatus;
import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.device.binding.runtime.CapabilityResolutionSource;
import com.nexus.device.binding.runtime.RuntimeCapabilityResolver;
import com.nexus.device.capability.*;
import com.nexus.device.capability.definition.*;
import com.nexus.device.capability.definition.validation.CapabilityOperation;
import com.nexus.device.capability.definition.validation.CapabilityValueValidator;
import com.nexus.device.connectivity.*;
import com.nexus.device.domain.*;
import com.nexus.pipeline.domain.PipelineStage;
import com.nexus.pipeline.domain.PipelineTraceRepository;
import com.nexus.rule.domain.*;
import com.nexus.rule.persistence.*;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import com.nexus.telemetry.domain.TelemetryRepository;
import com.nexus.twin.domain.DigitalTwinRepository;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.*;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class GenericPhysicalIntelligenceLoopSmokeTest {
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
        registry.add("mqtt.broker.url", GenericPhysicalIntelligenceLoopSmokeTest::brokerUrl);
        registry.add("mqtt.client.id", () -> "nexus-generic-loop-" + UUID.randomUUID());
    }

    @Autowired SiteRepository sites;
    @Autowired BuildingRepository buildings;
    @Autowired SpaceRepository spaces;
    @Autowired DeviceRepository devices;
    @Autowired DeviceCapabilityRepository legacyCapabilities;
    @Autowired CapabilityDefinitionRepository definitions;
    @Autowired DeviceConnectivityRegistry connectivity;
    @Autowired RuleRegistry rules;
    @Autowired RuleMatchRepository matches;
    @Autowired TelemetryRepository telemetry;
    @Autowired DigitalTwinRepository twins;
    @Autowired ActionHistoryRepository actions;
    @Autowired PipelineTraceRepository traces;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired RuntimeCapabilityResolver resolver;
    @Autowired CapabilityValueValidator valueValidator;

    private MqttClient simulatedDevice;
    private UUID deviceId;
    private UUID otherDeviceId;
    private UUID soilDefinitionId;
    private UUID soilBindingId;
    private UUID ledBindingId;
    private UUID ruleId;

    @BeforeEach void setUp() throws Exception {
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Generic Loop Site", "smoke", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "Generic Loop Building", "smoke", now, now));
        Space space = spaces.save(new Space(UUID.randomUUID(), building, "Generic Loop Space", "smoke", now, now));
        deviceId = createActiveDevice(space, "Generic Loop Actuator");
        otherDeviceId = createActiveDevice(space, "Unrelated Device");

        // soil_moisture deliberately has no CapabilityType enum member.
        soilDefinitionId = UUID.randomUUID();
        definitions.save(new CapabilityDefinition(soilDefinitionId, "soil_moisture", "Soil Moisture", null,
                CapabilityDirection.READ, CapabilityValueType.NUMBER, "PERCENT", List.of("PERCENT"),
                BigDecimal.ZERO, new BigDecimal("100"), List.of(), List.of(), "environment",
                Map.of(), Map.of()));
        soilBindingId = bind(soilDefinitionId, "PERCENT", "{}");
        var led = definitions.findByKey("led").orElseThrow();
        ledBindingId = bind(led.getId(), led.getUnit(), "{\"commandCapability\":\"LED\"}");
        // Existing simulator actuator safety contract still requires this legacy WRITE row.
        legacyCapabilities.save(new DeviceCapability(UUID.randomUUID(), deviceId, CapabilityType.LED,
                CapabilityDirection.WRITE, "BOOLEAN", Map.of()));
        connectivity.applyObservedStatus(deviceId, DeviceConnectivityStatus.ONLINE);

        ruleId = UUID.randomUUID();
        rules.register(new Rule(ruleId, "Dry soil turns LED on", true, 1,
                List.of(new RuleCondition(RuleField.EVENT_TYPE, RuleOperator.EQ, "TWIN_UPDATED"),
                        new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, deviceId.toString()),
                        new RuleCondition(RuleField.VALUE, RuleOperator.GT, "70", null,
                                "soil_moisture", "PERCENT")),
                List.of(new RuleAction("SEND_COMMAND", Map.of("deviceId", deviceId.toString(),
                        "capabilityKey", "led", "command", "SET_STATE", "value", true))), Map.of()));

        simulatedDevice = new MqttClient(brokerUrl(), "sim-generic-loop-" + UUID.randomUUID(), new MemoryPersistence());
        simulatedDevice.connect();
    }

    @AfterEach void tearDown() throws Exception {
        if (simulatedDevice != null && simulatedDevice.isConnected()) simulatedDevice.disconnect();
        if (ruleId != null) rules.unregister(ruleId);
    }

    @Test @Timeout(60)
    void arbitraryGenericTelemetryTriggersRuleAndAcknowledgedSimulatedCommand() throws Exception {
        BlockingQueue<MqttMessage> receivedCommands = new ArrayBlockingQueue<>(2);
        simulatedDevice.setCallback(new MqttCallback() {
            public void connectionLost(Throwable cause) {}
            public void deliveryComplete(IMqttDeliveryToken token) {}
            public void messageArrived(String topic, MqttMessage message) { receivedCommands.offer(message); }
        });
        simulatedDevice.subscribe("nexus/devices/" + deviceId + "/commands", 1);

        Instant sampleTime = Instant.now();
        String reading = json.writeValueAsString(Map.of("deviceId", deviceId.toString(),
                "timestamp", sampleTime.toString(), "capabilityKey", "soil_moisture",
                "value", 72.5, "unit", "PERCENT"));
        publish("nexus/devices/" + deviceId + "/telemetry", reading);

        MqttMessage command = receivedCommands.poll(20, TimeUnit.SECONDS);
        assertNotNull(command, "generic MQTT telemetry must trigger a real MQTT command");
        JsonNode payload = json.readTree(command.getPayload());
        UUID commandId = UUID.fromString(payload.path("commandId").asText());
        assertEquals("LED", payload.path("capability").asText());
        assertEquals("SET_STATE", payload.path("command").asText());
        assertTrue(payload.path("value").isBoolean());
        assertTrue(payload.path("value").asBoolean());
        assertTrue(Math.abs(Instant.now().getEpochSecond() - payload.path("timestamp").asLong()) < 300);
        assertFalse(command.isRetained());
        assertEquals(1, command.getQos());

        // Simulated device validates the existing wire contract and applies the LED state
        // before it publishes the same ACK shape as MQTTPublisher._publish_ack.
        AtomicBoolean simulatedLedOn = new AtomicBoolean(false);
        simulatedLedOn.set(payload.path("value").asBoolean());
        assertTrue(simulatedLedOn.get(), "simulated actuator must apply the command before ACK");
        publish("nexus/devices/" + deviceId + "/commands/ack", json.writeValueAsString(Map.of(
                "commandId", commandId.toString(), "status", "ACCEPTED",
                "timestamp", Instant.now().getEpochSecond())));

        ActionHistoryRecord action = awaitAck(commandId);
        assertEquals(ExecutionStatus.SUCCESS, action.getStatus());
        assertEquals(AcknowledgementStatus.ACCEPTED, action.getAckStatus());
        assertEquals(deviceId, action.getDeviceId());
        assertEquals("led", action.getParameters().get("capabilityKey"));
        var target = resolver.resolve(deviceId, "led", CapabilityDirection.WRITE);
        assertEquals(CapabilityResolutionSource.GENERIC_BINDING, target.resolutionSource());
        assertEquals(ledBindingId, target.binding().getId());
        assertEquals(CapabilityDirection.WRITE, target.definition().getDirection());
        assertTrue(valueValidator.validate(target.definition(), true, target.effectiveUnit(),
                CapabilityOperation.COMMAND, "SET_STATE").isValid());

        var records = telemetry.findAll().stream().filter(r -> deviceId.equals(r.getDeviceId())
                && "soil_moisture".equals(r.getCapabilityKey())).toList();
        assertEquals(1, records.size());
        var record = records.getFirst();
        assertEquals(soilDefinitionId, record.getCapabilityDefinitionId());
        assertEquals(soilBindingId, record.getBindingId());
        assertEquals(CapabilityValueType.NUMBER, record.getValueType());
        assertEquals(0, new BigDecimal("72.5").compareTo((BigDecimal) record.getTypedValue()));
        assertEquals("PERCENT", record.getUnit());
        assertEquals(CapabilityResolutionSource.GENERIC_BINDING, record.getResolutionSource());

        var twin = twins.findByDeviceId(deviceId).stream()
                .filter(t -> "soil_moisture".equals(t.capabilityKey())).findFirst().orElseThrow();
        assertEquals(deviceId, twin.deviceId());
        assertEquals(soilDefinitionId, twin.capabilityDefinitionId());
        assertEquals(soilBindingId, twin.bindingId());
        assertEquals(CapabilityValueType.NUMBER, twin.valueType());
        assertEquals(0, new BigDecimal("72.5").compareTo((BigDecimal) twin.typedValue()));
        assertEquals(CapabilityResolutionSource.GENERIC_BINDING, twin.resolutionSource());
        assertTrue(twins.findByDeviceId(otherDeviceId).isEmpty());

        var matchPage = matches.findByRuleId(ruleId, org.springframework.data.domain.PageRequest.of(0, 10));
        assertEquals(1, matchPage.getTotalElements());
        var match = matchPage.getContent().getFirst();
        assertEquals(match.getMatchId(), action.getMatchId());
        assertEquals("matched", match.getEvaluationEvidence().get("outcome"));
        @SuppressWarnings("unchecked")
        var conditions = (List<Map<String, Object>>) match.getEvaluationEvidence().get("evaluatedConditions");
        var soilEvidence = conditions.stream().filter(c -> "soil_moisture".equals(c.get("capabilityKey")))
                .findFirst().orElseThrow();
        assertEquals(soilDefinitionId.toString(), soilEvidence.get("definitionId").toString());
        assertEquals(soilBindingId.toString(), soilEvidence.get("bindingId").toString());
        assertEquals("NUMBER", soilEvidence.get("valueType"));
        assertEquals("GT", soilEvidence.get("operator"));
        assertEquals("70", soilEvidence.get("expectedValue"));
        assertEquals(72.5, ((Number) soilEvidence.get("actualValue")).doubleValue());
        assertEquals(true, soilEvidence.get("conditionMet"));

        var deviceTraces = traces.findByDeviceIdOrderByStartTimestampDesc(deviceId.toString(),
                org.springframework.data.domain.PageRequest.of(0, 30)).getContent();
        assertTrue(deviceTraces.stream().anyMatch(t -> match.getCorrelationId().equals(t.correlationId())
                && t.stage() == PipelineStage.TELEMETRY
                && "GENERIC_BINDING".equals(t.metadata().get("resolutionSource"))));
        assertTrue(deviceTraces.stream().anyMatch(t -> match.getCorrelationId().equals(t.correlationId())
                && t.stage() == PipelineStage.TWIN));
    }

    private UUID createActiveDevice(Space space, String name) {
        Device device = devices.save(new Device(UUID.randomUUID(), space, name, DeviceType.CUSTOM,
                "NEXUS", "SIM", "GENERIC-" + UUID.randomUUID(), "smoke", DeviceMode.SIMULATED));
        device.update(name, DeviceStatus.ACTIVE, "smoke");
        return devices.save(device).getId();
    }

    private UUID bind(UUID definitionId, String unit, String externalIdentifiers) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO device_capability_bindings "
                        + "(id, device_id, capability_definition_id, enabled, unit, configuration, external_identifiers) "
                        + "VALUES (?, ?, ?, TRUE, ?, '{}'::jsonb, ?::jsonb)",
                id, deviceId, definitionId, unit, externalIdentifiers);
        return id;
    }

    private void publish(String topic, String payload) throws MqttException {
        MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
        message.setQos(1);
        simulatedDevice.publish(topic, message);
    }

    private ActionHistoryRecord awaitAck(UUID commandId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            var found = actions.findById(commandId);
            if (found.isPresent() && found.get().getAckStatus() != null) return found.get();
            Thread.sleep(100);
        }
        fail("command ACK was not persisted");
        return null;
    }

    private static String brokerUrl() {
        return "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883);
    }
}
