package com.nexus.action.outbox;

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
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Ledger P4-139: the V39 command_state_history can never block the retention of action_history. The real RetentionManager runs
 * against a database holding history for every kind of old command, including legacy DELIVERY_UNKNOWN commands that have no
 * outbox row; no category fails, history leaves only with its action row, and obligations still protect their rows.
 */
@SpringBootTest(properties = "nexus.command.outbox.dispatcher-enabled=false")
@Testcontainers
class CommandStateHistoryRetentionIntegrationTest {

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
    }

    @Autowired RetentionManager retention;
    @Autowired DataSource dataSource;

    private static void fact(Connection c, UUID command, int seq, String factType, String source) throws Exception {
        try (PreparedStatement p = c.prepareStatement("INSERT INTO command_state_history (command_id, sequence, fact_type, to_state, attempt, reason_code, source, occurred_at)"
                + " VALUES (?, ?, ?, NULL, 0, 'X', ?, ?)")) {
            p.setObject(1, command);
            p.setInt(2, seq);
            p.setString(3, factType);
            p.setString(4, source);
            p.setTimestamp(5, Timestamp.from(Instant.now()));
            p.executeUpdate();
        }
    }

    private static long count(Connection c, String sql) throws Exception {
        return OutboxSql.count(c, sql);
    }

    @Test
    void historyNeverBlocksRetentionAndLeavesOnlyWithItsActionRow() throws Exception {
        Instant old = Instant.now().minus(100, ChronoUnit.DAYS);
        UUID device, plain, legacyUnknown, resolved, pending, recent;
        try (Connection c = dataSource.getConnection()) {
            device = OutboxSql.device(c);
            plain = OutboxSql.action(c, device, "SUCCESS", old);
            legacyUnknown = OutboxSql.action(c, device, "ACCEPTED_FOR_DISPATCH", old);
            resolved = OutboxSql.action(c, device, "SUCCESS", old);
            pending = OutboxSql.action(c, device, "ACCEPTED_FOR_DISPATCH", old);
            recent = OutboxSql.action(c, device, "SUCCESS", Instant.now().minus(1, ChronoUnit.DAYS));
            fact(c, plain, 1, "ACCEPTED", "ACCEPTANCE");
            fact(c, plain, 2, "PUBLISHED", "DISPATCHER");
            fact(c, legacyUnknown, 1, "DELIVERY_UNKNOWN", "MIGRATION");
            fact(c, resolved, 1, "ACCEPTED", "ACCEPTANCE");
            fact(c, resolved, 2, "PUBLISHED", "DISPATCHER");
            fact(c, pending, 1, "ACCEPTED", "ACCEPTANCE");
            fact(c, recent, 1, "ACCEPTED", "ACCEPTANCE");
            OutboxSql.insert(c, OutboxSql.queued(pending, device, old));
            Map<String, Object> row = OutboxSql.queued(resolved, device, old);
            row.putAll(cols("state", "PUBLISHED", "attempt_count", 1, "next_attempt_at", null,
                    "terminal_at", Timestamp.from(old), "first_publish_confirmed_at", Timestamp.from(old)));
            OutboxSql.insert(c, row);
        }

        RetentionManager.Report report = retention.runCleanup();

        assertFalse(report.failed(), "no category may fail: " + report);
        try (Connection c = dataSource.getConnection()) {
            for (UUID gone : new UUID[]{plain, legacyUnknown, resolved}) {
                assertEquals(0, count(c, "SELECT COUNT(*) FROM action_history WHERE request_id = '" + gone + "'"), "old row deleted: " + gone);
                assertEquals(0, count(c, "SELECT COUNT(*) FROM command_state_history WHERE command_id = '" + gone + "'"), "its history left with it: " + gone);
            }
            assertEquals(1, count(c, "SELECT COUNT(*) FROM action_history WHERE request_id = '" + pending + "'"), "an unresolved obligation keeps its action row");
            assertEquals(1, count(c, "SELECT COUNT(*) FROM command_state_history WHERE command_id = '" + pending + "'"), "and its history");
            assertEquals(1, count(c, "SELECT COUNT(*) FROM command_outbox WHERE command_id = '" + pending + "' AND state = 'QUEUED'"));
            assertEquals(1, count(c, "SELECT COUNT(*) FROM action_history WHERE request_id = '" + recent + "'"));
            assertEquals(1, count(c, "SELECT COUNT(*) FROM command_state_history WHERE command_id = '" + recent + "'"));
        }
        assertFalse(retention.runCleanup().failed(), "idempotent");
    }

    private static Map<String, Object> cols(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
