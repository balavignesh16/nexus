package com.nexus.action.integration;

import com.nexus.action.application.CommandAcceptanceService;
import com.nexus.action.application.executor.CommandExecutor;
import com.nexus.action.domain.*;
import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.device.capability.definition.CapabilityValueType;
import com.nexus.device.domain.*;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import com.nexus.twin.domain.DesiredTwinStateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
class CommandAcceptanceRollbackIntegrationTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));
    @Container static GenericContainer<?> mosquitto = new GenericContainer<>("eclipse-mosquitto:2.0")
            .withExposedPorts(1883)
            .withCopyFileToContainer(MountableFile.forClasspathResource("mosquitto-test/mosquitto.conf"),
                    "/mosquitto/config/mosquitto.conf").waitingFor(Wait.forListeningPort());

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("mqtt.broker.url", () -> "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883));
        registry.add("mqtt.client.id", () -> "nexus-acceptance-rollback-" + UUID.randomUUID());
    }

    @Autowired SiteRepository sites;
    @Autowired BuildingRepository buildings;
    @Autowired SpaceRepository spaces;
    @Autowired DeviceRepository devices;
    @Autowired ActionHistoryRepository actions;
    @Autowired DesiredTwinStateRepository desired;
    @Autowired CommandAcceptanceService acceptance;
    @MockBean CommandExecutor executor;

    @Test void failedDesiredWriteRollsBackAcceptedAction() {
        Instant now=Instant.now();
        Site site=sites.save(new Site(UUID.randomUUID(), "Rollback", "test", now, now));
        Building building=buildings.save(new Building(UUID.randomUUID(),site,"Building","test",now,now));
        Space space=spaces.save(new Space(UUID.randomUUID(),building,"Space","test",now,now));
        UUID device=devices.save(new Device(UUID.randomUUID(),space,"LED",DeviceType.CUSTOM,
                "NEXUS","TEST","P21-ROLLBACK-"+UUID.randomUUID(),null,DeviceMode.SIMULATED)).getId();
        UUID command=UUID.randomUUID(), missingBinding=UUID.randomUUID();
        ActionExecutionRequest request=new ActionExecutionRequest(command,null,ActionType.SEND_COMMAND,
                Map.of("deviceId",device.toString(),"capability","LED","command","SET_STATE","value",true));
        when(executor.prepare(eq(request))).thenReturn(new CommandExecutor.PreparedCommand(device,
                missingBinding,null,null,"led",CapabilityValueType.BOOLEAN,null,"","LED","SET_STATE",true));
        assertThrows(RuntimeException.class, () -> acceptance.accept(request,new ActionContext(null,null),now));
        assertTrue(actions.findById(command).isEmpty(), "acceptance row must roll back with desired write");
        assertTrue(desired.findByDeviceId(device).isEmpty());
    }
}
