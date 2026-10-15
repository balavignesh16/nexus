package com.nexus.action.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Safety corrections after the P4.1 forensic review (V38), proven on a disposable TimescaleDB/PostgreSQL: device deletion
 * fails closed for every outbox state and every deletion path, deletion races are serialised by the database, a concurrent
 * claim of two commands of one device cannot both succeed, attempts cannot be exhausted into a parked row, an expired row
 * cannot be claimed and non-ASCII payloads hash identically in Java and PostgreSQL.
 *
 * Nothing here is a dispatcher: the dispatcher of P4.2 must still select only unexpired rows and expire the rest.
 */
@Testcontainers
class CommandOutboxSafetySchemaTest {

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));

    private static final String CLAIM = "state = 'IN_FLIGHT', attempt_count = attempt_count + 1, next_attempt_at = NULL, lease_owner = 'w', lease_expires_at = NOW() + INTERVAL '6 seconds'";
    private static final String PUBLISH = "state = 'PUBLISHED', lease_owner = NULL, lease_expires_at = NULL, terminal_at = NOW(), first_publish_confirmed_at = NOW()";
    private static final String DEAD = "state = 'DEAD_LETTER', next_attempt_at = NULL, lease_owner = NULL, lease_expires_at = NULL, terminal_at = NOW(), terminal_reason = 'EXPIRED'";

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()).load().migrate();
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }

    private static int deleteDevice(Connection c, UUID device) throws SQLException {
        try (var s = c.createStatement()) {
            return s.executeUpdate("DELETE FROM devices WHERE id = '" + device + "'");
        }
    }

    private static UUID queuedCommand(Connection c, UUID device) throws SQLException {
        UUID command = OutboxSql.action(c, device, "ACCEPTED_FOR_DISPATCH", Instant.now());
        OutboxSql.insert(c, OutboxSql.queued(command, device, Instant.now()));
        return command;
    }

    // ------------------------------------------------------------------------------------ F1: device deletion

    @Test
    void aDeviceWithAnUnresolvedObligationCannotBeDeletedInAnyUnresolvedState() throws Exception {
        for (String toState : new String[]{"QUEUED", "IN_FLIGHT", "RETRY_WAIT"}) {
            try (Connection c = connect()) {
                UUID device = OutboxSql.device(c);
                UUID command = queuedCommand(c, device);
                if (toState.equals("IN_FLIGHT")) OutboxSql.update(c, command, CLAIM);
                if (toState.equals("RETRY_WAIT")) {
                    OutboxSql.update(c, command, CLAIM);
                    OutboxSql.update(c, command, "state = 'RETRY_WAIT', lease_owner = NULL, lease_expires_at = NULL, next_attempt_at = NOW()");
                }
                SQLException e = assertThrows(SQLException.class, () -> deleteDevice(c, device), toState);
                assertTrue(e.getMessage().contains("DEVICE_HAS_UNRESOLVED_COMMANDS"), toState + ": " + e.getMessage());
                assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM devices WHERE id = '" + device + "'"), toState);
                assertEquals(toState, stateOf(c, command), "the obligation is untouched");
            }
        }
    }

    @Test
    void resolvedDeliveryRecordsAlsoBlockDeletionUntilRetentionPurgesThem() throws Exception {
        try (Connection c = connect()) {
            UUID device = OutboxSql.device(c);
            UUID published = queuedCommand(c, device);
            OutboxSql.update(c, published, CLAIM);
            OutboxSql.update(c, published, PUBLISH);
            UUID dead = queuedCommand(c, device);
            OutboxSql.update(c, dead, DEAD);
            for (int i = 0; i < 2; i++) {
                SQLException e = assertThrows(SQLException.class, () -> deleteDevice(c, device));
                assertTrue(e.getMessage().contains("DEVICE_HAS_COMMAND_DELIVERY_EVIDENCE"), e.getMessage());
            }
            // Retention (RetentionManager) removes resolved rows; only then may the device go.
            try (var s = c.createStatement()) {
                assertEquals(2, s.executeUpdate("DELETE FROM command_outbox WHERE device_id = '" + device + "' AND state IN ('PUBLISHED','DEAD_LETTER')"));
            }
            assertEquals(1, deleteDevice(c, device));
        }
    }

    @Test
    void aDeviceWithoutDeliveryRecordsIsStillDeletable() throws Exception {
        try (Connection c = connect()) {
            UUID device = OutboxSql.device(c);
            OutboxSql.action(c, device, "SUCCESS", Instant.now());   // a legacy command, no outbox row
            assertEquals(1, deleteDevice(c, device));
        }
    }

    @Test
    void aRefusedDeletionPreservesActionHistoryDesiredStateAndVerificationEvidence() throws Exception {
        try (Connection c = connect()) {
            UUID device = OutboxSql.device(c);
            UUID command = queuedCommand(c, device);
            try (var s = c.createStatement()) {
                s.executeUpdate("INSERT INTO current_desired_twin_state(device_id,target_key,legacy_capability_type,subtarget_key,capability_key,value_type,boolean_value,source,command_id,requested_at,accepted_for_dispatch_at) "
                        + "VALUES ('" + device + "','legacy:LED','LED','','led','BOOLEAN',true,'MANUAL_COMMAND','" + command + "',NOW(),NOW())");
                s.executeUpdate("INSERT INTO current_twin_verification_state(device_id,target_key,subtarget_key,desired_command_id,verification_status,verification_updated_at) "
                        + "VALUES ('" + device + "','legacy:LED','','" + command + "','PENDING',NOW())");
            }
            assertThrows(SQLException.class, () -> deleteDevice(c, device));
            assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM action_history WHERE request_id = '" + command + "' AND device_id = '" + device + "' AND status = 'ACCEPTED_FOR_DISPATCH'"), "ActionHistory untouched, device link intact");
            assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM current_desired_twin_state WHERE device_id = '" + device + "' AND command_id = '" + command + "'"));
            assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM current_twin_verification_state WHERE device_id = '" + device + "' AND desired_command_id = '" + command + "'"));
            assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM command_outbox WHERE command_id = '" + command + "'"));
        }
    }

    @Test
    void aRefusedDeletionInsideATransactionRollsBackTheWholeTransaction() throws Exception {
        try (Connection c = connect()) {
            UUID device = OutboxSql.device(c);
            queuedCommand(c, device);
            c.setAutoCommit(false);
            try (var s = c.createStatement()) {
                s.executeUpdate("UPDATE devices SET name = 'renamed-in-failed-tx' WHERE id = '" + device + "'");
                SQLException e = assertThrows(SQLException.class, () -> s.executeUpdate("DELETE FROM devices WHERE id = '" + device + "'"));
                assertTrue(e.getMessage().contains("DEVICE_HAS_UNRESOLVED_COMMANDS"));
            }
            c.rollback();
            c.setAutoCommit(true);
            assertEquals(0, OutboxSql.count(c, "SELECT COUNT(*) FROM devices WHERE id = '" + device + "' AND name = 'renamed-in-failed-tx'"));
            assertEquals(1, OutboxSql.count(c, "SELECT COUNT(*) FROM devices WHERE id = '" + device + "'"));
        }
    }

    @Test
    void aCascadeFromOtherPathsCannotBypassTheGuardEither() throws Exception {
        // The foreign key itself is RESTRICT, so even if the trigger were dropped the delete would still fail.
        try (Connection c = connect()) {
            UUID device = OutboxSql.device(c);
            queuedCommand(c, device);
            assertEquals("RESTRICT", fkDeleteRule(c));
        }
    }

    private static String fkDeleteRule(Connection c) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery(
                "SELECT confdeltype FROM pg_constraint WHERE conname = 'fk_command_outbox_device'")) {
            r.next();
            return switch (r.getString(1)) { case "r" -> "RESTRICT"; case "c" -> "CASCADE"; case "a" -> "NO ACTION"; default -> r.getString(1); };
        }
    }

    // ------------------------------------------------------------------------------------ F1: races

    private static <T> T within(ExecutorService pool, java.util.concurrent.Callable<T> task) {
        return waitFor(pool.submit(task));
    }

    private static <T> T waitFor(Future<T> f) {
        try {
            return f.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void assertStillBlocked(Future<?> f) {
        assertThrows(TimeoutException.class, () -> f.get(800, TimeUnit.MILLISECONDS), "the statement must be waiting on the other transaction");
    }

    @Test
    void anAcceptanceThatCommitsBeforeADeviceDeletionWinsAndTheDeletionIsRefused() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection a = connect(); Connection b = connect()) {
            UUID device = OutboxSql.device(a);
            UUID command = OutboxSql.action(a, device, "ACCEPTED_FOR_DISPATCH", Instant.now());
            a.setAutoCommit(false);
            OutboxSql.insert(a, OutboxSql.queued(command, device, Instant.now()));     // uncommitted obligation
            Future<Integer> delete = pool.submit(() -> deleteDevice(b, device));
            assertStillBlocked(delete);                                                // waits for the key-share lock of the insert
            a.commit();
            ExecutionException e = assertThrows(ExecutionException.class, () -> delete.get(10, TimeUnit.SECONDS));
            assertTrue(e.getCause().getMessage().contains("DEVICE_HAS_UNRESOLVED_COMMANDS"), e.getCause().getMessage());
            a.setAutoCommit(true);
            assertEquals(1, OutboxSql.count(a, "SELECT COUNT(*) FROM devices WHERE id = '" + device + "'"));
            assertEquals(1, OutboxSql.count(a, "SELECT COUNT(*) FROM command_outbox WHERE command_id = '" + command + "'"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aDeviceDeletionThatCommitsFirstMakesALaterAcceptanceFailInsteadOfLeavingAnOrphan() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection a = connect(); Connection b = connect()) {
            UUID device = OutboxSql.device(a);
            UUID command = OutboxSql.action(a, device, "ACCEPTED_FOR_DISPATCH", Instant.now());
            b.setAutoCommit(false);
            assertEquals(1, deleteDevice(b, device));                                   // uncommitted deletion, no obligations yet
            Future<Integer> insert = pool.submit(() -> OutboxSql.insert(a, OutboxSql.queued(command, device, Instant.now())));
            assertStillBlocked(insert);
            b.commit();
            ExecutionException e = assertThrows(ExecutionException.class, () -> insert.get(10, TimeUnit.SECONDS));
            assertEquals("23503", ((SQLException) e.getCause()).getSQLState(), "foreign key to the deleted device");
            assertEquals(0, OutboxSql.count(a, "SELECT COUNT(*) FROM command_outbox WHERE command_id = '" + command + "'"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void deletingWhileAnotherTransactionClaimsOrResolvesTheRowIsNeverAllowed() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try (Connection a = connect(); Connection b = connect()) {
            UUID device = OutboxSql.device(a);
            UUID command = queuedCommand(a, device);
            a.setAutoCommit(false);
            OutboxSql.update(a, command, CLAIM);                                       // claim, uncommitted
            SQLException claimRace = assertThrows(SQLException.class, () -> deleteDevice(b, device));
            assertTrue(claimRace.getMessage().contains("DEVICE_HAS_UNRESOLVED_COMMANDS"),
                    "the claim is not visible yet, the committed row is still unresolved: refuse, never guess. " + claimRace.getMessage());
            // A second update of the same row inside A takes a key-share lock on the device row (PostgreSQL re-checks the key
            // of a row version created by the same transaction), so a concurrent deletion now has to WAIT for A to finish.
            OutboxSql.update(a, command, PUBLISH);                                     // resolution, still uncommitted
            Future<Integer> delete = pool.submit(() -> deleteDevice(b, device));
            assertStillBlocked(delete);
            a.commit();
            ExecutionException afterCommit = assertThrows(ExecutionException.class, () -> delete.get(10, TimeUnit.SECONDS));
            assertTrue(afterCommit.getCause().getMessage().contains("DEVICE_HAS_COMMAND_DELIVERY_EVIDENCE"),
                    "after A commits the row is PUBLISHED, which is delivery evidence retention has not purged: " + afterCommit.getCause().getMessage());
            a.setAutoCommit(true);
            assertEquals(1, OutboxSql.count(a, "SELECT COUNT(*) FROM devices WHERE id = '" + device + "'"));
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------------------------ F7: concurrent claims

    private record TwoQueued(UUID device, UUID first, UUID second) {}

    private static TwoQueued twoQueued(Connection c) throws SQLException {
        UUID device = OutboxSql.device(c);
        return new TwoQueued(device, queuedCommand(c, device), queuedCommand(c, device));
    }

    @Test
    void twoConcurrentClaimsForOneDeviceSerialiseAndOnlyTheFirstSucceeds() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection a = connect(); Connection b = connect()) {
            TwoQueued t = twoQueued(a);
            a.setAutoCommit(false);
            b.setAutoCommit(false);
            assertEquals(1, OutboxSql.update(a, t.first(), CLAIM));
            Future<Integer> second = pool.submit(() -> OutboxSql.update(b, t.second(), CLAIM));
            assertStillBlocked(second);                                                // waits on the unique in-flight index
            a.commit();
            ExecutionException e = assertThrows(ExecutionException.class, () -> second.get(10, TimeUnit.SECONDS));
            assertEquals("23505", ((SQLException) e.getCause()).getSQLState());
            b.rollback();
            a.setAutoCommit(true);
            assertEquals("IN_FLIGHT", stateOf(a, t.first()));
            assertEquals("QUEUED", stateOf(a, t.second()));
            assertEquals(1, OutboxSql.count(a, "SELECT COUNT(*) FROM command_outbox WHERE device_id = '" + t.device() + "' AND state = 'IN_FLIGHT'"));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void whenTheFirstClaimRollsBackTheWaitingClaimProceeds() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection a = connect(); Connection b = connect()) {
            TwoQueued t = twoQueued(a);
            a.setAutoCommit(false);
            b.setAutoCommit(false);
            OutboxSql.update(a, t.first(), CLAIM);
            Future<Integer> second = pool.submit(() -> OutboxSql.update(b, t.second(), CLAIM));
            assertStillBlocked(second);
            a.rollback();
            assertEquals(1, waitFor(second));
            b.commit();
            a.setAutoCommit(true);
            assertEquals("QUEUED", stateOf(a, t.first()));
            assertEquals("IN_FLIGHT", stateOf(a, t.second()));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void manyConcurrentClaimersOfOneDeviceNeverProduceMoreThanOneInFlightRow() throws Exception {
        int rows = 6;
        ExecutorService pool = Executors.newFixedThreadPool(rows);
        try (Connection setup = connect()) {
            UUID device = OutboxSql.device(setup);
            UUID[] commands = new UUID[rows];
            for (int i = 0; i < rows; i++) commands[i] = queuedCommand(setup, device);
            java.util.List<Future<Boolean>> results = new java.util.ArrayList<>();
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            for (UUID command : commands) results.add(pool.submit(() -> {
                try (Connection c = connect()) {
                    go.await();
                    return OutboxSql.update(c, command, CLAIM) == 1;
                } catch (SQLException e) {
                    assertEquals("23505", e.getSQLState());
                    return false;
                }
            }));
            go.countDown();
            long winners = results.stream().filter(f -> waitFor(f)).count();
            assertEquals(1, winners, "exactly one claim may win");
            assertEquals(1, OutboxSql.count(setup, "SELECT COUNT(*) FROM command_outbox WHERE device_id = '" + device + "' AND state = 'IN_FLIGHT'"));
            assertEquals(rows - 1, OutboxSql.count(setup, "SELECT COUNT(*) FROM command_outbox WHERE device_id = '" + device + "' AND state = 'QUEUED'"));
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------------------------ F7: attempts and expiry

    @Test
    void attemptExhaustionCannotBeParkedOrClaimedAgainAndMustBecomeADeadLetter() throws Exception {
        try (Connection c = connect()) {
            UUID device = OutboxSql.device(c);
            UUID command = OutboxSql.action(c, device, "ACCEPTED_FOR_DISPATCH", Instant.now());
            Map<String, Object> row = OutboxSql.queued(command, device, Instant.now());
            row.put("max_attempts", 2);
            OutboxSql.insert(c, row);
            String retry = "state = 'RETRY_WAIT', lease_owner = NULL, lease_expires_at = NULL, next_attempt_at = NOW()";
            assertEquals(1, OutboxSql.update(c, command, CLAIM));          // attempt 1 of 2
            assertEquals(1, OutboxSql.update(c, command, retry));          // one attempt left: may wait
            assertEquals(1, OutboxSql.update(c, command, CLAIM));          // attempt 2 of 2
            SQLException parked = assertThrows(SQLException.class, () -> OutboxSql.update(c, command, retry));
            assertTrue(parked.getMessage().contains("ck_outbox_retry_has_attempts_left"), "an exhausted row cannot wait for another attempt: " + parked.getMessage());
            assertEquals("IN_FLIGHT", stateOf(c, command));
            assertEquals(1, OutboxSql.update(c, command, "state = 'DEAD_LETTER', next_attempt_at = NULL, lease_owner = NULL, lease_expires_at = NULL, terminal_at = NOW(), terminal_reason = 'RETRY_EXHAUSTED'"));
            assertThrows(SQLException.class, () -> OutboxSql.update(c, command, CLAIM), "a dead letter is never claimed again");
        }
    }

    @Test
    void anExpiredRowCannotBeClaimedButCanStillBeDeadLettered() throws Exception {
        try (Connection c = connect()) {
            UUID device = OutboxSql.device(c);
            UUID command = OutboxSql.action(c, device, "ACCEPTED_FOR_DISPATCH", Instant.now().minusSeconds(100));
            // payload timestamp 100 s ago: expires_at (timestamp + 15 s) is 85 s in the past
            OutboxSql.insert(c, OutboxSql.queued(command, device, Instant.now().minusSeconds(100)));
            SQLException e = assertThrows(SQLException.class, () -> OutboxSql.update(c, command, CLAIM));
            assertTrue(e.getMessage().contains("has expired and cannot be claimed"), e.getMessage());
            assertEquals("QUEUED", stateOf(c, command));
            assertEquals(1, OutboxSql.update(c, command, DEAD), "the expiry sweep of a later slice can still dead-letter it");
        }
    }

    // ------------------------------------------------------------------------------------ F7: payload hashing

    @Test
    void aNonAsciiPayloadHashesIdenticallyInJavaAndPostgreSql() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String value = "ü€😀 café";                          // u-umlaut, euro, emoji, e-acute
        try (Connection c = connect()) {
            UUID device = OutboxSql.device(c);
            UUID command = OutboxSql.action(c, device, "ACCEPTED_FOR_DISPATCH", Instant.now());
            OutboxPayload payload = OutboxPayload.create(command, "LED", "SET_STATE", value, Instant.now().getEpochSecond(), mapper);
            Map<String, Object> row = OutboxSql.queued(command, device, Instant.now());
            row.put("payload_text", payload.text());
            row.put("payload_sha256", payload.sha256());
            row.put("payload_epoch_seconds", payload.epochSeconds());
            row.put("expires_at", Timestamp.from(Instant.ofEpochSecond(payload.epochSeconds() + 15)));
            assertEquals(1, OutboxSql.insert(c, row), "the database recomputed the hash from the stored text and it equals the Java hash");
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT payload_text, payload_sha256, encode(sha256(convert_to(payload_text,'UTF8')),'hex') FROM command_outbox WHERE command_id = '" + command + "'")) {
                r.next();
                assertEquals(payload.text(), r.getString(1), "the text round-trips byte for byte");
                assertEquals(payload.sha256(), r.getString(2));
                assertEquals(payload.sha256(), r.getString(3));
                assertTrue(r.getString(1).contains(value.substring(0, 3)));
            }
            assertFalse(payload.text().isEmpty());
        }
    }

    private static String stateOf(Connection c, UUID command) throws SQLException {
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT state FROM command_outbox WHERE command_id = '" + command + "'")) {
            return r.next() ? r.getString(1) : null;
        }
    }
}
