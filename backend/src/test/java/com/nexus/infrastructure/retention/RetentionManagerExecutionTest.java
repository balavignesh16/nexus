package com.nexus.infrastructure.retention;

import com.nexus.action.domain.ActionHistoryRecord;
import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.action.domain.ActionType;
import com.nexus.action.domain.ExecutionStatus;
import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceStatus;
import com.nexus.device.domain.DeviceType;
import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.DomainEventRepository;
import com.nexus.event.domain.EventType;
import com.nexus.pipeline.domain.PipelineStage;
import com.nexus.pipeline.domain.PipelineTrace;
import com.nexus.pipeline.domain.PipelineTraceRepository;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import com.nexus.telemetry.domain.TelemetryRecord;
import com.nexus.telemetry.domain.TelemetryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves RetentionManager.cleanupOldData() actually executes correctly
 * against a real TimescaleDB instance - not just that it is registered as a
 * scheduled task (RetentionManagerSchedulingTest already proves that
 * separately). The method is invoked directly rather than waiting for the
 * "0 0 2 * * *" cron; the production schedule is untouched.
 *
 * Default retention periods from application.yml are used as-is (telemetry
 * 30 days, pipeline 7 days, events/actions 90 days) - test fixtures are
 * placed comfortably on either side of each threshold so no configuration
 * override is needed and the test is deterministic regardless of exact
 * execution time.
 */
@SpringBootTest
@Testcontainers
class RetentionManagerExecutionTest {

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
    private RetentionManager retentionManager;
    @Autowired
    private TelemetryRepository telemetryRepository;
    @Autowired
    private PipelineTraceRepository pipelineTraceRepository;
    @Autowired
    private DomainEventRepository domainEventRepository;
    @Autowired
    private ActionHistoryRepository actionHistoryRepository;
    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private SpaceRepository spaceRepository;
    @Autowired
    private BuildingRepository buildingRepository;
    @Autowired
    private SiteRepository siteRepository;

    private Device device;

    @BeforeEach
    void setUp() {
        telemetryRepository.deleteAll();
        pipelineTraceRepository.deleteAll();
        domainEventRepository.deleteAll();
        actionHistoryRepository.deleteAll();
        deviceRepository.deleteAll();
        spaceRepository.deleteAll();
        buildingRepository.deleteAll();
        siteRepository.deleteAll();

        Site site = siteRepository.save(new Site(UUID.randomUUID(), "Site", "Desc", Instant.now(), Instant.now()));
        Building building = buildingRepository.save(new Building(UUID.randomUUID(), site, "B1", "Desc", Instant.now(), Instant.now()));
        Space space = spaceRepository.save(new Space(UUID.randomUUID(), building, "S1", "Desc", Instant.now(), Instant.now()));
        device = new Device(UUID.randomUUID(), space, "Device A", DeviceType.TEMPERATURE_SENSOR, "M", "M", "SN-RET-1", "D");
        device.update("Device A", DeviceStatus.ACTIVE, "D");
        device = deviceRepository.save(device);
    }

    // ---------- Telemetry retention (TimescaleDB drop_chunks on a hypertable) ----------

    @Test
    void cleanupOldData_removesTelemetryOlderThanRetentionPeriod_keepsRecentTelemetry() {
        // Default nexus.retention.telemetry-days is 30. Old/recent fixtures
        // are placed far enough apart (40 days vs 1 day) that they land in
        // different TimescaleDB chunks regardless of the default chunk
        // interval, so drop_chunks can only affect the old one.
        Instant old = Instant.now().minus(40, ChronoUnit.DAYS);
        Instant recent = Instant.now().minus(1, ChronoUnit.DAYS);
        UUID oldId = UUID.randomUUID();
        UUID recentId = UUID.randomUUID();
        telemetryRepository.save(new TelemetryRecord(oldId, device.getId(), old, "TEMPERATURE_SENSOR", 20.0, "CELSIUS"));
        telemetryRepository.save(new TelemetryRecord(recentId, device.getId(), recent, "TEMPERATURE_SENSOR", 21.0, "CELSIUS"));
        assertEquals(2, telemetryRepository.count(), "both fixtures must exist before retention runs");

        retentionManager.cleanupOldData();

        List<TelemetryRecord> remaining = telemetryRepository.findAll();
        assertEquals(1, remaining.size(), "only the recent telemetry row should survive retention");
        assertEquals(recentId, remaining.get(0).getId());
    }

    // ---------- Pipeline trace retention (TimescaleDB drop_chunks on a hypertable) ----------

    @Test
    void cleanupOldData_removesPipelineTracesOlderThanRetentionPeriod_keepsRecentTraces() {
        // Default nexus.retention.pipeline-days is 7.
        long oldStart = Instant.now().minus(20, ChronoUnit.DAYS).toEpochMilli();
        long recentStart = Instant.now().minus(1, ChronoUnit.DAYS).toEpochMilli();
        PipelineTrace oldTrace = PipelineTrace.of("corr-old", device.getId().toString(), PipelineStage.TELEMETRY, oldStart, "SUCCESS", null, Map.of());
        PipelineTrace recentTrace = PipelineTrace.of("corr-recent", device.getId().toString(), PipelineStage.TELEMETRY, recentStart, "SUCCESS", null, Map.of());
        pipelineTraceRepository.save(oldTrace);
        pipelineTraceRepository.save(recentTrace);
        assertEquals(2, pipelineTraceRepository.count());

        retentionManager.cleanupOldData();

        List<PipelineTrace> remaining = pipelineTraceRepository.findAll();
        assertEquals(1, remaining.size(), "only the recent pipeline trace should survive retention");
        assertEquals("corr-recent", remaining.get(0).getCorrelationId());
    }

    // ---------- Domain event retention (plain DELETE - not a hypertable) ----------

    @Test
    void cleanupOldData_removesDomainEventsOlderThanRetentionPeriod_keepsRecentEvents() {
        // Default nexus.retention.events-days is 90.
        Instant old = Instant.now().minus(120, ChronoUnit.DAYS);
        Instant recent = Instant.now().minus(1, ChronoUnit.DAYS);
        UUID oldId = UUID.randomUUID();
        UUID recentId = UUID.randomUUID();
        domainEventRepository.save(new DomainEvent(oldId, EventType.TWIN_UPDATED, device.getId(), old, "test", Map.of(), "INFO", "corr-old"));
        domainEventRepository.save(new DomainEvent(recentId, EventType.TWIN_UPDATED, device.getId(), recent, "test", Map.of(), "INFO", "corr-recent"));
        assertEquals(2, domainEventRepository.count());

        retentionManager.cleanupOldData();

        List<DomainEvent> remaining = domainEventRepository.findAll();
        assertEquals(1, remaining.size(), "only the recent domain event should survive retention");
        assertEquals(recentId, remaining.get(0).eventId());
    }

    // ---------- Action history retention (plain DELETE - not a hypertable) ----------

    @Test
    void cleanupOldData_removesActionHistoryOlderThanRetentionPeriod_keepsRecentHistory() {
        // Default nexus.retention.actions-days is 90.
        Instant old = Instant.now().minus(120, ChronoUnit.DAYS);
        Instant recent = Instant.now().minus(1, ChronoUnit.DAYS);
        UUID oldId = UUID.randomUUID();
        UUID recentId = UUID.randomUUID();
        actionHistoryRepository.save(new ActionHistoryRecord(oldId, UUID.randomUUID(), device.getId(),
                ActionType.CREATE_ALERT, ExecutionStatus.SUCCESS, Map.of(), old, "old"));
        actionHistoryRepository.save(new ActionHistoryRecord(recentId, UUID.randomUUID(), device.getId(),
                ActionType.CREATE_ALERT, ExecutionStatus.SUCCESS, Map.of(), recent, "recent"));
        assertEquals(2, actionHistoryRepository.count());

        retentionManager.cleanupOldData();

        List<ActionHistoryRecord> remaining = actionHistoryRepository.findAll();
        assertEquals(1, remaining.size(), "only the recent action history record should survive retention");
        assertEquals(recentId, remaining.get(0).getRequestId());
    }

    // ---------- All four operations run together, in one real call ----------

    @Test
    void cleanupOldData_runsAllFourRetentionOperationsInOneRealInvocation() {
        // Guards against the scenario where one operation's SQL failure
        // (drop_chunks is caught per-operation in Java, but a SQL-level
        // error can mark the surrounding @Transactional transaction as
        // aborted in Postgres) silently prevents the other three operations
        // from actually taking effect, even though each has its own
        // try/catch. All four tables get one old and one recent row; a
        // single cleanupOldData() call must correctly prune all four.
        Instant oldTelemetry = Instant.now().minus(40, ChronoUnit.DAYS);
        Instant recentTelemetry = Instant.now().minus(1, ChronoUnit.DAYS);
        telemetryRepository.save(new TelemetryRecord(UUID.randomUUID(), device.getId(), oldTelemetry, "TEMPERATURE_SENSOR", 1.0, "CELSIUS"));
        telemetryRepository.save(new TelemetryRecord(UUID.randomUUID(), device.getId(), recentTelemetry, "TEMPERATURE_SENSOR", 2.0, "CELSIUS"));

        long oldTraceStart = Instant.now().minus(20, ChronoUnit.DAYS).toEpochMilli();
        long recentTraceStart = Instant.now().minus(1, ChronoUnit.DAYS).toEpochMilli();
        pipelineTraceRepository.save(PipelineTrace.of("corr-1", device.getId().toString(), PipelineStage.TELEMETRY, oldTraceStart, "SUCCESS", null, Map.of()));
        pipelineTraceRepository.save(PipelineTrace.of("corr-2", device.getId().toString(), PipelineStage.TELEMETRY, recentTraceStart, "SUCCESS", null, Map.of()));

        Instant oldEvent = Instant.now().minus(120, ChronoUnit.DAYS);
        Instant recentEvent = Instant.now().minus(1, ChronoUnit.DAYS);
        domainEventRepository.save(new DomainEvent(UUID.randomUUID(), EventType.TWIN_UPDATED, device.getId(), oldEvent, "test", Map.of(), "INFO", "corr-3"));
        domainEventRepository.save(new DomainEvent(UUID.randomUUID(), EventType.TWIN_UPDATED, device.getId(), recentEvent, "test", Map.of(), "INFO", "corr-4"));

        Instant oldAction = Instant.now().minus(120, ChronoUnit.DAYS);
        Instant recentAction = Instant.now().minus(1, ChronoUnit.DAYS);
        actionHistoryRepository.save(new ActionHistoryRecord(UUID.randomUUID(), UUID.randomUUID(), device.getId(),
                ActionType.CREATE_ALERT, ExecutionStatus.SUCCESS, Map.of(), oldAction, "old"));
        actionHistoryRepository.save(new ActionHistoryRecord(UUID.randomUUID(), UUID.randomUUID(), device.getId(),
                ActionType.CREATE_ALERT, ExecutionStatus.SUCCESS, Map.of(), recentAction, "recent"));

        retentionManager.cleanupOldData();

        assertEquals(1, telemetryRepository.count(), "telemetry retention must take effect");
        assertEquals(1, pipelineTraceRepository.count(), "pipeline trace retention must take effect");
        assertEquals(1, domainEventRepository.count(), "domain event retention must take effect");
        assertEquals(1, actionHistoryRepository.count(), "action history retention must take effect");
    }

    // ---------- Empty database ----------

    @Test
    void cleanupOldData_withNoDataAtAll_completesWithoutThrowing() {
        assertEquals(0, telemetryRepository.count());
        assertEquals(0, pipelineTraceRepository.count());
        assertEquals(0, domainEventRepository.count());
        assertEquals(0, actionHistoryRepository.count());

        assertDoesNotThrow(() -> retentionManager.cleanupOldData(),
                "retention must not fail merely because there is no eligible data");
    }

    // ---------- Repeated execution ----------

    @Test
    void cleanupOldData_calledTwiceInSuccession_secondCallIsSafeAndFindsNothingLeftToDelete() {
        Instant old = Instant.now().minus(40, ChronoUnit.DAYS);
        Instant recent = Instant.now().minus(1, ChronoUnit.DAYS);
        UUID recentId = UUID.randomUUID();
        telemetryRepository.save(new TelemetryRecord(UUID.randomUUID(), device.getId(), old, "TEMPERATURE_SENSOR", 1.0, "CELSIUS"));
        telemetryRepository.save(new TelemetryRecord(recentId, device.getId(), recent, "TEMPERATURE_SENSOR", 2.0, "CELSIUS"));

        retentionManager.cleanupOldData();
        assertEquals(1, telemetryRepository.count());

        assertDoesNotThrow(() -> retentionManager.cleanupOldData(),
                "a second, immediate invocation with nothing new to delete must not fail");
        assertEquals(1, telemetryRepository.count(), "the recent row must still be present after the second call");
        assertEquals(recentId, telemetryRepository.findAll().get(0).getId());
    }
}
