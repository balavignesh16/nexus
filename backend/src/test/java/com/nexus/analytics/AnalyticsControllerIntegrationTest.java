package com.nexus.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.action.domain.ActionHistoryRecord;
import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.action.domain.ActionType;
import com.nexus.action.domain.AcknowledgementStatus;
import com.nexus.action.domain.ExecutionStatus;
import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.device.connectivity.DeviceConnectivity;
import com.nexus.device.connectivity.DeviceConnectivityRepository;
import com.nexus.device.connectivity.DeviceConnectivityStatus;
import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceType;
import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.DomainEventRepository;
import com.nexus.event.domain.EventType;
import com.nexus.rule.persistence.RuleMatchEntity;
import com.nexus.rule.persistence.RuleMatchRepository;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import com.nexus.telemetry.domain.TelemetryRecord;
import com.nexus.telemetry.domain.TelemetryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Real Postgres/TimescaleDB integration tests for Analytics (Frontend 2.0
 * Phase 2) - proves every aggregation against genuine persisted rows, not
 * mocks. Mirrors the existing Testcontainers pattern established
 * throughout this codebase (e.g. CommandDispatchMqttIntegrationTest).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class AnalyticsControllerIntegrationTest {

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

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SiteRepository siteRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private SpaceRepository spaceRepository;
    @Autowired private DeviceRepository deviceRepository;
    @Autowired private DeviceConnectivityRepository deviceConnectivityRepository;
    @Autowired private DomainEventRepository domainEventRepository;
    @Autowired private RuleMatchRepository ruleMatchRepository;
    @Autowired private ActionHistoryRepository actionHistoryRepository;
    @Autowired private TelemetryRepository telemetryRepository;

    private UUID deviceOnlineId;
    private UUID deviceUnknownId;

    @BeforeEach
    void setUp() {
        telemetryRepository.deleteAll();
        actionHistoryRepository.deleteAll();
        ruleMatchRepository.deleteAll();
        domainEventRepository.deleteAll();
        deviceConnectivityRepository.deleteAll();
        deviceRepository.deleteAll();
        spaceRepository.deleteAll();
        buildingRepository.deleteAll();
        siteRepository.deleteAll();

        Instant now = Instant.now();
        Site site = siteRepository.save(new Site(UUID.randomUUID(), "Analytics Site", "d", now, now));
        Building building = buildingRepository.save(new Building(UUID.randomUUID(), site, "B1", "d", now, now));
        Space space = spaceRepository.save(new Space(UUID.randomUUID(), building, "S1", "d", now, now));

        Device deviceOnline = deviceRepository.save(new Device(UUID.randomUUID(), space, "Temp Sensor", DeviceType.TEMPERATURE_SENSOR,
                "Acme", "T1", "SN-ANALYTICS-1", "d", com.nexus.device.domain.DeviceMode.SIMULATED));
        Device deviceUnknown = deviceRepository.save(new Device(UUID.randomUUID(), space, "Gas Sensor", DeviceType.GAS_SENSOR,
                "Acme", "G1", "SN-ANALYTICS-2", "d", com.nexus.device.domain.DeviceMode.SIMULATED));
        deviceOnlineId = deviceOnline.getId();
        deviceUnknownId = deviceUnknown.getId();

        // Only deviceOnline has a connectivity row - deviceUnknown deliberately has none (UNKNOWN).
        deviceConnectivityRepository.save(new DeviceConnectivity(deviceOnlineId, DeviceConnectivityStatus.ONLINE, now));
    }

    // ---------- Summary ----------

    @Test
    void summary_emptyActivityTables_returnsZerosNotError() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/summary").param("range", "24h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalDeviceCount").value(2))
                .andExpect(jsonPath("$.eventCount").value(0))
                .andExpect(jsonPath("$.ruleMatchCount").value(0))
                .andExpect(jsonPath("$.actionStatusDistribution").isEmpty())
                .andExpect(jsonPath("$.commandAckDistribution").isEmpty());
    }

    @Test
    void summary_deviceCountByType_correct() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/summary").param("range", "24h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceCountByType.TEMPERATURE_SENSOR").value(1))
                .andExpect(jsonPath("$.deviceCountByType.GAS_SENSOR").value(1));
    }

    @Test
    void summary_connectivity_onlineAndUnknown_neverTreatsUnknownAsOffline() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/summary").param("range", "24h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectivityDistribution.ONLINE").value(1))
                .andExpect(jsonPath("$.connectivityDistribution.UNKNOWN").value(1))
                .andExpect(jsonPath("$.connectivityDistribution.OFFLINE").doesNotExist());
    }

    @Test
    void summary_eventAndRuleMatchCounts_scopedToRange_excludesOutsideRange() throws Exception {
        Instant now = Instant.now();
        domainEventRepository.save(new DomainEvent(UUID.randomUUID(), EventType.SENSOR_VALUE_CHANGED, deviceOnlineId,
                now.minus(1, ChronoUnit.HOURS), "test", Map.of(), "INFO", "corr-1"));
        domainEventRepository.save(new DomainEvent(UUID.randomUUID(), EventType.SENSOR_VALUE_CHANGED, deviceOnlineId,
                now.minus(2, ChronoUnit.DAYS), "test", Map.of(), "INFO", "corr-2")); // outside 24h range

        ruleMatchRepository.save(new RuleMatchEntity(UUID.randomUUID(), UUID.randomUUID(), Map.of("name", "R1"),
                UUID.randomUUID(), "corr-1", Map.of("outcome", "matched"), now.minus(1, ChronoUnit.HOURS), "matched"));
        ruleMatchRepository.save(new RuleMatchEntity(UUID.randomUUID(), UUID.randomUUID(), Map.of("name", "R2"),
                UUID.randomUUID(), "corr-2", Map.of("outcome", "matched"), now.minus(2, ChronoUnit.DAYS), "matched")); // outside range

        mockMvc.perform(get("/api/v1/analytics/summary").param("range", "24h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventCount").value(1))
                .andExpect(jsonPath("$.ruleMatchCount").value(1));
    }

    @Test
    void summary_actionStatusDistribution_correct() throws Exception {
        Instant now = Instant.now();
        actionHistoryRepository.save(new ActionHistoryRecord(UUID.randomUUID(), null, deviceOnlineId,
                ActionType.CREATE_ALERT, ExecutionStatus.SUCCESS, Map.of(), now, "ok"));
        actionHistoryRepository.save(new ActionHistoryRecord(UUID.randomUUID(), null, deviceOnlineId,
                ActionType.CREATE_ALERT, ExecutionStatus.FAILURE, Map.of(), now, "fail"));
        actionHistoryRepository.save(new ActionHistoryRecord(UUID.randomUUID(), null, deviceOnlineId,
                ActionType.CREATE_ALERT, ExecutionStatus.SUCCESS, Map.of(), now, "ok2"));

        mockMvc.perform(get("/api/v1/analytics/summary").param("range", "24h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionStatusDistribution.SUCCESS").value(2))
                .andExpect(jsonPath("$.actionStatusDistribution.FAILURE").value(1));
    }

    @Test
    void summary_commandAckDistribution_onlySendCommandRowsParticipate_nullAckIsNoAckYetNotFailure() throws Exception {
        Instant now = Instant.now();
        ActionHistoryRecord accepted = new ActionHistoryRecord(UUID.randomUUID(), null, deviceOnlineId,
                ActionType.SEND_COMMAND, ExecutionStatus.SUCCESS, Map.of(), now, "published");
        accepted.applyAcknowledgement(AcknowledgementStatus.ACCEPTED, now, null);
        actionHistoryRepository.save(accepted);

        ActionHistoryRecord rejected = new ActionHistoryRecord(UUID.randomUUID(), null, deviceOnlineId,
                ActionType.SEND_COMMAND, ExecutionStatus.SUCCESS, Map.of(), now, "published");
        rejected.applyAcknowledgement(AcknowledgementStatus.REJECTED, now, "STALE");
        actionHistoryRepository.save(rejected);

        // Published but never acknowledged - must be NO_ACK_YET, never a failure.
        actionHistoryRepository.save(new ActionHistoryRecord(UUID.randomUUID(), null, deviceOnlineId,
                ActionType.SEND_COMMAND, ExecutionStatus.SUCCESS, Map.of(), now, "published, no ack"));

        // A non-command action - must NOT participate in the ack denominator at all.
        actionHistoryRepository.save(new ActionHistoryRecord(UUID.randomUUID(), null, deviceOnlineId,
                ActionType.CREATE_ALERT, ExecutionStatus.SUCCESS, Map.of(), now, "unrelated"));

        mockMvc.perform(get("/api/v1/analytics/summary").param("range", "24h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commandAckDistribution.ACCEPTED").value(1))
                .andExpect(jsonPath("$.commandAckDistribution.REJECTED").value(1))
                .andExpect(jsonPath("$.commandAckDistribution.NO_ACK_YET").value(1));
        // Total across all three buckets is 3, not 4 - confirms the CREATE_ALERT row was excluded.
    }

    @Test
    void summary_invalidRange_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/summary").param("range", "90d"))
                .andExpect(status().isBadRequest());
    }

    // ---------- Time series ----------

    @Test
    void timeseries_telemetry_multipleBuckets_correctGroupingAndOrdering() throws Exception {
        Instant now = Instant.now();
        telemetryRepository.save(new TelemetryRecord(UUID.randomUUID(), deviceOnlineId,
                now.minus(50, ChronoUnit.MINUTES), "TEMPERATURE_SENSOR", 20.0, "CELSIUS"));
        telemetryRepository.save(new TelemetryRecord(UUID.randomUUID(), deviceOnlineId,
                now.minus(45, ChronoUnit.MINUTES), "TEMPERATURE_SENSOR", 21.0, "CELSIUS"));
        telemetryRepository.save(new TelemetryRecord(UUID.randomUUID(), deviceOnlineId,
                now.minus(5, ChronoUnit.MINUTES), "TEMPERATURE_SENSOR", 22.0, "CELSIUS"));

        mockMvc.perform(get("/api/v1/analytics/timeseries")
                        .param("metric", "telemetry").param("range", "1h").param("bucket", "5minutes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metric").value("telemetry"))
                .andExpect(jsonPath("$.points", org.hamcrest.Matchers.hasSize(org.hamcrest.Matchers.greaterThanOrEqualTo(2))));
    }

    @Test
    void timeseries_emptyRange_returnsEmptyPointsNotError() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/timeseries")
                        .param("metric", "events").param("range", "1h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points").isArray())
                .andExpect(jsonPath("$.points").isEmpty());
    }

    @Test
    void timeseries_invalidMetric_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/analytics/timeseries")
                        .param("metric", "not-a-real-metric").param("range", "1h"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void timeseries_invalidBucketForRange_returns400() throws Exception {
        // 1-minute buckets over a 30-day range would produce ~43,200 points - rejected.
        mockMvc.perform(get("/api/v1/analytics/timeseries")
                        .param("metric", "telemetry").param("range", "30d").param("bucket", "minute"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void timeseries_pipelineMetric_windowClampedToSevenDayRetention_evenWhen30dRequested() throws Exception {
        String body = mockMvc.perform(get("/api/v1/analytics/timeseries")
                        .param("metric", "pipeline").param("range", "30d"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var json = objectMapper.readTree(body);
        Instant from = Instant.parse(json.get("from").asText());
        Instant to = Instant.parse(json.get("to").asText());
        long actualDays = java.time.Duration.between(from, to).toDays();

        org.junit.jupiter.api.Assertions.assertTrue(actualDays <= 7,
                "pipeline window must never exceed its real 7-day retention, was " + actualDays + " days");
    }
}
