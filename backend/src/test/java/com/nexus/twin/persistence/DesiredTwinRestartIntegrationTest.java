package com.nexus.twin.persistence;

import com.nexus.action.domain.*;
import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.device.capability.definition.CapabilityValueType;
import com.nexus.device.domain.*;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import com.nexus.twin.api.DigitalTwinController;
import com.nexus.twin.domain.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
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
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DesiredTwinRestartIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));
    @Container static GenericContainer<?> mosquitto = new GenericContainer<>("eclipse-mosquitto:2.0")
            .withExposedPorts(1883)
            .withCopyFileToContainer(MountableFile.forClasspathResource("mosquitto-test/mosquitto.conf"),
                    "/mosquitto/config/mosquitto.conf").waitingFor(Wait.forListeningPort());
    private static UUID deviceId;
    private static UUID commandId;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("mqtt.broker.url", () -> "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883));
        registry.add("mqtt.client.id", () -> "nexus-desired-restart-" + UUID.randomUUID());
    }

    @Autowired SiteRepository sites;
    @Autowired BuildingRepository buildings;
    @Autowired SpaceRepository spaces;
    @Autowired DeviceRepository devices;
    @Autowired ActionHistoryRepository actions;
    @Autowired DesiredTwinStateRepository desired;
    @Autowired DigitalTwinController twins;

    @Test @Order(1) @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void persistDesiredIntentBeforeRestart() {
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Desired Restart", "test", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "Building", "test", now, now));
        Space space = spaces.save(new Space(UUID.randomUUID(), building, "Space", "test", now, now));
        Device device = devices.save(new Device(UUID.randomUUID(), space, "LED", DeviceType.CUSTOM,
                "NEXUS", "TEST", "P21-RESTART-" + UUID.randomUUID(), null, DeviceMode.SIMULATED));
        deviceId = device.getId(); commandId = UUID.randomUUID();
        actions.save(new ActionHistoryRecord(commandId, null, deviceId, ActionType.SEND_COMMAND,
                ExecutionStatus.ACCEPTED_FOR_DISPATCH, Map.of("deviceId", deviceId.toString()),
                now, "Delivery unknown"));
        desired.save(new DesiredTwinState(deviceId, "legacy:LED", null, "LED", "", null,
                "led", CapabilityValueType.BOOLEAN, true, null, "MANUAL_COMMAND", commandId, now, now));
        assertEquals(1, desired.findByDeviceId(deviceId).size());
    }

    @Test @Order(2)
    void newContextReadsDurableIntentWithoutReportedObservation() {
        assertNotNull(deviceId);
        var response = twins.getTwin(deviceId).getBody();
        assertNotNull(response);
        assertTrue(response.reportedState().isEmpty());
        assertEquals(commandId, response.desiredState().get("legacy:LED").commandId());
        assertEquals("ACCEPTED_FOR_DISPATCH", response.desiredState().get("legacy:LED").deliveryStatus());
        assertEquals("DESIRED_UNVERIFIED", response.effectiveState().get("legacy:LED").source());
    }
}
