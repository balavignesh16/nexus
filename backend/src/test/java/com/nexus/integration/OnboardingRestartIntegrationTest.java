package com.nexus.integration;

import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.device.domain.*;
import com.nexus.device.onboarding.*;
import com.nexus.device.template.DeviceTemplateRepository;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OnboardingRestartIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));
    @Container static GenericContainer<?> mosquitto = new GenericContainer<>("eclipse-mosquitto:2.0")
            .withExposedPorts(1883)
            .withCopyFileToContainer(MountableFile.forClasspathResource("mosquitto-test/mosquitto.conf"),
                    "/mosquitto/config/mosquitto.conf").waitingFor(Wait.forListeningPort());

    private static UUID sessionId;
    private static UUID deviceId;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("mqtt.broker.url", () -> "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883));
        registry.add("mqtt.client.id", () -> "nexus-onboarding-restart-" + UUID.randomUUID());
    }

    @Autowired SiteRepository sites;
    @Autowired BuildingRepository buildings;
    @Autowired SpaceRepository spaces;
    @Autowired DeviceRepository devices;
    @Autowired DeviceTemplateRepository templates;
    @Autowired DeviceOnboardingSessionRepository sessions;
    @Autowired OnboardingLifecycleService lifecycle;

    @Test @Order(1) @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void persistWaitingSessionBeforeContextRestart() {
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Restart Site", "test", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "Restart Building", "test", now, now));
        Space space = spaces.save(new Space(UUID.randomUUID(), building, "Restart Space", "test", now, now));
        var template = templates.findById(UUID.fromString("20000000-0000-0000-0000-000000000001")).orElseThrow();
        var device = new Device(UUID.randomUUID(), space, "Restart Device", DeviceType.CUSTOM,
                "NEXUS", "TEST", "RESTART-" + UUID.randomUUID(), null, DeviceMode.SIMULATED);
        device.attachTemplate(template);
        devices.save(device);
        deviceId = device.getId();
        var session = sessions.save(new DeviceOnboardingSession(UUID.randomUUID(), deviceId,
                template.getId(), "restart-" + UUID.randomUUID(), "hash", now.plusSeconds(300), now));
        sessionId = session.getId();
        assertEquals(OnboardingState.WAITING_FOR_STATUS, session.getState());
    }

    @Test @Order(2)
    void freshContextRestoresSessionAndCanAdvanceIt() {
        assertNotNull(sessionId);
        assertEquals(OnboardingState.WAITING_FOR_STATUS, lifecycle.get(sessionId).state());
        assertEquals(DeviceStatus.REGISTERED, devices.findById(deviceId).orElseThrow().getStatus());
        lifecycle.observedOnline(deviceId);
        assertEquals(OnboardingState.WAITING_FOR_TELEMETRY, sessions.findById(sessionId).orElseThrow().getState());
        assertNotNull(sessions.findById(sessionId).orElseThrow().getFirstStatusAt());
    }
}
