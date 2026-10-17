package com.nexus.action.outbox;

import com.nexus.action.domain.AcknowledgementStatus;
import com.nexus.action.domain.ActionHistoryRepository;
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
import com.nexus.rule.trigger.RuleSourceFacts;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import com.nexus.twin.application.VerificationService;
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
import org.springframework.boot.test.mock.mockito.SpyBean;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The P4.4 workers and the ACK path on the REAL transport: a real Mosquitto broker, the production bounded publisher, the production
 * {@code TelemetryMQTTListener} that receives the device ACKs, and the SCHEDULED {@link CommandAckTimeoutScanner} and
 * {@link LegacyDeliveryReconciler} actually running (the context sets lifecycle-scanners-enabled=true, and the test asserts the beans
 * are enabled so a silently disabled worker cannot make it pass). The ACK timeout is shortened to 2 s so the real deadline passes.
 * An independent client plays the device: it receives the command bytes and publishes the ACKs.
 */
@SpringBootTest(properties = {"nexus.command.outbox.dispatcher-enabled=true", "nexus.command.outbox.enabled=true",
        "nexus.command.lifecycle-scanners-enabled=true", "nexus.command.ack-timeout=PT2S", "nexus.command.ack-timeout-scan-interval=PT0.3S",
        "nexus.command.legacy-reconcile-interval=PT0.5S", "nexus.command.legacy-reconcile-grace=PT5M"})
@Testcontainers
class CommandLifecycleScannersLiveIntegrationTest {

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
        registry.add("mqtt.client.id", () -> "nexus-lifecycle-live-" + UUID.randomUUID());
    }

    @Autowired DeviceCommandService commands;
    @Autowired ActionHistoryRepository actions;
    @Autowired JdbcTemplate jdbc;
    @Autowired SiteRepository sites;
    @Autowired BuildingRepository buildings;
    @Autowired SpaceRepository spaces;
    @Autowired DeviceRepository devices;
    @Autowired DeviceCapabilityRepository capabilities;
    @Autowired DeviceConnectivityRegistry connectivity;
    @Autowired CommandAckTimeoutScanner timeoutScanner;
    @Autowired LegacyDeliveryReconciler reconciler;
    @Autowired CommandOutboxDispatcher dispatcher;
    @SpyBean RuleSourceFacts ruleFacts;
    @SpyBean VerificationService verification;

    private MqttClient device;
    private final BlockingQueue<MqttMessage> received = new ArrayBlockingQueue<>(200);
    private final List<UUID> deviceIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        assertTrue(timeoutScanner.enabled(), "the scheduled ACK-timeout worker is really enabled in this context");
        assertTrue(reconciler.enabled(), "the scheduled reconciler is really enabled in this context");
        assertNotNull(dispatcher);
        reset(ruleFacts);
        reset(verification);
        received.clear();
        device = new MqttClient("tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883), "device-sim-" + UUID.randomUUID(), new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        device.connect(options);
        device.setCallback(new MqttCallback() {
            @Override public void connectionLost(Throwable cause) { }
            @Override public void messageArrived(String t, MqttMessage message) { received.offer(message); }
            @Override public void deliveryComplete(IMqttDeliveryToken token) { }
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        if (device != null && device.isConnected()) device.disconnect();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE state IN ('QUEUED','IN_FLIGHT','RETRY_WAIT')", Integer.class) > 0
                && System.nanoTime() < end) Thread.sleep(50);
    }

    private UUID newDevice() throws Exception {
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Site-" + UUID.randomUUID(), "d", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "B", "d", now, now));
        Space space = spaces.save(new Space(UUID.randomUUID(), building, "S", "d", now, now));
        UUID id = devices.save(new Device(UUID.randomUUID(), space, "ESP-Live", DeviceType.CUSTOM, "Acme", "T1",
                "SN-LIVE-" + UUID.randomUUID(), "d", DeviceMode.SIMULATED)).getId();
        capabilities.save(new DeviceCapability(UUID.randomUUID(), id, CapabilityType.LED, CapabilityDirection.WRITE, null, null));
        connectivity.applyObservedStatus(id, DeviceConnectivityStatus.ONLINE);
        device.subscribe("nexus/devices/" + id + "/commands", 1);
        deviceIds.add(id);
        return id;
    }

    private static void await(BooleanSupplier c, String what, int seconds) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!c.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(25);
        }
    }

    private UUID send(UUID deviceId) {
        return commands.dispatchCommand(deviceId, new CreateDeviceCommandRequest(CapabilityType.LED, "SET_STATE", true)).requestId();
    }

    private String outboxState(UUID id) {
        return jdbc.queryForObject("SELECT state FROM command_outbox WHERE command_id = ?", String.class, id);
    }

    private List<String> facts(UUID id) {
        return jdbc.queryForList("SELECT fact_type FROM command_state_history WHERE command_id = ? ORDER BY sequence", String.class, id);
    }

    /** The device publishes an ACK on the real broker; the production listener receives and handles it. */
    private void deviceAcks(UUID deviceId, UUID command, String status, long ts, String reason) throws Exception {
        String json = "{\"commandId\":\"" + command + "\",\"status\":\"" + status + "\",\"timestamp\":" + ts
                + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}";
        MqttMessage m = new MqttMessage(json.getBytes(StandardCharsets.UTF_8));
        m.setQos(1);
        device.publish("nexus/devices/" + deviceId + "/commands/ack", m);
    }

    // ---------------------------------------------------------------------------------------------------

    @Test
    @Timeout(120)
    void anAckOverTheRealBrokerBeforeTheDeadlineIsRecordedAndTheScheduledWorkerNeverTimesItOut() throws Exception {
        UUID deviceId = newDevice();
        UUID id = send(deviceId);
        MqttMessage command = received.poll(20, TimeUnit.SECONDS);
        assertNotNull(command, "the device received the command bytes over the real broker");
        deviceAcks(deviceId, id, "ACCEPTED", Instant.now().getEpochSecond(), null);
        await(() -> actions.findById(id).orElseThrow().getAckStatus() == AcknowledgementStatus.ACCEPTED, "the production listener to record the ACK", 20);

        Thread.sleep(3500);                                                          // the 2 s ACK deadline passes while the scanner runs every 0.3 s
        assertEquals(List.of("ACCEPTED", "QUEUED", "ATTEMPT_STARTED", "PUBLISHED", "ACK_ACCEPTED"), facts(id), "no timeout for an acknowledged command");
        assertNull(received.poll(500, TimeUnit.MILLISECONDS), "and nothing was resent");
    }

    @Test
    @Timeout(120)
    void noAckMeansExactlyOneTimeoutFactFromTheRealScheduledWorkerAndNeverAResend() throws Exception {
        UUID deviceId = newDevice();
        UUID id = send(deviceId);
        assertNotNull(received.poll(20, TimeUnit.SECONDS));
        await(() -> facts(id).contains("ACK_TIMED_OUT"), "the scheduled worker to persist the timeout", 30);
        Thread.sleep(1800);                                                          // many more scan cycles
        assertEquals(1, facts(id).stream().filter(f -> f.equals("ACK_TIMED_OUT")).count(), "one timeout fact, however many scans ran");
        assertEquals("PUBLISHED", outboxState(id), "a timeout never changes the delivery state");
        assertNull(received.poll(500, TimeUnit.MILLISECONDS), "and never resends the command");
        assertEquals("SUCCESS", actions.findById(id).orElseThrow().getStatus().name(), "nor claims a failure");
        verify(verification, never()).commandFailed(any(), eq(id), any());          // an ACK timeout is not a verification event (decision A9)
        verify(ruleFacts, never()).ackRejected(any(), any(), any(), any());
    }

    @Test
    @Timeout(120)
    void aDeviceAckOverTheRealBrokerAfterTheTimeoutIsAddedAndDuplicatesAndConflictsAreHandled() throws Exception {
        UUID deviceId = newDevice();
        UUID id = send(deviceId);
        assertNotNull(received.poll(20, TimeUnit.SECONDS));
        await(() -> facts(id).contains("ACK_TIMED_OUT"), "the timeout", 30);

        long ts = Instant.now().getEpochSecond();
        deviceAcks(deviceId, id, "ACCEPTED", ts, null);
        deviceAcks(deviceId, id, "ACCEPTED", ts, null);                              // MQTT duplicate delivery
        deviceAcks(deviceId, id, "REJECTED", ts + 1, "changed");                     // conflicting duplicate
        await(() -> facts(id).contains("ACK_CONFLICT_IGNORED"), "all three ACK messages to be processed", 30);

        assertEquals(List.of("ACK_TIMED_OUT", "ACK_ACCEPTED", "ACK_AFTER_TIMEOUT", "ACK_CONFLICT_IGNORED"),
                facts(id).stream().filter(f -> f.startsWith("ACK_")).toList());
        assertEquals(AcknowledgementStatus.ACCEPTED, actions.findById(id).orElseThrow().getAckStatus());
        verify(ruleFacts, never()).ackRejected(any(), any(), any(), any());
    }

    @Test
    @Timeout(120)
    void aNegativeAckUnknownCommandAndWrongDeviceAckOverTheRealBrokerBehaveAsSpecified() throws Exception {
        UUID deviceId = newDevice();
        UUID other = newDevice();
        UUID id = send(deviceId);
        assertNotNull(received.poll(20, TimeUnit.SECONDS));
        long ts = Instant.now().getEpochSecond();
        deviceAcks(other, id, "ACCEPTED", ts, null);                                 // the right command id on the wrong device's topic
        deviceAcks(deviceId, UUID.randomUUID(), "ACCEPTED", ts, null);               // unknown command
        deviceAcks(deviceId, id, "REJECTED", ts, "busy");                            // the real negative ACK
        await(() -> actions.findById(id).orElseThrow().getAckStatus() == AcknowledgementStatus.REJECTED, "the rejection", 20);

        assertEquals(List.of("ACK_REJECTED"), facts(id).stream().filter(f -> f.startsWith("ACK_")).toList(), "the two invalid messages created no fact");
        verify(ruleFacts, times(1)).ackRejected(eq(id), eq(deviceId), any(), eq("busy"));
        verify(verification, times(1)).commandFailed(deviceId, id, "DEVICE_COMMAND_REJECTED");
        assertEquals("PUBLISHED", outboxState(id));
    }

    @Test
    @Timeout(120)
    void theScheduledReconcilerClassifiesAStrandedLegacyCommandWhileLiveTrafficFlowsAndNothingIsPublishedForIt() throws Exception {
        UUID deviceId = newDevice();
        UUID stranded = UUID.randomUUID();
        jdbc.update("INSERT INTO action_history(request_id, match_id, device_id, action_type, status, parameters, timestamp, message)"
                + " VALUES (?, ?, ?, 'SEND_COMMAND', 'ACCEPTED_FOR_DISPATCH', '{}'::jsonb, now() - INTERVAL '1 hour', 'legacy')", stranded, UUID.randomUUID(), deviceId);
        UUID live = send(deviceId);                                                  // a normal command on the same device at the same time
        MqttMessage message = received.poll(20, TimeUnit.SECONDS);
        assertNotNull(message);
        assertTrue(new String(message.getPayload(), StandardCharsets.UTF_8).contains(live.toString()), "the only message on the wire is the live command's");
        await(() -> facts(stranded).contains("DELIVERY_UNKNOWN"), "the scheduled reconciler to classify the stranded command", 30);
        Thread.sleep(1500);

        assertEquals(List.of("DELIVERY_UNKNOWN"), facts(stranded));
        assertNull(received.poll(500, TimeUnit.MILLISECONDS), "nothing was published for the stranded command");
        assertEquals("PUBLISHED", outboxState(live));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM command_outbox WHERE command_id = ?", Integer.class, stranded));
        assertEquals("ACCEPTED_FOR_DISPATCH", jdbc.queryForObject("SELECT status FROM action_history WHERE request_id = ?", String.class, stranded));
        // a late ACK from the device that did get it years ago is recorded as a fact over the real transport
        deviceAcks(deviceId, stranded, "ACCEPTED", Instant.now().getEpochSecond(), null);
        await(() -> facts(stranded).contains("ACK_ACCEPTED"), "the late ACK for the DELIVERY_UNKNOWN command", 20);
        assertEquals(List.of("DELIVERY_UNKNOWN", "ACK_ACCEPTED"), facts(stranded));
    }

    @Test
    @Timeout(180)
    void manyCommandsAcksTimeoutsAndReconciliationRunningTogetherNeitherDeadlockNorContradictThemselves() throws Exception {
        int devicesCount = 6;
        List<UUID> ids = new ArrayList<>();
        List<UUID> deviceOf = new ArrayList<>();
        for (int d = 0; d < devicesCount; d++) {
            UUID dev = newDevice();
            for (int c = 0; c < 2; c++) {
                UUID id = send(dev);
                ids.add(id);
                deviceOf.add(dev);
            }
        }
        await(() -> ids.stream().allMatch(i -> !"QUEUED".equals(outboxState(i)) && !"IN_FLIGHT".equals(outboxState(i))), "every command to be published", 60);
        // half of them are acknowledged (concurrently with the scheduled worker timing out the rest), some twice, some conflicting
        for (int i = 0; i < ids.size(); i++) {
            if (i % 2 == 0) deviceAcks(deviceOf.get(i), ids.get(i), "ACCEPTED", Instant.now().getEpochSecond(), null);
            if (i % 4 == 0) deviceAcks(deviceOf.get(i), ids.get(i), "REJECTED", Instant.now().getEpochSecond(), "x");
        }
        Thread.sleep(3500);

        for (UUID id : ids) {
            List<String> ackFacts = facts(id).stream().filter(f -> f.startsWith("ACK_")).toList();
            long outcomes = ackFacts.stream().filter(f -> f.equals("ACK_ACCEPTED") || f.equals("ACK_REJECTED")).count();
            long timeouts = ackFacts.stream().filter(f -> f.equals("ACK_TIMED_OUT")).count();
            assertTrue(outcomes <= 1, "at most one authoritative ACK outcome: " + ackFacts);
            assertTrue(timeouts <= 1, "at most one timeout: " + ackFacts);
            if (timeouts == 1 && outcomes == 1) {
                int outcomeAt = Math.max(ackFacts.indexOf("ACK_ACCEPTED"), ackFacts.indexOf("ACK_REJECTED"));
                assertTrue(ackFacts.indexOf("ACK_TIMED_OUT") < outcomeAt && ackFacts.contains("ACK_AFTER_TIMEOUT"),
                        "a timeout is never recorded after an ACK, and an ACK after a timeout is recorded as late: " + ackFacts);
            }
            if (outcomes == 0) assertEquals(1, timeouts, "an unacknowledged published command timed out exactly once: " + ackFacts);
            assertEquals(1, facts(id).stream().filter(f -> f.equals("PUBLISHED")).count(), "one delivery fact");
        }
    }
}
