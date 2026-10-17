package com.nexus.telemetry.inbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.device.command.DeviceCommandAckMessageHandler;
import com.nexus.device.connectivity.DeviceStatusMessageHandler;
import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceMode;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceStatus;
import com.nexus.device.domain.DeviceType;
import com.nexus.infrastructure.mqtt.MqttReconnectController;
import com.nexus.infrastructure.mqtt.TelemetryMQTTListener;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import com.nexus.telemetry.application.TelemetryService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validator;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Telemetry redelivery on a REAL Mosquitto broker (R17; ledger P4-075 and P4-081). A first "backend" process receives a QoS 1 telemetry
 * message with a messageId, stores it, and dies before it acknowledges to the broker. A second process with the same client id and a
 * persistent session (a restart, with fresh in-memory client state) receives the very same message again from the broker, and the
 * inbox suppresses it: one stored row, one Twin update, and the broker queue is finally drained. Both processes use the production
 * {@link TelemetryMQTTListener} and the real {@link TelemetryService}. The application's own MQTT client is pointed at topics nothing
 * publishes on, so only the two processes under test consume the message.
 */
@SpringBootTest
@Testcontainers
class TelemetryInboxMosquittoIntegrationTest {

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
        registry.add("mqtt.client.id", () -> "p45-app-" + UUID.randomUUID());
        registry.add("mqtt.topic.telemetry", () -> "nexus/p45-unused/+/telemetry");
        registry.add("mqtt.topic.status", () -> "nexus/p45-unused/+/status");
        registry.add("mqtt.topic.command-ack", () -> "nexus/p45-unused/+/commands/ack");
    }

    @Autowired TelemetryService telemetry;
    @Autowired ObjectMapper mapper;
    @Autowired Validator validator;
    @Autowired MeterRegistry appMeters;
    @Autowired JdbcTemplate jdbc;
    @Autowired SiteRepository sites;
    @Autowired BuildingRepository buildings;
    @Autowired SpaceRepository spaces;
    @Autowired DeviceRepository devices;

    private final List<MqttClient> clients = new ArrayList<>();

    @AfterEach
    void closeClients() {
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
        Device d = new Device(UUID.randomUUID(), space, "M", DeviceType.CUSTOM, "NEXUS", "TEST", "P45M-" + UUID.randomUUID(), null, DeviceMode.SIMULATED);
        d.update("M", DeviceStatus.ACTIVE, null);
        return devices.save(d).getId();
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

    private double duplicates() {
        var counter = appMeters.find("nexus.telemetry.duplicates").tag("source", "MQTT").counter();
        return counter == null ? 0 : counter.count();
    }

    /** A "backend process": the production listener on its own client. {@code acknowledge=false} models dying before the acknowledgement. */
    private MqttClient process(String clientId, boolean acknowledge, AtomicInteger arrivals, AtomicInteger acknowledgements) throws Exception {
        MqttClient client = new MqttClient(brokerUrl(), clientId, new MemoryPersistence()) {
            @Override public void messageArrivedComplete(int messageId, int qos) throws MqttException {
                acknowledgements.incrementAndGet();
                if (acknowledge) super.messageArrivedComplete(messageId, qos);
            }
        };
        client.setManualAcks(true);
        clients.add(client);
        AtomicBoolean subscribed = new AtomicBoolean(false);
        TelemetryMQTTListener listener = new TelemetryMQTTListener(client, telemetry, mapper, validator, "nexus/devices/+/telemetry",
                mock(MqttReconnectController.class), new SimpleMeterRegistry(), "nexus/devices/+/status", mock(DeviceStatusMessageHandler.class),
                "nexus/devices/+/commands/ack", mock(DeviceCommandAckMessageHandler.class)) {
            @Override public void messageArrived(String topic, org.eclipse.paho.client.mqttv3.MqttMessage message) {
                arrivals.incrementAndGet();
                super.messageArrived(topic, message);
            }
            @Override public void connectComplete(boolean reconnect, String serverUri) {
                super.connectComplete(reconnect, serverUri);   // subscribes to the three topics synchronously
                subscribed.set(true);
            }
        };
        listener.init();   // sets the callback; the subscription is made when the connection completes
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(false);
        options.setAutomaticReconnect(false);
        client.connect(options);
        await(subscribed::get, "the process to finish subscribing");
        return client;
    }

    private void publish(UUID device, String payload) throws MqttException {
        MqttClient publisher = new MqttClient(brokerUrl(), "p45-publisher-" + UUID.randomUUID(), new MemoryPersistence());
        clients.add(publisher);
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        publisher.connect(options);
        org.eclipse.paho.client.mqttv3.MqttMessage message = new org.eclipse.paho.client.mqttv3.MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
        message.setQos(1);
        publisher.publish("nexus/devices/" + device + "/telemetry", message);
    }

    private static String payload(UUID device, String messageId, double value) {
        return "{\"deviceId\":\"" + device + "\",\"timestamp\":\"" + Instant.parse("2026-10-17T12:00:00Z") + "\",\"sensorType\":\"TEMPERATURE\",\"value\":" + value
                + ",\"unit\":\"CELSIUS\",\"messageId\":\"" + messageId + "\"}";
    }

    @Test
    void aMessageProcessedButNeverAcknowledgedIsRedeliveredAfterARestartAndSuppressed() throws Exception {
        UUID device = activeDevice();
        String clientId = "p45-backend-" + UUID.randomUUID();
        double duplicatesBefore = duplicates();

        // process 1: receives and stores the message, then dies before acknowledging it to the broker
        AtomicInteger arrivals1 = new AtomicInteger(), acks1 = new AtomicInteger();
        MqttClient first = process(clientId, false, arrivals1, acks1);
        publish(device, payload(device, "redeliver-1", 21.5));
        await(() -> count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", device) == 1, "the first delivery to be stored");
        await(() -> acks1.get() == 1, "the first process to reach (and skip) its acknowledgement");
        first.disconnectForcibly(0, 0, false);   // a crash: no DISCONNECT, no PUBACK; the persistent session keeps the message queued at the broker

        // process 2: same client id and persistent session, fresh client state (a restart); the broker redelivers the unacknowledged message
        AtomicInteger arrivals2 = new AtomicInteger(), acks2 = new AtomicInteger();
        MqttClient second = process(clientId, true, arrivals2, acks2);
        await(() -> arrivals2.get() >= 1, "the broker to redeliver the unacknowledged message");
        await(() -> acks2.get() >= 1, "the restarted process to acknowledge it");

        assertEquals(1, arrivals1.get(), "delivered once to the first process");
        assertTrue(arrivals2.get() >= 1, "and redelivered by the broker after the restart");
        assertEquals(1, count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", device), "the redelivery stored nothing");
        assertEquals(1, count("SELECT COUNT(*) FROM inbound_message_dedupe WHERE device_id = ?", device));
        assertEquals(1, count("SELECT COUNT(*) FROM digital_twins WHERE device_id = ?", device));
        assertEquals(1, count("SELECT COUNT(*) FROM twin_state_transitions WHERE device_id = ?", device), "one Twin update for one logical message");
        assertTrue(duplicates() - duplicatesBefore >= 1, "the redelivery was counted as a duplicate");

        // acknowledged now: a further restart receives nothing more
        second.disconnectForcibly(0, 0, false);
        AtomicInteger arrivals3 = new AtomicInteger(), acks3 = new AtomicInteger();
        process(clientId, true, arrivals3, acks3);
        Thread.sleep(1500);
        assertEquals(0, arrivals3.get(), "the broker queue is drained once the duplicate was acknowledged");
        assertEquals(1, count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", device));
    }

    @Test
    void theSameMessageIdPublishedTwiceByTheSenderIsStoredOnce() throws Exception {
        UUID device = activeDevice();
        AtomicInteger arrivals = new AtomicInteger(), acks = new AtomicInteger();
        process("p45-backend-" + UUID.randomUUID(), true, arrivals, acks);
        publish(device, payload(device, "sent-twice", 21.5));
        publish(device, payload(device, "sent-twice", 21.5));
        await(() -> acks.get() >= 2, "both deliveries to be acknowledged");
        assertEquals(1, count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", device));
        assertEquals(2, arrivals.get());
        // a poison message (the rehearsal defect) is acknowledged and dropped, so it neither loops nor blocks the next message
        publish(device, "{\"deviceId\":\"" + device + "\",\"timestamp\":\"2026-10-17T12:00:00Z\",\"sensorType\":\"MOTION\",\"value\":1.0,\"unit\":\"BOOLEAN\",\"messageId\":\"poison-1\"}");
        publish(device, payload(device, "after-poison", 22.5));
        await(() -> acks.get() >= 4, "the poison message to be acknowledged and the next message to be processed");
        assertEquals(2, count("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", device));
        assertEquals(0, count("SELECT COUNT(*) FROM inbound_message_dedupe WHERE device_id = ? AND message_id = 'poison-1'", device));
        assertEquals(4, arrivals.get(), "the poison message was delivered exactly once, not redelivered in a loop");
    }
}
