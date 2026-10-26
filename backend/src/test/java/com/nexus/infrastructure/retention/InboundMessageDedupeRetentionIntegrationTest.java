package com.nexus.infrastructure.retention;

import com.nexus.building.domain.Building;
import com.nexus.building.persistence.BuildingRepository;
import com.nexus.device.domain.Device;
import com.nexus.device.domain.DeviceMode;
import com.nexus.device.domain.DeviceRepository;
import com.nexus.device.domain.DeviceStatus;
import com.nexus.device.domain.DeviceType;
import com.nexus.site.domain.Site;
import com.nexus.site.persistence.SiteRepository;
import com.nexus.space.domain.Space;
import com.nexus.space.persistence.SpaceRepository;
import com.nexus.telemetry.api.dto.TelemetryRequest;
import com.nexus.telemetry.application.TelemetryService;
import com.nexus.telemetry.application.TelemetrySource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Retention of the telemetry inbox (R17, R19; ledger P4-082): entries are purged by database age at the configured window with an exact
 * boundary, in bounded independent batches, without touching telemetry, and a purged id is accepted again (the documented limit). A
 * failing purge never rolls back the other categories.
 */
@SpringBootTest
@Testcontainers
class InboundMessageDedupeRetentionIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("mqtt.broker.url", () -> "tcp://127.0.0.1:1");
        registry.add("mqtt.client.id", () -> "p45-retention-" + UUID.randomUUID());
    }

    @Autowired RetentionManager retention;
    @Autowired TelemetryService telemetry;
    @Autowired JdbcTemplate jdbc;
    @Autowired SiteRepository sites;
    @Autowired BuildingRepository buildings;
    @Autowired SpaceRepository spaces;
    @Autowired DeviceRepository devices;

    private UUID device;

    @BeforeEach
    void freshDevice() {
        Instant now = Instant.now();
        Site site = sites.save(new Site(UUID.randomUUID(), "Ret", "test", now, now));
        Building building = buildings.save(new Building(UUID.randomUUID(), site, "B", "test", now, now));
        Space space = spaces.save(new Space(UUID.randomUUID(), building, "S", "test", now, now));
        Device d = new Device(UUID.randomUUID(), space, "R", DeviceType.CUSTOM, "NEXUS", "TEST", "P45R-" + UUID.randomUUID(), null, DeviceMode.SIMULATED);
        d.update("R", DeviceStatus.ACTIVE, null);
        device = devices.save(d).getId();
        jdbc.update("DELETE FROM inbound_message_dedupe");
    }

    @AfterEach
    void restoreBatching() {
        ReflectionTestUtils.setField(retention, "inboundDedupeBatchSize", 1000);
        ReflectionTestUtils.setField(retention, "inboundDedupeMaxBatches", 100);
        ReflectionTestUtils.setField(retention, "inboundDedupeHours", 168);
    }

    private void entry(String id, String ageSql) {
        jdbc.update("INSERT INTO inbound_message_dedupe(device_id,message_id,source,fingerprint,received_at) VALUES (?,?,?,?, NOW() - " + ageSql + ")",
                device, id, "MQTT", "a".repeat(64));
    }

    private List<String> remaining() {
        return jdbc.queryForList("SELECT message_id FROM inbound_message_dedupe WHERE device_id = ? ORDER BY message_id", String.class, device);
    }

    @Test
    void theWindowBoundaryIsExactOnTheDatabaseClockAndOnlyOldEntriesGo() {
        entry("fresh", "INTERVAL '0 seconds'");
        entry("just-inside", "INTERVAL '167 hours 59 minutes'");
        entry("just-outside", "INTERVAL '168 hours 1 minute'");
        entry("very-old", "INTERVAL '30 days'");

        RetentionManager.Report report = retention.runCleanup();

        assertTrue(report.of(RetentionManager.INBOUND_MESSAGE_DEDUPE).success());
        assertEquals(2, report.of(RetentionManager.INBOUND_MESSAGE_DEDUPE).rows());
        assertEquals(List.of("fresh", "just-inside"), remaining());
    }

    @Test
    void thePurgeIsBoundedPerRunAndContinuesOnTheNextRun() {
        for (int i = 0; i < 10; i++) entry("old-" + i, "INTERVAL '10 days'");
        ReflectionTestUtils.setField(retention, "inboundDedupeBatchSize", 3);
        ReflectionTestUtils.setField(retention, "inboundDedupeMaxBatches", 2);

        assertEquals(6, retention.runCleanup().of(RetentionManager.INBOUND_MESSAGE_DEDUPE).rows(), "at most batch-size x max-batches rows per run");
        assertEquals(4, remaining().size());
        assertEquals(4, retention.runCleanup().of(RetentionManager.INBOUND_MESSAGE_DEDUPE).rows(), "the next run finishes the remainder");
        assertEquals(0, remaining().size());
    }

    @Test
    void aPurgedIdIsAcceptedAgainAndTelemetryIsNeverTouchedByThePurge() {
        Instant sample = Instant.parse("2026-10-17T12:00:00Z");
        TelemetryRequest request = new TelemetryRequest(device, sample, "TEMPERATURE", 21.5, "CELSIUS", null, null, "expiring");
        assertNull(telemetry.processTelemetry(request, TelemetrySource.MQTT).duplicate());
        assertTrue(telemetry.processTelemetry(request, TelemetrySource.MQTT).repeatsAcceptedMessage(), "inside the window it is a duplicate");
        long telemetryBefore = jdbc.queryForObject("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", Long.class, device);

        jdbc.update("UPDATE inbound_message_dedupe SET received_at = NOW() - INTERVAL '169 hours' WHERE device_id = ?", device);
        retention.runCleanup();

        assertEquals(0, remaining().size());
        assertEquals(telemetryBefore, jdbc.queryForObject("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", Long.class, device), "the purge only removes dedupe entries");
        assertFalse(telemetry.processTelemetry(request, TelemetrySource.MQTT).repeatsAcceptedMessage(),
                "documented limit (R17): once the entry is purged the id is accepted again");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM telemetry_history WHERE device_id = ?", Long.class, device));
    }

    @Test
    void aFailingPurgeLeavesEveryOtherCategoryCommitted() {
        entry("old", "INTERVAL '10 days'");
        jdbc.execute("ALTER TABLE inbound_message_dedupe RENAME TO inbound_message_dedupe_blocked");
        try {
            RetentionManager.Report report = retention.runCleanup();
            assertEquals(List.of(RetentionManager.INBOUND_MESSAGE_DEDUPE), report.failedCategories(), "only the dedupe category fails");
            assertTrue(report.of(RetentionManager.TELEMETRY).success());
            assertTrue(report.of(RetentionManager.DOMAIN_EVENTS).success());
            assertTrue(report.of(RetentionManager.COMMAND_OUTBOX).success());
            assertTrue(report.of(RetentionManager.ACTION_HISTORY).success());
        } finally {
            jdbc.execute("ALTER TABLE inbound_message_dedupe_blocked RENAME TO inbound_message_dedupe");
        }
        assertEquals(List.of("old"), remaining(), "the failed purge removed nothing");
    }
}
