package com.nexus.action.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.action.domain.ActionHistoryRecord;
import com.nexus.action.domain.ActionHistoryRepository;
import com.nexus.action.domain.ActionType;
import com.nexus.action.domain.ExecutionStatus;
import com.nexus.infrastructure.retention.RetentionManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The V37 table through the real Spring application (Hibernate schema validation, repositories, configuration) and
 * the real RetentionManager against disposable TimescaleDB/PostgreSQL. Nothing here switches dispatch to the outbox:
 * the legacy path must still create no outbox row.
 */
@SpringBootTest
@Testcontainers
class CommandOutboxPersistenceIntegrationTest {

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

    @Autowired DataSource dataSource;
    @Autowired CommandOutboxRepository outbox;
    @Autowired ActionHistoryRepository actionHistory;
    @Autowired CommandOutboxSettings settings;
    @Autowired RetentionManager retention;
    @Autowired ObjectMapper mapper;

    // ---------------------------------------------------------------------------------------------- mapping

    @Test
    void theOutboxFeatureFlagIsOffInTheRunningApplication() {
        assertFalse(settings.enabled());
        assertEquals(Duration.ofSeconds(15), settings.deliveryWindow());
    }

    @Test
    void aQueuedEntityRoundTripsThroughHibernateWithItsFrozenPayload() throws Exception {
        UUID device, command;
        try (Connection c = dataSource.getConnection()) {
            device = OutboxSql.device(c);
            command = OutboxSql.action(c, device, "ACCEPTED_FOR_DISPATCH", Instant.now());
        }
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        OutboxPayload payload = OutboxPayload.create(command, "LED", "SET_STATE", true, now.getEpochSecond(), mapper);
        outbox.saveAndFlush(CommandOutboxEntity.queued(command, device, payload, Duration.ofSeconds(15), 3, "corr-roundtrip", now));

        CommandOutboxEntity loaded = outbox.findById(command).orElseThrow();
        assertEquals(command, loaded.getCommandId());
        assertEquals(device, loaded.getDeviceId());
        assertEquals("nexus/devices/" + device + "/commands", loaded.getTopic());
        assertEquals(payload, loaded.payload());
        assertTrue(loaded.payload().verify());
        assertEquals(OutboxState.QUEUED, loaded.getState());
        assertEquals(0, loaded.getAttemptCount());
        assertEquals(3, loaded.getMaxAttempts());
        assertEquals(Instant.ofEpochSecond(payload.epochSeconds() + 15), loaded.getExpiresAt());
        assertEquals("corr-roundtrip", loaded.getCorrelationId());
        assertNull(loaded.getLeaseOwner());
        assertNull(loaded.getTerminalReason());
        assertTrue(outbox.countByState(OutboxState.QUEUED) >= 1);
    }

    // ---------------------------------------------------------------------------------------------- compatibility

    @Test
    void theExistingActionHistoryPathStillWorksAndCreatesNoOutboxRow() throws Exception {
        UUID device;
        try (Connection c = dataSource.getConnection()) {
            device = OutboxSql.device(c);
        }
        long before = outbox.count();
        UUID request = UUID.randomUUID();
        actionHistory.saveAndFlush(new ActionHistoryRecord(request, UUID.randomUUID(), device, ActionType.SEND_COMMAND,
                ExecutionStatus.ACCEPTED_FOR_DISPATCH, Map.of("capability", "LED"), Instant.now(), "Durably accepted; MQTT delivery not yet known"));
        assertEquals(ExecutionStatus.ACCEPTED_FOR_DISPATCH, actionHistory.findById(request).orElseThrow().getStatus());
        assertEquals(before, outbox.count(), "the legacy acceptance path writes no outbox row while the outbox is not in use");
    }

    // ---------------------------------------------------------------------------------------------- retention

    /** Like Map.of but allows null values (a column explicitly set to NULL). */
    private static Map<String, Object> cols(Object... keyValues) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) m.put((String) keyValues[i], keyValues[i + 1]);
        return m;
    }

    private static void insertOld(Connection c, UUID command, UUID device, Map<String, Object> overrides) throws Exception {
        Instant old = Instant.now().minus(100, ChronoUnit.DAYS);
        Map<String, Object> row = OutboxSql.queued(command, device, old);
        row.putAll(overrides);
        OutboxSql.insert(c, row);
    }

    @Test
    void retentionNeverDestroysPendingOrInFlightObligationsNorTheirActionRows() throws Exception {
        UUID device, queued, inFlight, retryWait;
        Instant old = Instant.now().minus(100, ChronoUnit.DAYS);
        try (Connection c = dataSource.getConnection()) {
            device = OutboxSql.device(c);
            queued = OutboxSql.action(c, device, "ACCEPTED_FOR_DISPATCH", old);
            inFlight = OutboxSql.action(c, device, "ACCEPTED_FOR_DISPATCH", old);
            retryWait = OutboxSql.action(c, device, "ACCEPTED_FOR_DISPATCH", old);
            insertOld(c, queued, device, cols());
            insertOld(c, inFlight, device, cols("state", "IN_FLIGHT", "attempt_count", 1, "next_attempt_at", null,
                    "lease_owner", "worker-1", "lease_expires_at", Timestamp.from(old.plusSeconds(6))));
            insertOld(c, retryWait, device, cols("state", "RETRY_WAIT", "attempt_count", 1));
        }

        retention.cleanupOldData();

        try (Connection c = dataSource.getConnection()) {
            for (UUID command : new UUID[]{queued, inFlight, retryWait}) {
                assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM command_outbox WHERE command_id = '" + command + "'"), "outbox row survives " + command);
                assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM action_history WHERE request_id = '" + command + "'"), "action row survives " + command);
            }
        }
    }

    @Test
    void retentionPurgesResolvedObligationsAndThenTheirActionRowsButKeepsRecentOnes() throws Exception {
        UUID device, published, deadLettered, recentPublished, plainOld, plainRecent;
        Instant old = Instant.now().minus(100, ChronoUnit.DAYS);
        Instant recent = Instant.now().minus(1, ChronoUnit.DAYS);
        try (Connection c = dataSource.getConnection()) {
            device = OutboxSql.device(c);
            published = OutboxSql.action(c, device, "SUCCESS", old);
            deadLettered = OutboxSql.action(c, device, "FAILURE", old);
            recentPublished = OutboxSql.action(c, device, "SUCCESS", recent);
            plainOld = OutboxSql.action(c, device, "SUCCESS", old);          // a legacy command: no outbox row
            plainRecent = OutboxSql.action(c, device, "SUCCESS", recent);
            insertOld(c, published, device, cols("state", "PUBLISHED", "attempt_count", 1, "next_attempt_at", null,
                    "terminal_at", Timestamp.from(old), "first_publish_confirmed_at", Timestamp.from(old)));
            insertOld(c, deadLettered, device, cols("state", "DEAD_LETTER", "next_attempt_at", null,
                    "terminal_at", Timestamp.from(old), "terminal_reason", "EXPIRED"));
            Map<String, Object> row = OutboxSql.queued(recentPublished, device, recent);
            row.putAll(cols("state", "PUBLISHED", "attempt_count", 1, "next_attempt_at", null,
                    "terminal_at", Timestamp.from(recent), "first_publish_confirmed_at", Timestamp.from(recent)));
            OutboxSql.insert(c, row);
        }

        retention.cleanupOldData();

        try (Connection c = dataSource.getConnection()) {
            for (UUID command : new UUID[]{published, deadLettered}) {
                assertEquals(0, OutboxSql.count(c, "SELECT COUNT(*) FROM command_outbox WHERE command_id = '" + command + "'"), "resolved outbox row purged " + command);
                assertEquals(0, OutboxSql.count(c, "SELECT COUNT(*) FROM action_history WHERE request_id = '" + command + "'"), "its action row purged afterwards " + command);
            }
            assertEquals(0, OutboxSql.count(c, "SELECT COUNT(*) FROM action_history WHERE request_id = '" + plainOld + "'"), "legacy behaviour: an old command without an outbox row is still removed");
            assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM action_history WHERE request_id = '" + plainRecent + "'"));
            assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM command_outbox WHERE command_id = '" + recentPublished + "'"), "a recent resolved row is kept");
            assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM action_history WHERE request_id = '" + recentPublished + "'"));
        }
    }
}
