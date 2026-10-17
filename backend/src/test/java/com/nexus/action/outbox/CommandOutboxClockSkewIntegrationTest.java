package com.nexus.action.outbox;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
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

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Forensic finding M3 through the real acceptance path: with the application clock one hour ahead of, or behind, the database,
 * an accepted command is still created, scheduled, claimable and delivered inside its valid window, because in outbox mode the
 * payload timestamp (still an integer of epoch seconds, the value the firmware compares with its own clock), the creation and
 * scheduling timestamps and the expiry all come from the database clock at acceptance.
 */
@SpringBootTest(properties = {"nexus.command.outbox.dispatcher-enabled=true", "nexus.command.outbox.enabled=true"})
@Testcontainers
@Import(CommandOutboxClockSkewIntegrationTest.Scripted.class)
class CommandOutboxClockSkewIntegrationTest {

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
        registry.add("mqtt.client.id", () -> "nexus-outbox-skew-" + UUID.randomUUID());
    }

    @MockBean Clock clock;
    @Autowired FakeBroker broker;
    @Autowired DeviceCommandService commands;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired CommandOutboxRepository outbox;
    @Autowired SiteRepository sites;
    @Autowired BuildingRepository buildings;
    @Autowired SpaceRepository spaces;
    @Autowired DeviceRepository devices;
    @Autowired DeviceCapabilityRepository capabilities;
    @Autowired DeviceConnectivityRegistry connectivity;

    private static void await(BooleanSupplier c, String what) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!c.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(25);
        }
    }

    private UUID newDevice() {
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Site-" + UUID.randomUUID(), "d", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "B", "d", now, now));
        Space space = spaces.save(new Space(UUID.randomUUID(), building, "S", "d", now, now));
        UUID id = devices.save(new Device(UUID.randomUUID(), space, "ESP-Skew", DeviceType.CUSTOM, "Acme", "T1",
                "SN-SKEW-" + UUID.randomUUID(), "d", DeviceMode.SIMULATED)).getId();
        capabilities.save(new DeviceCapability(UUID.randomUUID(), id, CapabilityType.LED, CapabilityDirection.WRITE, null, null));
        connectivity.applyObservedStatus(id, DeviceConnectivityStatus.ONLINE);
        return id;
    }

    @Test
    @Timeout(120)
    void anApplicationClockAnHourAheadOrBehindStillYieldsAClaimableDeliverableCommandWithDatabaseTimeOnTheWire() throws Exception {
        for (long skewSeconds : new long[]{3600, -3600}) {
            when(clock.instant()).thenAnswer(inv -> Instant.now().plusSeconds(skewSeconds));
            when(clock.getZone()).thenReturn(java.time.ZoneOffset.UTC);
            broker.reset();
            UUID device = newDevice();

            long dbBefore = jdbc.queryForObject("SELECT floor(extract(epoch from clock_timestamp()))::bigint", Long.class);
            UUID id = commands.dispatchCommand(device, new CreateDeviceCommandRequest(CapabilityType.LED, "SET_STATE", true)).requestId();
            long dbAfter = jdbc.queryForObject("SELECT ceil(extract(epoch from clock_timestamp()))::bigint", Long.class);

            CommandOutboxEntity row = outbox.findById(id).orElseThrow();
            // the wire timestamp, creation, scheduling and expiry follow the DATABASE clock, not the skewed application clock
            assertTrue(row.getPayloadEpochSeconds() >= dbBefore && row.getPayloadEpochSeconds() <= dbAfter,
                    "skew " + skewSeconds + ": payload timestamp " + row.getPayloadEpochSeconds() + " is not database time [" + dbBefore + "," + dbAfter + "]");
            assertTrue(Math.abs(row.getCreatedAt().getEpochSecond() - dbBefore) <= 5, "created_at is database time");
            assertEquals(15, row.getExpiresAt().getEpochSecond() - row.getPayloadEpochSeconds(), "the window is exactly the configured 15 s after the payload time");

            // and the command is claimable and delivered inside its valid window
            await(() -> "PUBLISHED".equals(jdbc.queryForObject("SELECT state FROM command_outbox WHERE command_id = ?", String.class, id)), "delivery with skew " + skewSeconds);
            assertEquals(1, broker.calls.size());
            JsonNode wire = mapper.readTree(broker.calls.get(0).bytes());
            assertEquals(row.getPayloadEpochSeconds(), wire.get("timestamp").asLong(), "the wire timestamp is the stored one");
            assertTrue(Math.abs(wire.get("timestamp").asLong() - Instant.now().getEpochSecond()) <= 10,
                    "an integer of epoch seconds the firmware (whose clock is right) accepts as fresh, whatever the backend application clock says");
            Timestamp terminal = jdbc.queryForObject("SELECT terminal_at FROM command_outbox WHERE command_id = ?", Timestamp.class, id);
            assertTrue(terminal != null);
        }
    }
}
