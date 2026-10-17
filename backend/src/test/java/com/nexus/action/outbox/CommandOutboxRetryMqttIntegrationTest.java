package com.nexus.action.outbox;

import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.action.domain.ExecutionStatus;
import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.device.capability.CapabilityDirection;
import com.nexus.device.capability.CapabilityType;
import com.nexus.device.capability.DeviceCapability;
import com.nexus.device.capability.DeviceCapabilityRepository;
import com.nexus.device.command.DeviceCommandAckMessageHandler;
import com.nexus.device.command.DeviceCommandService;
import com.nexus.device.command.api.dto.CreateDeviceCommandRequest;
import com.nexus.device.connectivity.DeviceConnectivityRegistry;
import com.nexus.device.connectivity.DeviceConnectivityStatus;
import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceMode;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceType;
import com.nexus.infrastructure.mqtt.PahoBrokerCommandPublisher;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Retries on the REAL wire (flag ON, the real bounded Paho publisher, a real Mosquitto broker, a disposable PostgreSQL).
 *
 * The publisher under test is the production {@link PahoBrokerCommandPublisher}. A thin decorator makes the real client genuinely
 * unavailable before the hand-over on chosen attempts (it disconnects the real client, so the real publisher classifies the attempt
 * NOT_SENT by itself) and reconnects it for later ones. The decorator never touches the payload, the classification or the timing,
 * and records the bytes handed to the real publisher on every attempt. An independent subscriber captures what actually reaches the
 * broker.
 *
 * Ledger: P4-026, P4-028, P4-030, P4-035, P4-045 (wire layer).
 */
@SpringBootTest(properties = {"nexus.command.outbox.dispatcher-enabled=true", "nexus.command.outbox.enabled=true", "nexus.command.outbox.poll-interval=PT0.25S"})
@Testcontainers
@Import(CommandOutboxRetryMqttIntegrationTest.Flaky.class)
class CommandOutboxRetryMqttIntegrationTest {

    /** Decorates the REAL publisher; see the class comment. */
    static final class FlakyPublisher implements BrokerCommandPublisher {
        private final PahoBrokerCommandPublisher real;
        private final MqttClient client;
        private final MqttConnectOptions options;
        volatile int failFirst;
        final AtomicInteger calls = new AtomicInteger();
        final List<byte[]> handedOver = Collections.synchronizedList(new ArrayList<>());

        FlakyPublisher(PahoBrokerCommandPublisher real, MqttClient client, MqttConnectOptions options) {
            this.real = real;
            this.client = client;
            this.options = options;
        }

        void reset(int failFirst) {
            this.failFirst = failFirst;
            calls.set(0);
            handedOver.clear();
        }

        @Override
        public Attempt publish(String topic, byte[] payload, Duration timeout) {
            handedOver.add(payload.clone());
            try {
                if (calls.incrementAndGet() <= failFirst) {
                    if (client.isConnected()) client.disconnect();      // the real client is now really not connected
                } else if (!client.isConnected()) {
                    client.connect(options);                           // the broker is reachable again
                }
            } catch (Exception e) {
                throw new IllegalStateException("test setup: could not change the connection state", e);
            }
            return real.publish(topic, payload, timeout);
        }
    }

    @TestConfiguration
    static class Flaky {
        @Bean
        @Primary
        FlakyPublisher flakyPublisher(PahoBrokerCommandPublisher real, MqttClient client, MqttConnectOptions options) {
            return new FlakyPublisher(real, client, options);
        }
    }

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
        registry.add("mqtt.client.id", () -> "nexus-outbox-retry-" + UUID.randomUUID());
    }

    @Autowired DeviceCommandService commands;
    @Autowired DeviceCommandAckMessageHandler ackHandler;
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
    @Autowired FlakyPublisher flaky;
    @Autowired CommandOutboxDispatcher dispatcher;

    private UUID deviceId;
    private MqttClient observer;
    private final BlockingQueue<MqttMessage> received = new ArrayBlockingQueue<>(20);

    @BeforeEach
    void setUp() throws Exception {
        assertNotNull(dispatcher, "the real dispatcher runs in this context (dispatcher-enabled=true, outbox enabled)");
        flaky.reset(0);
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Site-" + UUID.randomUUID(), "d", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "B", "d", now, now));
        Space space = spaces.save(new Space(UUID.randomUUID(), building, "S", "d", now, now));
        Device device = devices.save(new Device(UUID.randomUUID(), space, "ESP-Retry", DeviceType.CUSTOM, "Acme", "T1",
                "SN-RETRY-" + UUID.randomUUID(), "d", DeviceMode.SIMULATED));
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
        flaky.reset(0);
        if (!backendClient.isConnected()) backendClient.connect(connectOptions);
        if (observer != null && observer.isConnected()) observer.disconnect();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE state IN ('QUEUED','IN_FLIGHT','RETRY_WAIT')", Integer.class) > 0
                && System.nanoTime() < end) Thread.sleep(50);
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(25);
        }
    }

    private UUID dispatch(boolean value) {
        return commands.dispatchCommand(deviceId, new CreateDeviceCommandRequest(CapabilityType.LED, "SET_STATE", value)).requestId();
    }

    private List<String> facts(UUID id) {
        return jdbc.queryForList("SELECT fact_type FROM command_state_history WHERE command_id = ? ORDER BY sequence", String.class, id);
    }

    @Test
    @Timeout(120)
    void twoRealNonPublicationsThenARecoveredBrokerPublishTheIdenticalStoredBytesExactlyOnceOnTheWire() throws Exception {
        flaky.reset(2);
        UUID id = dispatch(true);
        await(() -> outbox.findById(id).orElseThrow().getState() == OutboxState.PUBLISHED, "published after two non-publications");

        CommandOutboxEntity row = outbox.findById(id).orElseThrow();
        byte[] stored = row.getPayloadText().getBytes(StandardCharsets.UTF_8);
        assertEquals(3, row.getAttemptCount());
        assertEquals(3, flaky.handedOver.size());
        for (byte[] attempt : flaky.handedOver) assertArrayEquals(stored, attempt, "every attempt handed the real publisher the identical stored bytes");

        MqttMessage message = received.poll(20, TimeUnit.SECONDS);
        assertNotNull(message, "the recovered publication reached the real broker");
        assertArrayEquals(stored, message.getPayload(), "the bytes captured from the broker equal the bytes stored at acceptance (byte for byte)");
        assertEquals(1, message.getQos());
        assertNull(received.poll(2, TimeUnit.SECONDS), "the two non-publications put NOTHING on the wire: exactly one message in total");
        assertEquals(row.getPayloadEpochSeconds(), com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readTree(message.getPayload()).get("timestamp").asLong(),
                "the wire timestamp is the acceptance-time one, not refreshed by the retries");
        assertEquals(ExecutionStatus.SUCCESS, actions.findById(id).orElseThrow().getStatus());
        assertEquals(List.of("ACCEPTED", "QUEUED", "ATTEMPT_STARTED", "ATTEMPT_FAILED_TRANSIENT", "ATTEMPT_STARTED", "ATTEMPT_FAILED_TRANSIENT",
                "ATTEMPT_STARTED", "PUBLISHED"), facts(id));
    }

    @Test
    @Timeout(120)
    void aBrokerThatStaysUnreachableExhaustsTheBudgetFailsHonestlyAndNothingEverReachesTheWire() throws Exception {
        flaky.reset(99);
        UUID id = dispatch(true);
        await(() -> outbox.findById(id).orElseThrow().getState() == OutboxState.DEAD_LETTER, "retry exhaustion");

        CommandOutboxEntity row = outbox.findById(id).orElseThrow();
        assertEquals(OutboxTerminalReason.RETRY_EXHAUSTED, row.getTerminalReason());
        assertEquals(3, row.getAttemptCount(), "exactly the attempt budget");
        assertEquals(3, flaky.calls.get());
        assertEquals(ExecutionStatus.FAILURE, actions.findById(id).orElseThrow().getStatus());
        assertTrue(actions.findById(id).orElseThrow().getMessage().contains("not sent"));
        assertNull(received.poll(2, TimeUnit.SECONDS), "a command that was never handed to the broker is never on the wire");
        Thread.sleep(1500);
        assertEquals(3, flaky.calls.get(), "a dead letter is never retried by any job");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM current_desired_twin_state WHERE device_id = ?", Integer.class, deviceId),
                "the desired state is retained, never cleared automatically");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM command_state_history WHERE command_id = ? AND fact_type = 'DEAD_LETTERED'", Integer.class, id));
    }

    @Test
    @Timeout(120)
    void aDuplicateBrokerMessageIsVisibleIdenticalAndYieldsOneAckFactAndOneLifecycle() throws Exception {
        UUID id = dispatch(true);
        await(() -> outbox.findById(id).orElseThrow().getState() == OutboxState.PUBLISHED, "published");
        MqttMessage first = received.poll(20, TimeUnit.SECONDS);
        assertNotNull(first);
        // model an MQTT QoS 1 redelivery: the broker puts the same bytes on the wire a second time (nothing in NEXUS republishes an accepted command)
        byte[] stored = outbox.findById(id).orElseThrow().getPayloadText().getBytes(StandardCharsets.UTF_8);
        assertEquals(BrokerCommandPublisher.Result.CONFIRMED, flaky.publish("nexus/devices/" + deviceId + "/commands", stored, Duration.ofSeconds(3)).result());
        MqttMessage second = received.poll(20, TimeUnit.SECONDS);
        assertNotNull(second);
        assertArrayEquals(first.getPayload(), second.getPayload(), "the duplicate is byte-identical, hence recognisable by commandId");

        String ack = "{\"commandId\":\"" + id + "\",\"status\":\"ACCEPTED\",\"timestamp\":" + Instant.now().getEpochSecond() + "}";
        ackHandler.handle("nexus/devices/" + deviceId + "/commands/ack", ack.getBytes(StandardCharsets.UTF_8));
        ackHandler.handle("nexus/devices/" + deviceId + "/commands/ack", ack.getBytes(StandardCharsets.UTF_8));
        assertEquals("ACCEPTED", jdbc.queryForObject("SELECT ack_status FROM action_history WHERE request_id = ?", String.class, id),
                "the first ACK is the only ACK fact");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM action_history WHERE request_id = ?", Integer.class, id), "one lifecycle");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM command_state_history WHERE command_id = ? AND fact_type = 'PUBLISHED'", Integer.class, id),
                "the delivery record shows one publication; the duplicate is not a second delivery fact");
    }
}
