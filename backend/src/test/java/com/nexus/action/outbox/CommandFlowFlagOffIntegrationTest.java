package com.nexus.action.outbox;

import com.nexus.action.api.dto.ActionHistoryResponse;
import com.nexus.action.domain.ExecutionStatus;
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

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Flag OFF (the shipped default) with the tightest possible caps (1 per device, 1 in all): the queue caps constrain delivery OBLIGATIONS, and
 * with the outbox off acceptance creates none, so legacy behaviour is exactly what it was. Three commands for one device are all accepted and
 * published directly, the admission check is never consulted, and no outbox row exists. (The publish rate applies to the dispatcher's claims
 * and therefore also to draining an outbox after a rollback; that is covered by the dispatcher tests.)
 */
@SpringBootTest(properties = {"nexus.command.flow.global-queue-cap=1", "nexus.command.flow.device-queue-cap=1"})
@Testcontainers
class CommandFlowFlagOffIntegrationTest {

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
        registry.add("mqtt.client.id", () -> "nexus-flow-off-" + UUID.randomUUID());
    }

    @Autowired CommandOutboxSettings settings;
    @Autowired DeviceCommandService commands;
    @Autowired JdbcTemplate jdbc;
    @Autowired SiteRepository sites;
    @Autowired BuildingRepository buildings;
    @Autowired SpaceRepository spaces;
    @Autowired DeviceRepository devices;
    @Autowired DeviceCapabilityRepository capabilities;
    @Autowired DeviceConnectivityRegistry connectivity;
    @SpyBean CommandAdmission admission;

    @Test
    @Timeout(60)
    void withTheOutboxOffNoCapAppliesAndEveryCommandIsPublishedDirectly() {
        assertFalse(settings.enabled());
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Site-" + UUID.randomUUID(), "d", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "B", "d", now, now));
        Space space = spaces.save(new Space(UUID.randomUUID(), building, "S", "d", now, now));
        UUID device = devices.save(new Device(UUID.randomUUID(), space, "ESP-FlowOff", DeviceType.CUSTOM, "Acme", "T1", "SN-FLOWOFF-" + UUID.randomUUID(), "d", DeviceMode.SIMULATED)).getId();
        capabilities.save(new DeviceCapability(UUID.randomUUID(), device, CapabilityType.LED, CapabilityDirection.WRITE, null, null));
        connectivity.applyObservedStatus(device, DeviceConnectivityStatus.ONLINE);

        for (int i = 0; i < 3; i++) {
            ActionHistoryResponse response = commands.dispatchCommand(device, new CreateDeviceCommandRequest(CapabilityType.LED, "SET_STATE", i % 2 == 0));
            assertEquals(ExecutionStatus.SUCCESS, response.status(), "command " + i + " was published directly, as before Phase 4");
        }
        verify(admission, never()).admit(any(), anyBoolean());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE device_id = ?", Integer.class, device));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM action_history WHERE device_id = ?", Integer.class, device));
    }
}
