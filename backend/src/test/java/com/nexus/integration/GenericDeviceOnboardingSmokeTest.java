package com.nexus.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.building.domain.Building;
import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.action.domain.AcknowledgementStatus;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.device.binding.DeviceCapabilityBindingRepository;
import com.nexus.device.binding.runtime.CapabilityResolutionSource;
import com.nexus.device.capability.CapabilityDirection;
import com.nexus.device.capability.CapabilityType;
import com.nexus.device.capability.definition.*;
import com.nexus.device.domain.*;
import com.nexus.device.onboarding.DeviceOnboardingSessionRepository;
import com.nexus.device.onboarding.OnboardingState;
import com.nexus.device.template.*;
import com.nexus.rule.domain.*;
import com.nexus.rule.persistence.RuleRegistry;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import com.nexus.telemetry.domain.TelemetryRepository;
import com.nexus.twin.domain.DigitalTwinRepository;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class GenericDeviceOnboardingSmokeTest {
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
        registry.add("mqtt.broker.url", GenericDeviceOnboardingSmokeTest::brokerUrl);
        registry.add("mqtt.client.id", () -> "nexus-onboarding-" + UUID.randomUUID());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired SiteRepository sites;
    @Autowired BuildingRepository buildings;
    @Autowired SpaceRepository spaces;
    @Autowired DeviceRepository devices;
    @Autowired DeviceTemplateRepository templates;
    @Autowired DeviceTemplateCapabilityRepository templateCapabilities;
    @Autowired CapabilityDefinitionRepository definitions;
    @Autowired DeviceCapabilityBindingRepository bindings;
    @Autowired DeviceOnboardingSessionRepository sessions;
    @Autowired TelemetryRepository telemetry;
    @Autowired DigitalTwinRepository twins;
    @Autowired RuleRegistry rules;
    @Autowired ActionHistoryRepository actions;

    @Test @Timeout(70)
    void provisionedGenericDeviceBecomesOperationalOnlyAfterStatusAndTypedTelemetry() throws Exception {
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Onboard Site", "test", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "Onboard Building", "test", now, now));
        Space space = spaces.save(new Space(UUID.randomUUID(), building, "Onboard Space", "test", now, now));
        UUID definitionId = UUID.randomUUID();
        definitions.save(new CapabilityDefinition(definitionId, "soil_moisture", "Soil Moisture", null,
                CapabilityDirection.READ, CapabilityValueType.NUMBER, "PERCENT", List.of("PERCENT"),
                BigDecimal.ZERO, new BigDecimal("100"), List.of(), List.of(), "environment",
                Map.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> CapabilityType.valueOf("SOIL_MOISTURE"));
        var template = templates.save(new DeviceTemplate(UUID.randomUUID(), "generic_soil_probe", "Generic Soil Probe",
                "Test probe", "NEXUS", "SOIL", ProtocolType.MQTT, true, Map.of()));
        templateCapabilities.save(new DeviceTemplateCapability(UUID.randomUUID(), template,
                definitions.findById(definitionId).orElseThrow(), true, "PERCENT", 1, null, Map.of()));
        templateCapabilities.save(new DeviceTemplateCapability(UUID.randomUUID(), template,
                definitions.findByKey("led").orElseThrow(), true, "BOOLEAN", 2, null, Map.of()));

        String requestKey = "onboard-" + UUID.randomUUID();
        String serial = "SOIL-" + UUID.randomUUID();
        String request = json.writeValueAsString(Map.of("requestKey", requestKey, "templateId", template.getId(),
                "spaceId", space.getId(), "name", "Soil Probe", "serialNumber", serial,
                "mode", "SIMULATED"));
        JsonNode created = json.readTree(mvc.perform(post("/api/v1/device-onboarding")
                .contentType(MediaType.APPLICATION_JSON).content(request)).andReturn().getResponse().getContentAsString());
        UUID sessionId = UUID.fromString(created.path("id").asText());
        UUID deviceId = UUID.fromString(created.path("deviceId").asText());
        assertEquals("WAITING_FOR_STATUS", created.path("state").asText());
        assertEquals(DeviceStatus.REGISTERED, devices.findById(deviceId).orElseThrow().getStatus());
        assertEquals(template.getId(), devices.findById(deviceId).orElseThrow().getDeviceTemplate().getId());
        assertEquals(2, bindings.findByDeviceId(deviceId).size());
        assertEquals(sessionId, UUID.fromString(json.readTree(mvc.perform(post("/api/v1/device-onboarding")
                .contentType(MediaType.APPLICATION_JSON).content(request)).andReturn()
                .getResponse().getContentAsString()).path("id").asText()));

        JsonNode contract = json.readTree(mvc.perform(get("/api/v1/devices/{id}/contract", deviceId))
                .andReturn().getResponse().getContentAsString());
        assertEquals("MQTT", contract.path("protocol").asText());
        assertEquals("nexus/devices/" + deviceId + "/telemetry", contract.path("topics").path("telemetry").asText());
        assertEquals("nexus/devices/" + deviceId + "/status", contract.path("topics").path("status").asText());
        assertEquals("soil_moisture", capability(contract, "soil_moisture").path("key").asText());
        assertEquals("LED", capability(contract, "led").path("externalCommandIdentifier").asText());

        UUID ruleId = UUID.randomUUID();
        rules.register(new Rule(ruleId, "Onboard soil rule", true, 1,
                List.of(new RuleCondition(RuleField.EVENT_TYPE, RuleOperator.EQ, "TWIN_UPDATED"),
                        new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, deviceId.toString()),
                        new RuleCondition(RuleField.VALUE, RuleOperator.GT, "70", null,
                                "soil_moisture", "PERCENT")),
                List.of(new RuleAction("SEND_COMMAND", Map.of("deviceId", deviceId.toString(),
                        "capabilityKey", "led", "command", "SET_STATE", "value", true))), Map.of()));

        try (MqttClient client = new MqttClient(brokerUrl(), "sim-onboard-" + UUID.randomUUID(), new MemoryPersistence())) {
            client.connect();
            var commands = new ArrayBlockingQueue<MqttMessage>(2);
            client.setCallback(new MqttCallback() {
                public void connectionLost(Throwable cause) {}
                public void deliveryComplete(IMqttDeliveryToken token) {}
                public void messageArrived(String topic, MqttMessage message) { commands.offer(message); }
            });
            client.subscribe("nexus/devices/" + deviceId + "/commands", 1);
            publish(client, "nexus/devices/" + deviceId + "/status", "online");
            awaitState(sessionId, OnboardingState.WAITING_FOR_TELEMETRY);
            assertEquals(DeviceStatus.REGISTERED, devices.findById(deviceId).orElseThrow().getStatus());
            String reading = json.writeValueAsString(Map.of("deviceId", deviceId.toString(),
                    "timestamp", Instant.now().toString(), "capabilityKey", "soil_moisture",
                    "value", 72.5, "unit", "PERCENT"));
            publish(client, "nexus/devices/" + deviceId + "/telemetry", reading);
            awaitState(sessionId, OnboardingState.OPERATIONAL);
            MqttMessage command = commands.poll(15, TimeUnit.SECONDS);
            assertNotNull(command, "onboarded generic bindings must dispatch the rule command");
            JsonNode commandPayload = json.readTree(command.getPayload());
            assertEquals("LED", commandPayload.path("capability").asText());
            assertTrue(commandPayload.path("value").asBoolean());
            AtomicBoolean simulatedLed = new AtomicBoolean(false);
            simulatedLed.set(commandPayload.path("value").asBoolean());
            assertTrue(simulatedLed.get());
            UUID commandId = UUID.fromString(commandPayload.path("commandId").asText());
            publish(client, "nexus/devices/" + deviceId + "/commands/ack",
                    json.writeValueAsString(Map.of("commandId", commandId.toString(),
                            "status", "ACCEPTED", "timestamp", Instant.now().getEpochSecond())));
            awaitAck(commandId);
            client.disconnect();
        }
        rules.unregister(ruleId);
        assertEquals(DeviceStatus.ACTIVE, devices.findById(deviceId).orElseThrow().getStatus());
        assertNotNull(sessions.findById(sessionId).orElseThrow().getFirstTelemetryAt());
        var records = telemetry.findAll().stream().filter(r -> r.getDeviceId().equals(deviceId)).toList();
        assertEquals(1, records.size());
        assertEquals(CapabilityResolutionSource.GENERIC_BINDING, records.getFirst().getResolutionSource());
        assertEquals(0, new BigDecimal("72.5").compareTo((BigDecimal) records.getFirst().getTypedValue()));
        var twin = twins.findByDeviceId(deviceId).getFirst();
        assertEquals("soil_moisture", twin.capabilityKey());
        assertEquals(CapabilityValueType.NUMBER, twin.valueType());
        assertEquals(0, new BigDecimal("72.5").compareTo((BigDecimal) twin.typedValue()));
    }

    @Test @Timeout(70)
    void invalidFirstTelemetryIsRejectedWithoutTwinOrActivationAndCanRetry() throws Exception {
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Reject Site", "test", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "Reject Building", "test", now, now));
        Space space = spaces.save(new Space(UUID.randomUUID(), building, "Reject Space", "test", now, now));
        UUID definitionId = UUID.randomUUID();
        definitions.save(new CapabilityDefinition(definitionId, "soil_moisture_reject", "Soil Moisture", null,
                CapabilityDirection.READ, CapabilityValueType.NUMBER, "PERCENT", List.of("PERCENT"),
                BigDecimal.ZERO, new BigDecimal("100"), List.of(), List.of(), "environment",
                Map.of(), Map.of()));
        var template = templates.save(new DeviceTemplate(UUID.randomUUID(), "reject_soil_probe", "Reject Soil Probe",
                null, "NEXUS", "SOIL", ProtocolType.MQTT, true, Map.of()));
        templateCapabilities.save(new DeviceTemplateCapability(UUID.randomUUID(), template,
                definitions.findById(definitionId).orElseThrow(), true, "PERCENT", 1, null, Map.of()));
        String request = json.writeValueAsString(Map.of("requestKey", "reject-" + UUID.randomUUID(),
                "templateId", template.getId(), "spaceId", space.getId(), "name", "Reject Probe",
                "serialNumber", "REJECT-" + UUID.randomUUID(), "mode", "SIMULATED"));
        JsonNode created = json.readTree(mvc.perform(post("/api/v1/device-onboarding")
                .contentType(MediaType.APPLICATION_JSON).content(request)).andReturn().getResponse().getContentAsString());
        UUID sessionId = UUID.fromString(created.path("id").asText());
        UUID deviceId = UUID.fromString(created.path("deviceId").asText());

        try (MqttClient client = new MqttClient(brokerUrl(), "sim-reject-" + UUID.randomUUID(), new MemoryPersistence())) {
            client.connect();
            publish(client, "nexus/devices/" + deviceId + "/status", "online");
            awaitState(sessionId, OnboardingState.WAITING_FOR_TELEMETRY);
            String invalid = json.writeValueAsString(Map.of("deviceId", deviceId.toString(),
                    "timestamp", Instant.now().toString(), "capabilityKey", "soil_moisture_reject",
                    "value", 150, "unit", "PERCENT"));
            publish(client, "nexus/devices/" + deviceId + "/telemetry", invalid);
            awaitState(sessionId, OnboardingState.TELEMETRY_REJECTED);
            assertEquals("CAPABILITY_VALUE_OUT_OF_RANGE", sessions.findById(sessionId).orElseThrow().getFailureCode());
            assertEquals(DeviceStatus.REGISTERED, devices.findById(deviceId).orElseThrow().getStatus());
            assertTrue(telemetry.findAll().stream().noneMatch(r -> r.getDeviceId().equals(deviceId)));
            assertTrue(twins.findByDeviceId(deviceId).isEmpty());

            JsonNode retried = json.readTree(mvc.perform(post("/api/v1/device-onboarding/{id}/retry", sessionId))
                    .andReturn().getResponse().getContentAsString());
            assertEquals("WAITING_FOR_TELEMETRY", retried.path("state").asText());
            String valid = json.writeValueAsString(Map.of("deviceId", deviceId.toString(),
                    "timestamp", Instant.now().toString(), "capabilityKey", "soil_moisture_reject",
                    "value", 50, "unit", "PERCENT"));
            publish(client, "nexus/devices/" + deviceId + "/telemetry", valid);
            awaitState(sessionId, OnboardingState.OPERATIONAL);
            client.disconnect();
        }
        assertEquals(DeviceStatus.ACTIVE, devices.findById(deviceId).orElseThrow().getStatus());
        assertEquals(1, telemetry.findAll().stream().filter(r -> r.getDeviceId().equals(deviceId)).count());
    }

    @Test @Timeout(45)
    void failedProvisioningRollsBackDeviceBindingsAndSession() throws Exception {
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Rollback Site", "test", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "Rollback Building", "test", now, now));
        Space space = spaces.save(new Space(UUID.randomUUID(), building, "Rollback Space", "test", now, now));
        UUID definitionId = UUID.randomUUID();
        definitions.save(new CapabilityDefinition(definitionId, "rollback_soil", "Rollback Soil", null,
                CapabilityDirection.READ, CapabilityValueType.NUMBER, "PERCENT", List.of("PERCENT"),
                BigDecimal.ZERO, new BigDecimal("100"), List.of(), List.of(), null, Map.of(), Map.of()));
        var template = templates.save(new DeviceTemplate(UUID.randomUUID(), "rollback_probe", "Rollback Probe",
                null, "NEXUS", "SOIL", ProtocolType.MQTT, true, Map.of()));
        templateCapabilities.save(new DeviceTemplateCapability(UUID.randomUUID(), template,
                definitions.findById(definitionId).orElseThrow(), true, "PERCENT", 1, null, Map.of()));
        String serial = "ROLLBACK-" + UUID.randomUUID();
        String requestKey = "rollback-" + UUID.randomUUID();
        String request = json.writeValueAsString(Map.of("requestKey", requestKey,
                "templateId", template.getId(), "spaceId", space.getId(), "name", "Rollback Probe",
                "serialNumber", serial, "mode", "SIMULATED",
                "bindings", Map.of("rollback_soil", Map.of("unit", "CELSIUS"))));
        assertEquals(400, mvc.perform(post("/api/v1/device-onboarding")
                .contentType(MediaType.APPLICATION_JSON).content(request)).andReturn().getResponse().getStatus());
        assertFalse(devices.existsBySerialNumber(serial));
        assertTrue(sessions.findByRequestKey(requestKey).isEmpty());

        String valid = json.writeValueAsString(Map.of("requestKey", requestKey,
                "templateId", template.getId(), "spaceId", space.getId(), "name", "Rollback Probe",
                "serialNumber", serial, "mode", "SIMULATED"));
        JsonNode created = json.readTree(mvc.perform(post("/api/v1/device-onboarding")
                .contentType(MediaType.APPLICATION_JSON).content(valid)).andReturn().getResponse().getContentAsString());
        UUID deviceId = UUID.fromString(created.path("deviceId").asText());
        assertEquals(1, bindings.findByDeviceId(deviceId).size());
        String conflicting = json.writeValueAsString(Map.of("requestKey", "conflict-" + UUID.randomUUID(),
                "templateId", template.getId(), "spaceId", space.getId(), "name", "Different Probe",
                "serialNumber", serial, "mode", "SIMULATED"));
        assertEquals(409, mvc.perform(post("/api/v1/device-onboarding")
                .contentType(MediaType.APPLICATION_JSON).content(conflicting)).andReturn().getResponse().getStatus());
        assertEquals(1, devices.findAll().stream().filter(d -> serial.equals(d.getSerialNumber())).count());
    }

    @Test @Timeout(45)
    void bindingMutationAndContractUseNarrowedConstraintsAndProtectActiveRule() throws Exception {
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Binding Site", "test", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "Binding Building", "test", now, now));
        Space space = spaces.save(new Space(UUID.randomUUID(), building, "Binding Space", "test", now, now));
        var servo = definitions.findByKey("servo").orElseThrow();
        var template = templates.save(new DeviceTemplate(UUID.randomUUID(), "servo_contract_probe", "Servo Contract Probe",
                null, "NEXUS", "SERVO", ProtocolType.MQTT, true, Map.of()));
        templateCapabilities.save(new DeviceTemplateCapability(UUID.randomUUID(), template, servo,
                true, "DEGREES", 1, null, Map.of()));
        String provision = json.writeValueAsString(Map.of("requestKey", "binding-" + UUID.randomUUID(),
                "templateId", template.getId(), "spaceId", space.getId(), "name", "Servo Probe",
                "serialNumber", "SERVO-" + UUID.randomUUID(), "mode", "SIMULATED",
                "bindings", Map.of("servo", Map.of("configuration", Map.of("minimum", 10, "maximum", 160)))));
        JsonNode created = json.readTree(mvc.perform(post("/api/v1/device-onboarding")
                .contentType(MediaType.APPLICATION_JSON).content(provision)).andReturn().getResponse().getContentAsString());
        UUID deviceId = UUID.fromString(created.path("deviceId").asText());
        UUID bindingId = bindings.findByDeviceId(deviceId).getFirst().getId();
        JsonNode contract = json.readTree(mvc.perform(get("/api/v1/devices/{id}/contract", deviceId))
                .andReturn().getResponse().getContentAsString());
        assertEquals(10, contract.path("capabilities").get(0).path("minimum").asInt());
        assertEquals(160, contract.path("capabilities").get(0).path("maximum").asInt());
        assertEquals("nexus/devices/" + deviceId + "/commands", contract.path("topics").path("commands").asText());

        String narrower = json.writeValueAsString(Map.of("capabilityDefinitionId", servo.getId(),
                "configuration", Map.of("minimum", 20, "maximum", 150)));
        assertEquals(200, mvc.perform(put("/api/v1/devices/{id}/capability-bindings/{bindingId}", deviceId, bindingId)
                .contentType(MediaType.APPLICATION_JSON).content(narrower)).andReturn().getResponse().getStatus());
        String expansion = json.writeValueAsString(Map.of("capabilityDefinitionId", servo.getId(),
                "configuration", Map.of("maximum", 170)));
        assertEquals(400, mvc.perform(put("/api/v1/devices/{id}/capability-bindings/{bindingId}", deviceId, bindingId)
                .contentType(MediaType.APPLICATION_JSON).content(expansion)).andReturn().getResponse().getStatus());
        JsonNode updated = json.readTree(mvc.perform(get("/api/v1/devices/{id}/contract", deviceId))
                .andReturn().getResponse().getContentAsString());
        assertEquals(20, updated.path("capabilities").get(0).path("minimum").asInt());
        assertEquals(150, updated.path("capabilities").get(0).path("maximum").asInt());

        UUID ruleId = UUID.randomUUID();
        try {
            rules.register(new Rule(ruleId, "Servo binding guard", true, 1,
                    List.of(new RuleCondition(RuleField.EVENT_TYPE, RuleOperator.EQ, "TWIN_UPDATED"),
                            new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, deviceId.toString())),
                    List.of(new RuleAction("SEND_COMMAND", Map.of("deviceId", deviceId.toString(),
                            "capabilityKey", "servo", "command", "SET_STATE", "value", 90))), Map.of()));
            String disable = json.writeValueAsString(Map.of("enabled", false));
            assertEquals(400, mvc.perform(put("/api/v1/devices/{id}/capability-bindings/{bindingId}", deviceId, bindingId)
                    .contentType(MediaType.APPLICATION_JSON).content(disable)).andReturn().getResponse().getStatus());
        } finally {
            rules.unregister(ruleId);
        }

        UUID stringId = UUID.randomUUID();
        definitions.save(new CapabilityDefinition(stringId, "firmware_label", "Firmware Label", null,
                CapabilityDirection.READ, CapabilityValueType.STRING, null, List.of(), null, null,
                List.of(), List.of(), null, Map.of(), Map.of()));
        String add = json.writeValueAsString(Map.of("capabilityDefinitionId", stringId,
                "externalIdentifiers", Map.of("telemetryKey", "fw_label")));
        assertEquals(201, mvc.perform(post("/api/v1/devices/{id}/capability-bindings", deviceId)
                .contentType(MediaType.APPLICATION_JSON).content(add)).andReturn().getResponse().getStatus());
        assertEquals(2, bindings.findByDeviceId(deviceId).size());
    }

    @Test @Timeout(45)
    void oneTemplateProducesIndependentTypedContractsForMultipleDevices() throws Exception {
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Types Site", "test", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "Types Building", "test", now, now));
        Space space = spaces.save(new Space(UUID.randomUUID(), building, "Types Space", "test", now, now));
        UUID enumId = UUID.randomUUID();
        definitions.save(new CapabilityDefinition(enumId, "irrigation_mode", "Irrigation Mode", null,
                CapabilityDirection.READ, CapabilityValueType.ENUM, null, List.of(), null, null,
                List.of("AUTO", "MANUAL"), List.of(), null, Map.of(), Map.of()));
        UUID stringId = UUID.randomUUID();
        definitions.save(new CapabilityDefinition(stringId, "probe_label", "Probe Label", null,
                CapabilityDirection.READ, CapabilityValueType.STRING, null, List.of(), null, null,
                List.of(), List.of(), null, Map.of(), Map.of()));
        var template = templates.save(new DeviceTemplate(UUID.randomUUID(), "typed_contract_probe", "Typed Contract Probe",
                null, "NEXUS", "TYPED", ProtocolType.MQTT, true, Map.of()));
        var keys = List.of("servo", "motion", "relay", "led", "irrigation_mode", "probe_label");
        int order = 0;
        for (String key : keys) {
            var definition = definitions.findByKey(key).orElseThrow();
            templateCapabilities.save(new DeviceTemplateCapability(UUID.randomUUID(), template, definition,
                    true, definition.getUnit(), ++order, null,
                    key.equals("relay") ? Map.of("channels", List.of(1,2,3,4)) : Map.of()));
        }
        String first = json.writeValueAsString(Map.of("requestKey", "typed-a-" + UUID.randomUUID(),
                "templateId", template.getId(), "spaceId", space.getId(), "name", "Typed A",
                "serialNumber", "TYPED-A-" + UUID.randomUUID(), "mode", "SIMULATED",
                "bindings", Map.of("servo", Map.of("configuration", Map.of("minimum", 10, "maximum", 160)),
                        "relay", Map.of("configuration", Map.of("channels", List.of(1,2))),
                        "irrigation_mode", Map.of("configuration", Map.of("enumValues", List.of("AUTO"))))));
        String second = json.writeValueAsString(Map.of("requestKey", "typed-b-" + UUID.randomUUID(),
                "templateId", template.getId(), "spaceId", space.getId(), "name", "Typed B",
                "serialNumber", "TYPED-B-" + UUID.randomUUID(), "mode", "SIMULATED"));
        UUID firstId = UUID.fromString(json.readTree(mvc.perform(post("/api/v1/device-onboarding")
                .contentType(MediaType.APPLICATION_JSON).content(first)).andReturn().getResponse().getContentAsString())
                .path("deviceId").asText());
        UUID secondId = UUID.fromString(json.readTree(mvc.perform(post("/api/v1/device-onboarding")
                .contentType(MediaType.APPLICATION_JSON).content(second)).andReturn().getResponse().getContentAsString())
                .path("deviceId").asText());
        assertNotEquals(firstId, secondId);
        assertEquals(6, bindings.findByDeviceId(firstId).size());
        assertEquals(6, bindings.findByDeviceId(secondId).size());
        JsonNode firstContract = json.readTree(mvc.perform(get("/api/v1/devices/{id}/contract", firstId))
                .andReturn().getResponse().getContentAsString());
        JsonNode secondContract = json.readTree(mvc.perform(get("/api/v1/devices/{id}/contract", secondId))
                .andReturn().getResponse().getContentAsString());
        assertEquals("1.0", firstContract.path("contractVersion").asText());
        assertEquals(template.getId().toString(), firstContract.path("templateId").asText());
        assertEquals(6, firstContract.path("capabilities").size());
        assertEquals("NUMBER", capability(firstContract, "servo").path("valueType").asText());
        assertEquals(10, capability(firstContract, "servo").path("minimum").asInt());
        assertEquals(160, capability(firstContract, "servo").path("maximum").asInt());
        assertEquals(0, capability(secondContract, "servo").path("minimum").asInt());
        assertEquals(180, capability(secondContract, "servo").path("maximum").asInt());
        assertEquals("BOOLEAN", capability(firstContract, "motion").path("valueType").asText());
        assertEquals("OBJECT", capability(firstContract, "relay").path("valueType").asText());
        assertEquals(2, capability(firstContract, "relay").path("effectiveConstraints").path("channels").size());
        assertEquals(4, capability(secondContract, "relay").path("effectiveConstraints").path("channels").size());
        assertEquals("WRITE", capability(firstContract, "led").path("direction").asText());
        assertEquals("SET_STATE", capability(firstContract, "led").path("supportedCommands").get(0).asText());
        assertEquals("LED", capability(firstContract, "led").path("externalCommandIdentifier").asText());
        assertEquals("ENUM", capability(firstContract, "irrigation_mode").path("valueType").asText());
        assertEquals(1, capability(firstContract, "irrigation_mode").path("enumValues").size());
        assertEquals(2, capability(secondContract, "irrigation_mode").path("enumValues").size());
        assertEquals("STRING", capability(firstContract, "probe_label").path("valueType").asText());
    }

    private static JsonNode capability(JsonNode contract, String key) {
        for (JsonNode candidate : contract.path("capabilities"))
            if (key.equals(candidate.path("key").asText())) return candidate;
        throw new AssertionError("Missing capability " + key);
    }

    private void awaitState(UUID id, OnboardingState expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (sessions.findById(id).orElseThrow().getState() == expected) return;
            Thread.sleep(100);
        }
        fail("Onboarding session did not reach " + expected);
    }

    private void awaitAck(UUID commandId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            var action = actions.findById(commandId);
            if (action.isPresent() && action.get().getAckStatus() == AcknowledgementStatus.ACCEPTED) return;
            Thread.sleep(100);
        }
        fail("Onboarding command ACK was not persisted");
    }

    private static void publish(MqttClient client, String topic, String payload) throws Exception {
        MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
        message.setQos(1);
        client.publish(topic, message);
    }

    private static String brokerUrl() {
        return "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883);
    }
}
