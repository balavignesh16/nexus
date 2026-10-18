package com.nexus.action.lifecycle;

import com.nexus.action.outbox.CommandAckTimeoutScanner;
import com.nexus.action.outbox.FakeBroker;
import com.nexus.action.outbox.LegacyDeliveryReconciler;
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
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Shared disposable PostgreSQL + Mosquitto fixture of the P4-FINAL lifecycle tests: outbox ON, the real dispatcher, a scripted broker
 * (the real broker is never asked to publish), the scheduled lifecycle workers OFF (tests call them directly, so every pass is explicit).
 */
@SpringBootTest(properties = {"nexus.command.outbox.dispatcher-enabled=true", "nexus.command.outbox.enabled=true"})
@Testcontainers
@Import(LifecycleFixture.Scripted.class)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
abstract class LifecycleFixture {

    @TestConfiguration
    static class Scripted {
        @Bean
        @Primary
        FakeBroker fakeBroker() { return new FakeBroker(); }
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
        registry.add("mqtt.client.id", () -> "nexus-lifecycle-" + UUID.randomUUID());
    }

    @Autowired protected FakeBroker broker;
    @Autowired protected DeviceCommandService commands;
    @Autowired protected DeviceCommandAckMessageHandler ackHandler;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected SiteRepository sites;
    @Autowired protected BuildingRepository buildings;
    @Autowired protected SpaceRepository spaces;
    @Autowired protected DeviceRepository devices;
    @Autowired protected DeviceCapabilityRepository capabilities;
    @Autowired protected DeviceConnectivityRegistry connectivity;
    @Autowired protected CommandAckTimeoutScanner timeoutScanner;
    @Autowired protected LegacyDeliveryReconciler reconciler;

    protected UUID deviceId;

    @BeforeEach
    void freshDevice() {
        broker.reset();
        deviceId = newDevice();
    }

    protected UUID newDevice() {
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Site-" + UUID.randomUUID(), "d", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "B", "d", now, now));
        Space space = spaces.save(new Space(UUID.randomUUID(), building, "S", "d", now, now));
        UUID id = devices.save(new Device(UUID.randomUUID(), space, "ESP-Lifecycle", DeviceType.CUSTOM, "Acme", "T1",
                "SN-LC-" + UUID.randomUUID(), "d", DeviceMode.SIMULATED)).getId();
        capabilities.save(new DeviceCapability(UUID.randomUUID(), id, CapabilityType.LED, CapabilityDirection.WRITE, null, null));
        connectivity.applyObservedStatus(id, DeviceConnectivityStatus.ONLINE);
        return id;
    }

    @AfterEach
    void drain() throws Exception {
        await(() -> jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE state IN ('QUEUED','IN_FLIGHT','RETRY_WAIT')", Integer.class) == 0,
                "every obligation to resolve", 60);
    }

    protected static void await(BooleanSupplier condition, String what, int seconds) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(25);
        }
    }

    protected UUID sendCommand() {
        return commands.dispatchCommand(deviceId, new CreateDeviceCommandRequest(CapabilityType.LED, "SET_STATE", true)).requestId();
    }

    protected UUID sendPublished() throws Exception {
        UUID id = sendCommand();
        await(() -> "PUBLISHED".equals(outboxState(id)), "publication", 30);
        return id;
    }

    protected UUID sendDeadLettered(BrokerCommandOutcome outcome) throws Exception {
        broker.behaviour = (t, b) -> outcome == BrokerCommandOutcome.UNCERTAIN
                ? com.nexus.action.outbox.BrokerCommandPublisher.Attempt.uncertain("PUBLISH_TIMEOUT")
                : com.nexus.action.outbox.BrokerCommandPublisher.Attempt.notSent("CLIENT_NOT_CONNECTED");
        UUID id = sendCommand();
        await(() -> "DEAD_LETTER".equals(outboxState(id)), "dead letter", 60);
        return id;
    }

    protected enum BrokerCommandOutcome { NOT_SENT, UNCERTAIN }

    protected String outboxState(UUID id) {
        return jdbc.queryForObject("SELECT state FROM command_outbox WHERE command_id = ?", String.class, id);
    }

    protected void ack(UUID command, String status, long epochSeconds, String reason) {
        String json = "{\"commandId\":\"" + command + "\",\"status\":\"" + status + "\",\"timestamp\":" + epochSeconds
                + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}";
        ackHandler.handle("nexus/devices/" + deviceId + "/commands/ack", json.getBytes(StandardCharsets.UTF_8));
    }

    protected List<String> facts(UUID id) {
        return jdbc.queryForList("SELECT fact_type FROM command_state_history WHERE command_id = ? ORDER BY sequence", String.class, id);
    }
}
