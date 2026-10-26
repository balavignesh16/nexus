package com.nexus.adapter;

import com.nexus.adapter.mqtt.MqttJsonAdapter;
import com.nexus.adapter.spi.Attempt;
import com.nexus.adapter.spi.CommandEnvelope;
import com.nexus.adapter.spi.EvidenceKind;
import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.device.connectivity.DeviceConnectivityRegistry;
import com.nexus.device.connectivity.DeviceConnectivityStatus;
import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceMode;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceStatus;
import com.nexus.device.domain.DeviceType;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The MQTT adapter on a REAL Mosquitto and PostgreSQL with the application's own listener, subscriptions and dispatcher wiring (ledger
 * P5-037, P5-042, P5-044, P5-047, P5-120, P5-124): legacy devices that were never assigned keep working over MQTT, nothing in a payload selects
 * a transport, retained online / last-will offline / acknowledgement messages reach the same handlers, the adapter delivers stored bytes
 * verbatim with BROKER_CONFIRMED evidence, and the diagnostics endpoint is read-only and secret-free.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MqttAdapterIntegrationTest {

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
        registry.add("mqtt.client.id", () -> "p53-app-" + UUID.randomUUID());
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MqttJsonAdapter adapter;
    @Autowired DeviceConnectivityRegistry connectivity;
    @Autowired MeterRegistry meters;
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

    private static String brokerUrl() {
        return "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883);
    }

    private UUID activeDevice() {
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Mqtt", "test", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "B", "test", now, now));
        Space space = spaces.save(new Space(UUID.randomUUID(), building, "S", "test", now, now));
        Device d = new Device(UUID.randomUUID(), space, "M", DeviceType.CUSTOM, "NEXUS", "TEST", "P53-" + UUID.randomUUID(), null, DeviceMode.SIMULATED);
        d.update("M", DeviceStatus.ACTIVE, null);
        return devices.save(d).getId();
    }

    private MqttClient connect(String id, MqttConnectOptions options) throws MqttException {
        MqttClient client = new MqttClient(brokerUrl(), id, new MemoryPersistence());
        clients.add(client);
        client.connect(options);
        return client;
    }

    private MqttClient connect() throws MqttException {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        return connect("p53-" + UUID.randomUUID(), options);
    }

    private void publish(String topic, String payload, boolean retained) throws MqttException {
        MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
        message.setQos(1);
        message.setRetained(retained);
        connect().publish(topic, message);
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(25);
        }
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private static String sample(UUID device, String extra) {
        return "{\"deviceId\":\"" + device + "\",\"timestamp\":\"2026-10-17T12:00:00Z\",\"sensorType\":\"TEMPERATURE\",\"value\":21.5,\"unit\":\"CELSIUS\"" + extra + "}";
    }

    private double rejections(String reason) {
        var counter = meters.find("nexus.telemetry.permanent.rejections").tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void p5_124_aLegacyDeviceThatWasNeverAssignedKeepsWorkingOverMqttExactlyAsBefore() throws Exception {
        UUID device = activeDevice();
        assertEquals("MQTT_JSON", jdbc.queryForObject("SELECT transport FROM devices WHERE id = ?", String.class, device), "V41 default for a device nobody assigned");
        publish("nexus/devices/" + device + "/telemetry", sample(device, ""), false);
        await(() -> count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", device) == 1, "the legacy telemetry to be stored");
        assertEquals(1, count("SELECT COUNT(*) FROM digital_twins WHERE device_id = ?", device));
    }

    @Test
    void p5_037_nothingInAPayloadSelectsATransportTheServerSideAssignmentDecides() throws Exception {
        UUID mqttDevice = activeDevice();
        // an MQTT device whose payload claims another transport and a contract version is still served as an MQTT device
        publish("nexus/devices/" + mqttDevice + "/telemetry", sample(mqttDevice, ",\"transport\":\"HTTP_WEBHOOK\",\"adapter\":\"http-webhook\",\"contractVersion\":\"1.0\""), false);
        await(() -> count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", mqttDevice) == 1, "the MQTT device's telemetry to be stored");

        UUID httpDevice = activeDevice();
        jdbc.update("UPDATE devices SET transport = 'HTTP_WEBHOOK' WHERE id = ?", httpDevice);
        double before = rejections("TRANSPORT_MISMATCH");
        publish("nexus/devices/" + httpDevice + "/telemetry", sample(httpDevice, ",\"transport\":\"MQTT_JSON\",\"contractVersion\":\"1.0\""), false);
        await(() -> rejections("TRANSPORT_MISMATCH") > before, "the MQTT message of an HTTP-assigned device to be refused");
        assertEquals(0, count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", httpDevice), "claiming MQTT in the payload does not make it so");
        // a later MQTT message of the first device is unaffected: nothing global changed
        publish("nexus/devices/" + mqttDevice + "/telemetry", sample(mqttDevice, ",\"messageId\":\"after-1\""), false);
        await(() -> count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", mqttDevice) == 2, "the next MQTT message to be stored");
    }

    @Test
    void p5_042_retainedOnlineLastWillOfflineAndAcknowledgementsReachTheSameHandlers() throws Exception {
        UUID device = activeDevice();
        String statusTopic = "nexus/devices/" + device + "/status";
        publish(statusTopic, "online", true);
        await(() -> connectivity.get(device).map(c -> c.status()).orElse(null) == DeviceConnectivityStatus.ONLINE, "the retained birth message to mark the device ONLINE");

        // a device client with a retained last will: an ungraceful loss publishes it
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setWill(statusTopic, "offline".getBytes(StandardCharsets.UTF_8), 1, true);
        MqttClient deviceClient = connect("p53-device-" + UUID.randomUUID(), options);
        deviceClient.disconnectForcibly(0, 0, false);
        await(() -> connectivity.get(device).map(c -> c.status()).orElse(null) == DeviceConnectivityStatus.OFFLINE, "the last will to mark the device OFFLINE");

        UUID command = UUID.randomUUID();
        Instant published = Instant.now().minusSeconds(5);
        jdbc.update("INSERT INTO action_history(request_id, match_id, device_id, action_type, status, parameters, timestamp, message, published_at, ack_deadline_at) "
                        + "VALUES (?, ?, ?, 'SEND_COMMAND', 'SUCCESS', '{}'::jsonb, ?, 'test', ?, ?)",
                command, UUID.randomUUID(), device, Timestamp.from(published), Timestamp.from(published), Timestamp.from(published.plusSeconds(60)));
        publish("nexus/devices/" + device + "/commands/ack", "{\"commandId\":\"" + command + "\",\"status\":\"ACCEPTED\",\"timestamp\":" + Instant.now().getEpochSecond() + "}", false);
        await(() -> "ACCEPTED".equals(jdbc.queryForObject("SELECT ack_status FROM action_history WHERE request_id = ?", String.class, command)),
                "the acknowledgement to be recorded");
    }

    @Test
    void p5_044_theAdapterPutsTheStoredBytesOnTheBrokerVerbatimAndStatesBrokerConfirmation() throws Exception {
        UUID device = UUID.randomUUID();
        MqttClient subscriber = connect();
        List<byte[]> received = new CopyOnWriteArrayList<>();
        subscriber.subscribe("nexus/devices/" + device + "/commands", 1, (topic, message) -> received.add(message.getPayload()));
        byte[] stored = ("{\"commandId\":\"" + UUID.randomUUID() + "\",\"capability\":\"LED\",\"command\":\"SET_STATE\",\"value\":true,\"timestamp\":1767225600}").getBytes(StandardCharsets.UTF_8);
        long confirmedBefore = adapter.diagnostics().get("egressConfirmed");
        Attempt attempt = adapter.deliver(new CommandEnvelope(UUID.randomUUID(), device, stored, "hash", 1767225600L, "c"), Duration.ofSeconds(10));
        assertEquals(Attempt.Outcome.CONFIRMED, attempt.outcome());
        assertEquals(EvidenceKind.BROKER_CONFIRMED, attempt.evidence());
        await(() -> !received.isEmpty(), "the broker to deliver the command to the subscriber");
        assertEquals(1, received.size());
        assertArrayEquals(stored, received.get(0), "byte for byte the stored payload");
        assertEquals(confirmedBefore + 1, adapter.diagnostics().get("egressConfirmed"));
    }

    @Test
    void p5_047_p5_120_theDiagnosticsEndpointShowsTheMqttAdapterRunningAndConnectedAndExposesNoSecretAndNoWrite() throws Exception {
        mvc.perform(get("/api/v1/adapters")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='mqtt-json')].running").value(true))
                .andExpect(jsonPath("$[?(@.id=='mqtt-json')].transport").value("MQTT_JSON"))
                .andExpect(jsonPath("$[?(@.id=='mqtt-json')].ingestOnly").value(false))
                .andExpect(jsonPath("$[?(@.id=='mqtt-json')].counters.brokerConnected").value(1))
                .andExpect(jsonPath("$[?(@.id=='mqtt-json')].capabilities").isNotEmpty());
        String body = mvc.perform(get("/api/v1/adapters")).andReturn().getResponse().getContentAsString().toLowerCase();
        for (String secret : new String[]{"password", "secret", "token", "credential", "apikey", "hash", "pepper", "authorization"}) assertFalse(body.contains(secret), secret);
        mvc.perform(post("/api/v1/adapters")).andExpect(status().isMethodNotAllowed());
        mvc.perform(put("/api/v1/adapters")).andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/api/v1/adapters")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/v1/adapters/mqtt-json/credentials")).andExpect(status().is4xxClientError());
        assertTrue(adapter.started(), "the registry's lifecycle started the adapter with the application");
        Optional<String> unused = Optional.empty();
        assertTrue(unused.isEmpty());
    }
}
