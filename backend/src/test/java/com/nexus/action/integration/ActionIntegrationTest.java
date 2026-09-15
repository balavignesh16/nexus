package com.nexus.action.integration;

import com.nexus.action.api.dto.ActionHistoryResponse;
import com.nexus.action.domain.ActionType;
import com.nexus.action.domain.ActionHistoryRecord;
import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.building.application.BuildingService;
import com.nexus.building.dto.CreateBuildingRequest;
import com.nexus.device.api.dto.CreateDeviceRequest;
import com.nexus.device.api.dto.UpdateDeviceRequest;
import com.nexus.device.application.DeviceService;
import com.nexus.device.domain.DeviceStatus;
import com.nexus.device.domain.DeviceType;
import com.nexus.rule.api.dto.RuleRequest;
import com.nexus.rule.domain.RuleAction;
import com.nexus.rule.domain.RuleCondition;
import com.nexus.rule.persistence.RuleRegistry;
import com.nexus.site.application.SiteService;
import com.nexus.site.dto.CreateSiteRequest;
import com.nexus.space.application.SpaceService;
import com.nexus.space.dto.CreateSpaceRequest;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.telemetry.application.TelemetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Testcontainers
public class ActionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    @Autowired
    private SiteService siteService;
    @Autowired
    private BuildingService buildingService;
    @Autowired
    private SpaceService spaceService;
    @Autowired
    private DeviceService deviceService;
    @Autowired
    private TelemetryService telemetryService;
    @Autowired
    private RuleRegistry ruleRegistry;
    @Autowired
    private ActionHistoryRepository actionHistoryRepository;

    private UUID deviceId;

    @BeforeEach
    void setup() {
        actionHistoryRepository.deleteAll();
        ruleRegistry.clear();
        
        UUID siteId = siteService.createSite(new CreateSiteRequest("Site A", "Desc")).id();
        UUID bldgId = buildingService.createBuilding(siteId, new CreateBuildingRequest("Bldg 1", "Desc")).id();
        UUID spaceId = spaceService.createSpace(bldgId, new CreateSpaceRequest("Space X", "Desc")).id();

        deviceId = deviceService.createDevice(spaceId, new CreateDeviceRequest(
                "Test Device", DeviceType.TEMPERATURE_SENSOR, "NEXUS", "M1", UUID.randomUUID().toString(), "Desc", null
        )).id();
        deviceService.updateDevice(deviceId, new UpdateDeviceRequest("Test Device", DeviceStatus.ACTIVE, "Desc"));
    }

    @Test
    void shouldTriggerActionWhenTelemetryMatchesRule() throws InterruptedException {
        // Register Rule
        RuleCondition typeCondition = new RuleCondition(com.nexus.rule.domain.RuleField.EVENT_TYPE, com.nexus.rule.domain.RuleOperator.EQ, "TWIN_UPDATED");
        RuleCondition valCondition = new RuleCondition(com.nexus.rule.domain.RuleField.VALUE, com.nexus.rule.domain.RuleOperator.GT, "30.0");
        RuleAction action = new RuleAction("CREATE_ALERT", Map.of("severity", "CRITICAL"));
        com.nexus.rule.domain.Rule rule = new com.nexus.rule.domain.Rule(
                UUID.randomUUID(), "High Temp Alert", true, 1, List.of(typeCondition, valCondition), List.of(action), Map.of()
        );
        ruleRegistry.register(rule);

        // Send telemetry that triggers the rule
        telemetryService.processTelemetry(new TelemetryRequest(
                deviceId, Instant.now(), "TEMPERATURE_SENSOR", 35.0, "CELSIUS"
        ));

        // Let async events process
        Thread.sleep(100);

        List<ActionHistoryRecord> history = actionHistoryRepository.findAllByOrderByTimestampDesc(PageRequest.of(0, 10)).getContent();
        assertEquals(1, history.size(), "One action should have been executed");
        
        ActionHistoryRecord result = history.get(0);
        assertEquals(ActionType.CREATE_ALERT, result.getActionType());
        assertEquals("SUCCESS", result.getStatus().name());
        // Tier 5D regression: a rule-triggered action must still persist its
        // real match_id, exactly as before match_id became nullable.
        org.junit.jupiter.api.Assertions.assertNotNull(result.getMatchId(),
                "a rule-triggered action must retain its real, non-null match_id after Tier 5D's nullability change");
    }
}
