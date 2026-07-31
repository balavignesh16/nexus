package com.nexus.action.integration;

import com.nexus.action.api.dto.ActionHistoryResponse;
import com.nexus.action.domain.ActionType;
import com.nexus.action.persistence.BoundedActionHistoryStore;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
public class ActionIntegrationTest {

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
    private BoundedActionHistoryStore actionHistoryStore;

    private UUID deviceId;

    @BeforeEach
    void setup() {
        actionHistoryStore.clear();
        ruleRegistry.clear();
        
        UUID siteId = siteService.createSite(new CreateSiteRequest("Site A", "Desc")).id();
        UUID bldgId = buildingService.createBuilding(siteId, new CreateBuildingRequest("Bldg 1", "Desc")).id();
        UUID spaceId = spaceService.createSpace(bldgId, new CreateSpaceRequest("Space X", "Desc")).id();

        deviceId = deviceService.createDevice(spaceId, new CreateDeviceRequest(
                "Test Device", DeviceType.TEMPERATURE_SENSOR, "NEXUS", "M1", UUID.randomUUID().toString(), "Desc"
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

        // Let async events process (though they are currently sync, we wait a tiny bit just in case)
        Thread.sleep(100);

        List<com.nexus.action.domain.ActionExecutionResult> history = actionHistoryStore.getRecentHistory(10);
        assertEquals(1, history.size(), "One action should have been executed");
        
        com.nexus.action.domain.ActionExecutionResult result = history.get(0);
        assertEquals(ActionType.CREATE_ALERT, result.request().actionType());
        assertEquals("SUCCESS", result.status().name());
    }
}
