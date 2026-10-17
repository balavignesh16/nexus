package com.nexus.action.outbox;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The persisted ACK timeout (contract R14, ledger P4-052, P4-053, P4-056 at the scanner level) on the real SQL: the deadline is read and
 * never moved, the database clock decides, exactly one fact per command across repeated scans and several scanners, a first ACK always
 * wins the row lock race, a restart loses nothing, and the scanner touches nothing but the history (no outbox row, no desired state, no
 * verification, no publication).
 */
@Testcontainers
class CommandAckTimeoutScannerTest {

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));

    static OutboxTestKit kit;

    @BeforeAll
    static void start() {
        OutboxTestKit.migrate(db);
        kit = new OutboxTestKit(db);
    }

    @AfterAll
    static void stop() {
        kit.close();
    }

    @BeforeEach
    void clean() {
        kit.jdbc.execute("TRUNCATE command_state_history, command_outbox, action_history CASCADE");
    }

    private static CommandAckTimeoutScanner scanner(OutboxTestKit k) {
        return new CommandAckTimeoutScanner(k.jdbc, k.transactions, k.history, new DefaultListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class), true);
    }

    /** A PUBLISHED command whose ACK deadline lies {@code deadlineOffsetMillis} from the database now (negative = already passed). */
    private UUID published(UUID device, long deadlineOffsetMillis) {
        try (Connection c = kit.dataSource.getConnection()) {
            UUID command = OutboxSql.action(c, device, "SUCCESS", Instant.now().minusSeconds(60));
            kit.jdbc.update("UPDATE action_history SET published_at = now() - INTERVAL '40 seconds', ack_deadline_at = clock_timestamp() + make_interval(secs => ?) WHERE request_id = ?",
                    deadlineOffsetMillis / 1000.0, command);
            return command;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> facts(UUID command) {
        return kit.facts(command);
    }

    @Test
    void anOverdueCommandWithoutAnAckGetsExactlyOneTimeoutFactAcrossRepeatedScans() {
        UUID command = published(kit.device(), -2000);
        var scanner = scanner(kit);
        assertEquals(1, scanner.scan(10));
        assertEquals(0, scanner.scan(10));
        assertEquals(0, scanner.scan(10));
        assertEquals(List.of("ACK_TIMED_OUT"), facts(command));
        assertEquals("ACK_DEADLINE_PASSED", kit.jdbc.queryForObject("SELECT reason_code FROM command_state_history WHERE command_id = ?", String.class, command));
        assertEquals("TIMEOUT_SCANNER", kit.jdbc.queryForObject("SELECT source FROM command_state_history WHERE command_id = ?", String.class, command));
    }

    @Test
    void aDeadlineThatHasNotPassedIsNeverATimeoutAndNeitherIsEquality() {
        UUID future = published(kit.device(), 5000);
        assertEquals(0, scanner(kit).scan(10));
        assertTrue(facts(future).isEmpty());
        // the comparison is strictly after the deadline: pin the deadline to this very statement's clock and it is not overdue
        kit.jdbc.update("UPDATE action_history SET ack_deadline_at = clock_timestamp() + INTERVAL '2 seconds' WHERE request_id = ?", future);
        assertEquals(0, scanner(kit).scan(10));
    }

    @Test
    void aCommandThatWasNeverPublishedHasNoDeadlineAndIsNeverTimedOut() {
        UUID queued = kit.enqueueDue(kit.device(), 0);                          // ACCEPTED_FOR_DISPATCH, QUEUED, no publication, no deadline
        UUID retry = kit.enqueueDue(kit.device(), 1);
        kit.ops.complete(kit.ops.claim(retry, "w", java.time.Duration.ofSeconds(6)).orElseThrow(), OutboxDeliveryOperations.AttemptResult.NOT_SENT, "X");
        assertEquals(0, scanner(kit).scan(10));
        assertTrue(facts(queued).stream().noneMatch(f -> f.equals("ACK_TIMED_OUT")));
        assertTrue(facts(retry).stream().noneMatch(f -> f.equals("ACK_TIMED_OUT")), "RETRY_WAIT is not publication: no ACK was ever due");
        assertEquals(0, kit.jdbc.queryForObject("SELECT count(*) FROM action_history WHERE ack_deadline_at IS NOT NULL", Integer.class));
    }

    @Test
    void anAcknowledgedCommandIsNeverTimedOutAndAFailedOrNonCommandRowIsIgnored() {
        UUID acked = published(kit.device(), -2000);
        kit.jdbc.update("UPDATE action_history SET ack_status = 'ACCEPTED', ack_received_at = now() WHERE request_id = ?", acked);
        UUID failed = published(kit.device(), -2000);
        kit.jdbc.update("UPDATE action_history SET status = 'FAILURE' WHERE request_id = ?", failed);
        assertEquals(0, scanner(kit).scan(10));
        assertTrue(facts(acked).isEmpty());
        assertTrue(facts(failed).isEmpty());
    }

    @Test
    void theScannerOnlyAppendsAHistoryFactAndChangesNothingElse() {
        UUID device = kit.device();
        UUID command = published(device, -2000);
        String before = kit.jdbc.queryForObject("SELECT row_to_json(a)::text FROM action_history a WHERE request_id = ?", String.class, command);
        long outboxRows = kit.jdbc.queryForObject("SELECT count(*) FROM command_outbox", Long.class);
        assertEquals(1, scanner(kit).scan(10));
        assertEquals(before, kit.jdbc.queryForObject("SELECT row_to_json(a)::text FROM action_history a WHERE request_id = ?", String.class, command),
                "ActionHistory is untouched (the deadline is read, never moved or cleared)");
        assertEquals(outboxRows, kit.jdbc.queryForObject("SELECT count(*) FROM command_outbox", Long.class));
        assertEquals(0, kit.jdbc.queryForObject("SELECT count(*) FROM current_desired_twin_state", Integer.class));
    }

    @Test
    void aSkewedApplicationClockCannotTimeACommandOutOrHideATimeout() {
        // an application clock a day ahead (or behind) is irrelevant: the scanner never reads one
        var skewed = new OutboxTestKit(db, null, java.time.Clock.offset(java.time.Clock.systemUTC(), java.time.Duration.ofDays(1)));
        try {
            UUID fresh = published(skewed.device(), 30_000);
            UUID overdue = published(skewed.device(), -1000);
            assertEquals(1, scanner(skewed).scan(10));
            assertTrue(skewed.facts(fresh).isEmpty());
            assertEquals(List.of("ACK_TIMED_OUT"), skewed.facts(overdue));
        } finally {
            skewed.close();
        }
    }

    @Test
    void theAckDeadlineIsSetFromTheDatabaseClockSoAnApplicationClockInEitherDirectionCannotShiftIt() {
        for (long offsetHours : new long[]{24, -24}) {
            var skewed = new OutboxTestKit(db, null, java.time.Clock.offset(java.time.Clock.systemUTC(), java.time.Duration.ofHours(offsetHours)));
            try {
                UUID command = skewed.enqueueDue(skewed.device(), 0);
                var claimed = skewed.ops.claim(command, "w", java.time.Duration.ofSeconds(6)).orElseThrow();
                assertTrue(skewed.ops.complete(claimed, OutboxDeliveryOperations.AttemptResult.CONFIRMED, null).published());
                Long secondsAhead = skewed.jdbc.queryForObject("SELECT (EXTRACT(EPOCH FROM (ack_deadline_at - clock_timestamp())))::bigint FROM action_history WHERE request_id = ?",
                        Long.class, command);
                assertTrue(secondsAhead >= 28 && secondsAhead <= 30, "with the application clock " + offsetHours + " h off the deadline is still about 30 s from the database now: " + secondsAhead);
                Long fromPublication = skewed.jdbc.queryForObject("SELECT (EXTRACT(EPOCH FROM (ack_deadline_at - published_at)))::bigint FROM action_history WHERE request_id = ?", Long.class, command);
                assertEquals(30L, fromPublication, "the deadline is the publication instant plus the 30 s ACK timeout");
                Long publishedAgo = skewed.jdbc.queryForObject("SELECT abs(EXTRACT(EPOCH FROM (published_at - clock_timestamp())))::bigint FROM action_history WHERE request_id = ?", Long.class, command);
                assertTrue(publishedAgo <= 2, "and the publication instant itself is the database clock: " + publishedAgo);
            } finally {
                skewed.close();
            }
        }
    }

    @Test
    void aBatchIsBoundedAndTheRestIsPickedUpByTheNextPass() {
        UUID device = kit.device();
        List<UUID> commands = new ArrayList<>();
        for (int i = 0; i < 5; i++) commands.add(published(device, -3000 + i));
        var scanner = scanner(kit);
        assertEquals(2, scanner.scan(2));
        assertEquals(2, scanner.scan(2));
        assertEquals(1, scanner.scan(2));
        assertEquals(0, scanner.scan(2));
        for (UUID c : commands) assertEquals(List.of("ACK_TIMED_OUT"), facts(c));
    }

    @Test
    void aDeadlinePassedDuringDowntimeIsPersistedByAFreshProcess() {
        UUID command = published(kit.device(), -120_000);                        // the deadline passed two minutes ago, nobody was running
        try (OutboxTestKit restarted = new OutboxTestKit(db)) {
            assertEquals(1, scanner(restarted).scan(10));
        }
        assertEquals(List.of("ACK_TIMED_OUT"), facts(command));
    }

    @Test
    @Timeout(60)
    void severalScannersRacingForTheSameCommandsRecordEachTimeoutExactlyOnce() throws Exception {
        UUID device = kit.device();
        List<UUID> commands = new ArrayList<>();
        for (int i = 0; i < 12; i++) commands.add(published(device, -2000));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<OutboxTestKit> kits = new ArrayList<>();
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                OutboxTestKit k = new OutboxTestKit(db);
                kits.add(k);
                results.add(pool.submit(() -> { go.await(); return scanner(k).scan(100); }));
            }
            go.countDown();
            int total = 0;
            for (var f : results) total += f.get();
            assertEquals(12, total, "every command was timed out by exactly one scanner");
        } finally {
            pool.shutdownNow();
            kits.forEach(OutboxTestKit::close);
        }
        for (UUID c : commands) assertEquals(List.of("ACK_TIMED_OUT"), facts(c), "no duplicate timeout fact");
        assertEquals(List.of(1), kit.sequences(commands.get(0)));
    }

    @Test
    @Timeout(60)
    void aFirstAckHoldingTheRowWinsAndTheScannerWaitsThenWritesNothing() throws Exception {
        UUID command = published(kit.device(), -2000);
        // an ACK transaction that has locked the action_history row and not yet committed
        try (Connection ackTx = kit.dataSource.getConnection()) {
            ackTx.setAutoCommit(false);
            try (var st = ackTx.createStatement()) {
                st.execute("SELECT 1 FROM action_history WHERE request_id = '" + command + "' FOR UPDATE");
                ExecutorService pool = Executors.newSingleThreadExecutor();
                try {
                    Future<Integer> scan = pool.submit(() -> scanner(kit).scan(10));
                    Thread.sleep(700);
                    assertFalse(scan.isDone(), "the scanner is blocked on the row lock the ACK holds");
                    st.execute("UPDATE action_history SET ack_status = 'ACCEPTED', ack_received_at = now() WHERE request_id = '" + command + "'");
                    ackTx.commit();
                    assertEquals(0, scan.get(), "the ACK committed first: nothing to time out");
                } finally {
                    pool.shutdownNow();
                }
            }
        }
        assertTrue(facts(command).isEmpty());
    }
}
