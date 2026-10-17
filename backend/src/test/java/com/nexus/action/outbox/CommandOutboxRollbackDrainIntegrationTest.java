package com.nexus.action.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Forensic finding H1, rollback half: the application runs with the outbox flag OFF (as after an operator rolled it back) while
 * obligations created earlier still exist. They must NOT be stranded (contract R22: rollback is drain-only): a fresh queued
 * command is delivered, a crashed worker's expired lease is recovered as uncertain, and an expired command is dead-lettered,
 * all by the unconditional dispatcher, with the flag OFF and no new obligation created.
 */
@SpringBootTest(properties = "nexus.command.outbox.dispatcher-enabled=true")
@Testcontainers
@Import(CommandOutboxRollbackDrainIntegrationTest.Scripted.class)
class CommandOutboxRollbackDrainIntegrationTest {

    @TestConfiguration
    static class Scripted {
        @Bean
        @Primary
        FakeBroker fakeBroker() { return new FakeBroker(); }
    }

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));
    @Container
    static GenericContainer<?> mosquitto = new GenericContainer<>("eclipse-mosquitto:2.0")
            .withExposedPorts(1883)
            .withCopyFileToContainer(MountableFile.forClasspathResource("mosquitto-test/mosquitto.conf"), "/mosquitto/config/mosquitto.conf")
            .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("mqtt.broker.url", () -> "tcp://" + mosquitto.getHost() + ":" + mosquitto.getMappedPort(1883));
        registry.add("mqtt.client.id", () -> "nexus-outbox-drain-" + UUID.randomUUID());
    }

    @Autowired CommandOutboxSettings settings;
    @Autowired FakeBroker broker;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    private static void await(BooleanSupplier c, String what) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!c.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(50);
        }
    }

    private String state(UUID command) {
        return jdbc.queryForObject("SELECT state FROM command_outbox WHERE command_id = ?", String.class, command);
    }

    @Test
    @Timeout(90)
    void withTheFlagOffExistingObligationsAreDeliveredRecoveredAndExpiredNotStranded() throws Exception {
        assertFalse(settings.enabled(), "the flag is OFF");
        UUID deviceFresh, deviceLost, deviceExpired, fresh, lost, expired;
        try (Connection c = dataSource.getConnection()) {
            deviceFresh = OutboxSql.device(c);
            deviceLost = OutboxSql.device(c);
            deviceExpired = OutboxSql.device(c);
            Instant due = Instant.now().minusSeconds(2);
            fresh = OutboxSql.action(c, deviceFresh, "ACCEPTED_FOR_DISPATCH", due);
            OutboxSql.insert(c, OutboxSql.queued(fresh, deviceFresh, due));
            // a worker of the previous run crashed after claiming: IN_FLIGHT with a lease that ended
            lost = OutboxSql.action(c, deviceLost, "ACCEPTED_FOR_DISPATCH", due);
            Map<String, Object> row = OutboxSql.queued(lost, deviceLost, due);
            row.putAll(Map.of("state", "IN_FLIGHT", "attempt_count", 1, "lease_owner", "dead-instance/1"));
            row.put("next_attempt_at", null);
            row.put("lease_expires_at", Timestamp.from(Instant.now().minusSeconds(30)));
            OutboxSql.insert(c, row);
            Instant old = Instant.now().minusSeconds(120);
            expired = OutboxSql.action(c, deviceExpired, "ACCEPTED_FOR_DISPATCH", old);
            OutboxSql.insert(c, OutboxSql.queued(expired, deviceExpired, old));
        }

        await(() -> "PUBLISHED".equals(state(fresh)), "the queued command to be delivered");
        await(() -> "DEAD_LETTER".equals(state(lost)), "the lost lease to be recovered");
        await(() -> "DEAD_LETTER".equals(state(expired)), "the expired command to be dead-lettered");

        assertEquals("SUCCESS", jdbc.queryForObject("SELECT status FROM action_history WHERE request_id = ?", String.class, fresh));
        assertEquals("UNCERTAIN_EXPIRED", jdbc.queryForObject("SELECT terminal_reason FROM command_outbox WHERE command_id = ?", String.class, lost));
        assertEquals("EXPIRED", jdbc.queryForObject("SELECT terminal_reason FROM command_outbox WHERE command_id = ?", String.class, expired));
        assertEquals(1, broker.calls.size(), "only the fresh command was sent; the lost and expired ones were not");
        assertTrue(broker.calls.get(0).text().contains(fresh.toString()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE state IN ('QUEUED','IN_FLIGHT','RETRY_WAIT')", Integer.class),
                "nothing is left stranded");
        assertFalse(settings.enabled(), "and the flag stayed OFF throughout");
    }
}
