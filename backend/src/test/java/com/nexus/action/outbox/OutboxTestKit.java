package com.nexus.action.outbox;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Wires the real P4.2 database components (history writer, delivery operations) against a disposable PostgreSQL without
 * starting Spring, so concurrency, fencing and lease behaviour are exercised on the real SQL with real connections.
 */
final class OutboxTestKit implements AutoCloseable {
    final HikariDataSource dataSource;
    final JdbcTemplate jdbc;
    final DataSourceTransactionManager transactions;
    final CommandStateHistory history;
    final OutboxDeliveryOperations ops;
    private final PostgreSQLContainer<?> db;

    static void migrate(PostgreSQLContainer<?> db) {
        Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()).load().migrate();
    }

    OutboxTestKit(PostgreSQLContainer<?> db) {
        this(db, null);
    }

    /** @param historyOverride a history writer to inject (for failure injection); null uses the real one */
    OutboxTestKit(PostgreSQLContainer<?> db, java.util.function.Function<JdbcTemplate, CommandStateHistory> historyOverride) {
        this(db, historyOverride, Clock.systemUTC());
    }

    /** @param applicationClock the clock the operations RECORD timestamps with; decisions always use the database clock */
    OutboxTestKit(PostgreSQLContainer<?> db, java.util.function.Function<JdbcTemplate, CommandStateHistory> historyOverride, Clock applicationClock) {
        this.db = db;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(db.getJdbcUrl());
        config.setUsername(db.getUsername());
        config.setPassword(db.getPassword());
        config.setMaximumPoolSize(24);
        this.dataSource = new HikariDataSource(config);
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new DataSourceTransactionManager(dataSource);
        this.history = historyOverride == null ? new CommandStateHistory(jdbc) : historyOverride.apply(jdbc);
        this.ops = new OutboxDeliveryOperations(jdbc, transactions, history, applicationClock, Duration.ofSeconds(30));
    }

    UUID device() {
        try (Connection c = dataSource.getConnection()) {
            return OutboxSql.device(c);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An ACCEPTED_FOR_DISPATCH command with its QUEUED obligation; {@code createdAt} drives order, payload time and expiry. */
    UUID enqueue(UUID device, Instant createdAt) {
        try (Connection c = dataSource.getConnection()) {
            UUID command = OutboxSql.action(c, device, "ACCEPTED_FOR_DISPATCH", createdAt);
            OutboxSql.insert(c, OutboxSql.queued(command, device, createdAt));
            return command;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A command created a little in the past so it is due now with most of its 15 s window left. */
    UUID enqueueDue(UUID device, int orderIndex) {
        return enqueue(device, Instant.now().minusSeconds(2).plusMillis(orderIndex * 5L));
    }

    String state(UUID command) {
        return jdbc.queryForObject("SELECT state FROM command_outbox WHERE command_id = ?", String.class, command);
    }

    String actionStatus(UUID command) {
        return jdbc.queryForObject("SELECT status FROM action_history WHERE request_id = ?", String.class, command);
    }

    String actionMessage(UUID command) {
        return jdbc.queryForObject("SELECT message FROM action_history WHERE request_id = ?", String.class, command);
    }

    String terminalReason(UUID command) {
        return jdbc.queryForObject("SELECT terminal_reason FROM command_outbox WHERE command_id = ?", String.class, command);
    }

    int attempts(UUID command) {
        return jdbc.queryForObject("SELECT attempt_count FROM command_outbox WHERE command_id = ?", Integer.class, command);
    }

    List<String> facts(UUID command) {
        return new ArrayList<>(jdbc.queryForList("SELECT fact_type FROM command_state_history WHERE command_id = ? ORDER BY sequence",
                String.class, command));
    }

    List<Integer> sequences(UUID command) {
        return new ArrayList<>(jdbc.queryForList("SELECT sequence FROM command_state_history WHERE command_id = ? ORDER BY sequence",
                Integer.class, command));
    }

    /** Forces the lease of an IN_FLIGHT row to have ended (the dispatcher decides by the database clock). */
    void expireLease(UUID command) {
        jdbc.update("UPDATE command_outbox SET lease_expires_at = now() - INTERVAL '1 second' WHERE command_id = ? AND state = 'IN_FLIGHT'", command);
    }

    @Override
    public void close() {
        dataSource.close();
    }

    PostgreSQLContainer<?> container() {
        return db;
    }
}
