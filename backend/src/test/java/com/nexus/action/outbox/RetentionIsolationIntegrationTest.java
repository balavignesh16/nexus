package com.nexus.action.outbox;

import com.nexus.infrastructure.retention.RetentionManager;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F2: ActionHistory retention against real PostgreSQL. Rows referenced by current desired Twin state or by an outbox row are
 * retained, nothing is cleared, eligible rows are still deleted, every category has its own transaction so one failing category
 * cannot roll back the others, failures are logged and surfaced, repeated runs are idempotent and the records survive a context
 * (backend) restart. Execution is ordered because step 2 deliberately runs in a fresh application context.
 */
@SpringBootTest
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RetentionIsolationIntegrationTest {

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

    @Autowired RetentionManager retention;
    @Autowired DataSource dataSource;
    @Autowired MeterRegistry meters;

    private static UUID device, referenced, plain, pending, resolved, recent;

    private static Map<String, Object> cols(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private long count(Connection c, String sql) throws Exception {
        return OutboxSql.count(c, sql);
    }

    // ------------------------------------------------------------------------------------------------ step 1

    @Test
    @Order(1)
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void referencedAndObligationRowsAreRetainedEligibleRowsAreDeletedAndNothingIsCleared() throws Exception {
        Instant old = Instant.now().minus(100, ChronoUnit.DAYS);
        Instant fresh = Instant.now().minus(1, ChronoUnit.DAYS);
        try (Connection c = dataSource.getConnection(); var s = c.createStatement()) {
            device = OutboxSql.device(c);
            referenced = OutboxSql.action(c, device, "SUCCESS", old);
            plain = OutboxSql.action(c, device, "SUCCESS", old);
            pending = OutboxSql.action(c, device, "ACCEPTED_FOR_DISPATCH", old);
            resolved = OutboxSql.action(c, device, "SUCCESS", old);
            recent = OutboxSql.action(c, device, "SUCCESS", fresh);
            s.executeUpdate("INSERT INTO current_desired_twin_state(device_id,target_key,legacy_capability_type,subtarget_key,capability_key,value_type,boolean_value,source,command_id,requested_at,accepted_for_dispatch_at) "
                    + "VALUES ('" + device + "','legacy:LED','LED','','led','BOOLEAN',true,'MANUAL_COMMAND','" + referenced + "','" + Timestamp.from(old) + "','" + Timestamp.from(old) + "')");
            s.executeUpdate("INSERT INTO current_twin_verification_state(device_id,target_key,subtarget_key,desired_command_id,verification_status,verification_updated_at) "
                    + "VALUES ('" + device + "','legacy:LED','','" + referenced + "','PENDING',NOW())");
            Map<String, Object> row = OutboxSql.queued(pending, device, old);
            OutboxSql.insert(c, row);
            row = OutboxSql.queued(resolved, device, old);
            row.putAll(cols("state", "PUBLISHED", "attempt_count", 1, "next_attempt_at", null,
                    "terminal_at", Timestamp.from(old), "first_publish_confirmed_at", Timestamp.from(old)));
            OutboxSql.insert(c, row);
            s.executeUpdate("INSERT INTO domain_events(event_id,event_type,timestamp,severity) VALUES (gen_random_uuid(),'RET_OLD_EVENT','" + Timestamp.from(Instant.now().minus(200, ChronoUnit.DAYS)) + "','INFO')");
        }

        RetentionManager.Report report = retention.runCleanup();

        assertFalse(report.failed(), "no category may fail: " + report);
        assertEquals(2, report.of(RetentionManager.ACTION_HISTORY).rows(), "the plain old command and the resolved command's action row");
        assertEquals(1, report.of(RetentionManager.COMMAND_OUTBOX).rows(), "only the resolved delivery row is purged");
        assertTrue(report.of(RetentionManager.DOMAIN_EVENTS).rows() >= 1);
        try (Connection c = dataSource.getConnection()) {
            assertEquals(1, count(c, "SELECT COUNT(*) FROM action_history WHERE request_id = '" + referenced + "'"), "1. an old row referenced by current desired state remains");
            assertEquals(0, count(c, "SELECT COUNT(*) FROM action_history WHERE request_id = '" + plain + "'"), "2. an old unreferenced row is deleted");
            assertEquals(1, count(c, "SELECT COUNT(*) FROM current_desired_twin_state WHERE device_id = '" + device + "' AND command_id = '" + referenced + "' AND boolean_value = true"), "3. the desired state is intact and still points at the retained row");
            assertEquals(1, count(c, "SELECT COUNT(*) FROM current_twin_verification_state WHERE device_id = '" + device + "' AND desired_command_id = '" + referenced + "'"), "verification evidence is intact");
            assertEquals(1, count(c, "SELECT COUNT(*) FROM command_outbox WHERE command_id = '" + pending + "' AND state = 'QUEUED'"), "4. an unresolved obligation remains");
            assertEquals(1, count(c, "SELECT COUNT(*) FROM action_history WHERE request_id = '" + pending + "'"), "4. and so does its action row");
            assertEquals(0, count(c, "SELECT COUNT(*) FROM command_outbox WHERE command_id = '" + resolved + "'"));
            assertEquals(0, count(c, "SELECT COUNT(*) FROM action_history WHERE request_id = '" + resolved + "'"));
            assertEquals(1, count(c, "SELECT COUNT(*) FROM action_history WHERE request_id = '" + recent + "'"), "a recent row is never deleted");
            assertEquals(0, count(c, "SELECT COUNT(*) FROM domain_events WHERE event_type = 'RET_OLD_EVENT'"));
        }
    }

    // ------------------------------------------------------------------------------------------------ step 2 (fresh context = restart)

    @Test
    @Order(2)
    void afterABackendRestartTheRecordsArePreservedAndRepeatedRetentionIsIdempotent() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            assertEquals(1, count(c, "SELECT COUNT(*) FROM action_history WHERE request_id = '" + referenced + "'"));
            assertEquals(1, count(c, "SELECT COUNT(*) FROM current_desired_twin_state WHERE command_id = '" + referenced + "'"));
            assertEquals(1, count(c, "SELECT COUNT(*) FROM command_outbox WHERE command_id = '" + pending + "' AND state = 'QUEUED'"));
            assertEquals(1, count(c, "SELECT COUNT(*) FROM action_history WHERE request_id = '" + pending + "'"));
        }
        for (int run = 1; run <= 2; run++) {
            RetentionManager.Report again = retention.runCleanup();
            assertFalse(again.failed(), "run " + run + ": " + again);
            assertEquals(0, again.of(RetentionManager.ACTION_HISTORY).rows(), "run " + run + ": nothing further is eligible");
            assertEquals(0, again.of(RetentionManager.COMMAND_OUTBOX).rows());
            assertEquals(0, again.of(RetentionManager.DOMAIN_EVENTS).rows());
        }
        assertDoesNotThrow(retention::cleanupOldData);
        try (Connection c = dataSource.getConnection()) {
            assertEquals(1, count(c, "SELECT COUNT(*) FROM action_history WHERE request_id = '" + referenced + "'"), "still retained after repeated runs");
            assertEquals(1, count(c, "SELECT COUNT(*) FROM command_outbox WHERE command_id = '" + pending + "'"));
        }
    }

    // ------------------------------------------------------------------------------------------------ step 3 isolation

    @Test
    @Order(3)
    void aFailingCategoryIsLoggedAndSurfacedAndDoesNotRollBackTheOthers() throws Exception {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RetentionManager.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Instant old = Instant.now().minus(100, ChronoUnit.DAYS);
        UUID bomb;
        try (Connection c = dataSource.getConnection(); var s = c.createStatement()) {
            // A constraint-style failure inside the action_history category only.
            s.execute("CREATE OR REPLACE FUNCTION retention_test_bomb() RETURNS trigger AS $$ BEGIN IF OLD.message = 'retention-bomb' THEN RAISE EXCEPTION 'retention bomb'; END IF; RETURN OLD; END $$ LANGUAGE plpgsql");
            s.execute("CREATE TRIGGER trg_retention_test_bomb BEFORE DELETE ON action_history FOR EACH ROW EXECUTE FUNCTION retention_test_bomb()");
            bomb = OutboxSql.action(c, device, "SUCCESS", old);
            s.executeUpdate("UPDATE action_history SET message = 'retention-bomb' WHERE request_id = '" + bomb + "'");
            s.executeUpdate("INSERT INTO domain_events(event_id,event_type,timestamp,severity) VALUES (gen_random_uuid(),'RET_ISOLATION_EVENT','" + Timestamp.from(Instant.now().minus(200, ChronoUnit.DAYS)) + "','INFO')");
        }
        try {
            RetentionManager.Report report = retention.runCleanup();

            assertEquals(List.of(RetentionManager.ACTION_HISTORY), report.failedCategories(), "only the blocked category fails");
            assertTrue(report.of(RetentionManager.DOMAIN_EVENTS).success(), "5. domain-event retention succeeds when action-history retention hits a constraint");
            assertTrue(report.of(RetentionManager.DOMAIN_EVENTS).rows() >= 1);
            assertTrue(report.of(RetentionManager.TELEMETRY).success());
            assertTrue(report.of(RetentionManager.PIPELINE_TRACES).success());
            assertTrue(report.of(RetentionManager.COMMAND_OUTBOX).success());
            try (Connection c = dataSource.getConnection()) {
                assertEquals(0, count(c, "SELECT COUNT(*) FROM domain_events WHERE event_type = 'RET_ISOLATION_EVENT'"), "the event deletion was committed on its own, not rolled back");
                assertEquals(1, count(c, "SELECT COUNT(*) FROM action_history WHERE request_id = '" + bomb + "'"), "the blocked row stays, untouched");
                assertEquals(1, count(c, "SELECT COUNT(*) FROM action_history WHERE request_id = '" + referenced + "'"));
            }
            // The scheduled entry point surfaces the failure after attempting every category.
            RetentionManager.RetentionFailedException surfaced = assertThrows(RetentionManager.RetentionFailedException.class, retention::cleanupOldData);
            assertEquals(List.of(RetentionManager.ACTION_HISTORY), surfaced.getReport().failedCategories());
            List<ILoggingEvent> errors = appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
            assertTrue(errors.stream().anyMatch(e -> e.getFormattedMessage().contains("ACTION_HISTORY") && e.getThrowableProxy() != null),
                    "the failure is logged at ERROR with its cause, not swallowed");
            assertTrue(meters.get("nexus.retention.category.failures").tag("category", RetentionManager.ACTION_HISTORY).counter().count() >= 1.0,
                    "and counted");
        } finally {
            try (Connection c = dataSource.getConnection(); var s = c.createStatement()) {
                s.execute("DROP TRIGGER IF EXISTS trg_retention_test_bomb ON action_history");
                s.execute("DROP FUNCTION IF EXISTS retention_test_bomb()");
            }
            logger.detachAppender(appender);
        }
        // Once the obstacle is gone the same run succeeds and removes the row: nothing was left half done.
        RetentionManager.Report recovered = retention.runCleanup();
        assertFalse(recovered.failed(), recovered.toString());
        assertEquals(1, recovered.of(RetentionManager.ACTION_HISTORY).rows());
    }
}
