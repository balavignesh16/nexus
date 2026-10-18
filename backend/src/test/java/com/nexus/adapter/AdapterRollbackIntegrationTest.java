package com.nexus.adapter;

import com.nexus.adapter.core.AdapterRegistry;
import com.nexus.adapter.core.MqttTransportEligibility;
import com.nexus.adapter.http.HttpWebhookAdapter;
import com.nexus.adapter.simulator.SimulatorAdapter;
import com.nexus.adapter.spi.TransportEligibility;
import com.nexus.adapter.spi.TransportKind;
import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceMode;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceStatus;
import com.nexus.device.domain.DeviceType;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ledger P5-118: disabling the adapters is the rollback. The application is started with NONE of the Phase 5 enable properties (the default), on a
 * database that has V41 applied and holds an HTTP-assigned device, a credential row and a SIMULATOR-assigned device. The V41 columns and tables are
 * there and are ignored: no HTTP route, no HTTP or simulator adapter bean, no assignment to a transport nothing serves, and a legacy MQTT device
 * works exactly as it did before V41, command eligibility included.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AdapterRollbackIntegrationTest {
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
        registry.add("mqtt.client.id", () -> "p58-app-" + UUID.randomUUID());
        // deliberately NO nexus.adapter.http.enabled and NO nexus.adapter.simulator.enabled
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;
    @Autowired AdapterRegistry registry;
    @Autowired TransportEligibility eligibility;
    @Autowired SiteRepository sites;
    @Autowired BuildingRepository buildings;
    @Autowired SpaceRepository spaces;
    @Autowired DeviceRepository devices;

    private final List<MqttClient> clients = new ArrayList<>();

    @AfterEach
    void close() {
        for (MqttClient c : clients) {
            try { if (c.isConnected()) c.disconnectForcibly(0, 0, false); c.close(true); } catch (MqttException ignored) { }
        }
    }

    private UUID device() {
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Rollback", "test", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "B", "test", now, now));
        Space space = spaces.save(new Space(UUID.randomUUID(), building, "S", "test", now, now));
        Device d = new Device(UUID.randomUUID(), space, "M", DeviceType.CUSTOM, "NEXUS", "TEST", "P58-" + UUID.randomUUID(), null, DeviceMode.SIMULATED);
        d.update("M", DeviceStatus.ACTIVE, null);
        return devices.save(d).getId();
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(25);
        }
    }

    @Test
    void p5_118_withTheAdaptersDisabledTheApplicationIgnoresTheNewColumnsAndBehavesAsBeforeV41() throws Exception {
        // the schema is the V41 schema, with data in the new columns and tables
        UUID httpAssigned = device();
        jdbc.update("UPDATE devices SET transport = 'HTTP_WEBHOOK' WHERE id = ?", httpAssigned);
        UUID credentialDevice = device();
        jdbc.update("INSERT INTO device_ingestion_credentials(credential_id, device_id, lookup_id, secret_hash, hash_algorithm, status, created_at) VALUES (?, ?, ?, ?, 'HMAC-SHA256', 'ACTIVE', now())",
                UUID.randomUUID(), credentialDevice, "a".repeat(24), new byte[32]);
        assertEquals(1, count("SELECT COUNT(*) FROM device_ingestion_credentials WHERE device_id = ?", credentialDevice));

        // only the MQTT adapter exists: no HTTP adapter, no simulator adapter, no HTTP route, no credential-reading bean
        assertEquals(List.of("mqtt-json"), registry.adapters().stream().map(a -> a.id()).toList());
        assertEquals(0, context.getBeanNamesForType(HttpWebhookAdapter.class).length);
        assertEquals(0, context.getBeanNamesForType(SimulatorAdapter.class).length);
        assertEquals(0, context.getBeanNamesForType(com.nexus.adapter.http.CredentialStore.class).length);
        mvc.perform(post("/api/v1/device-adapter/devices/" + credentialDevice + "/telemetry").header("Authorization", "Bearer nxk1_" + "a".repeat(24) + "_" + "A".repeat(43))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());

        // a device is never assigned to a transport nothing serves; returning to MQTT is always allowed
        UUID movable = device();
        mvc.perform(put("/api/v1/devices/" + movable + "/transport").contentType(MediaType.APPLICATION_JSON).content("{\"transport\":\"HTTP_WEBHOOK\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("No adapter is registered for transport HTTP_WEBHOOK"));
        mvc.perform(put("/api/v1/devices/" + movable + "/transport").contentType(MediaType.APPLICATION_JSON).content("{\"transport\":\"MQTT_JSON\"}")).andExpect(status().isOk());
        assertEquals("MQTT_JSON", jdbc.queryForObject("SELECT transport FROM devices WHERE id = ?", String.class, movable));

        // a legacy device that was never assigned works over MQTT exactly as before V41: telemetry stored, twin created, offline eligibility wording unchanged
        UUID legacy = device();
        assertEquals("MQTT_JSON", jdbc.queryForObject("SELECT transport FROM devices WHERE id = ?", String.class, legacy), "the column default");
        MqttClient client = new MqttClient("tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883), "p58-" + UUID.randomUUID(), new MemoryPersistence());
        clients.add(client);
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        client.connect(options);
        MqttMessage message = new MqttMessage(("{\"deviceId\":\"" + legacy + "\",\"timestamp\":\"2026-10-17T12:00:00Z\",\"sensorType\":\"TEMPERATURE\",\"value\":21.5,\"unit\":\"CELSIUS\"}")
                .getBytes(StandardCharsets.UTF_8));
        message.setQos(1);
        client.publish("nexus/devices/" + legacy + "/telemetry", message);
        await(() -> count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", legacy) == 1, "the legacy telemetry to be stored");
        assertEquals(1, count("SELECT COUNT(*) FROM digital_twins WHERE device_id = ?", legacy));
        var decision = eligibility.eligible(legacy, Instant.now());
        assertFalse(decision.eligible());
        assertEquals("DEVICE_OFFLINE", decision.code());
        assertEquals(MqttTransportEligibility.OFFLINE_MESSAGE, decision.message());

        // the HTTP-assigned device is simply not served by anything while the adapter is off: its MQTT message is refused, never processed, and nothing else changes
        MqttMessage crossed = new MqttMessage(("{\"deviceId\":\"" + httpAssigned + "\",\"timestamp\":\"2026-10-17T12:00:00Z\",\"sensorType\":\"TEMPERATURE\",\"value\":1.5,\"unit\":\"CELSIUS\"}")
                .getBytes(StandardCharsets.UTF_8));
        crossed.setQos(1);
        client.publish("nexus/devices/" + httpAssigned + "/telemetry", crossed);
        MqttMessage again = new MqttMessage(("{\"deviceId\":\"" + legacy + "\",\"timestamp\":\"2026-10-17T12:00:01Z\",\"sensorType\":\"TEMPERATURE\",\"value\":22.5,\"unit\":\"CELSIUS\",\"messageId\":\"after-1\"}")
                .getBytes(StandardCharsets.UTF_8));
        again.setQos(1);
        client.publish("nexus/devices/" + legacy + "/telemetry", again);
        await(() -> count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", legacy) == 2, "the legacy device to continue");
        assertEquals(0, count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", httpAssigned));
        assertTrue(count("SELECT COUNT(*) FROM flyway_schema_history WHERE version = '41' AND success") == 1, "V41 stays applied: the rollback is the property, not a schema change");
    }
}
