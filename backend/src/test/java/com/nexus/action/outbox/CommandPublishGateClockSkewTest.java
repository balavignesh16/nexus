package com.nexus.action.outbox;

import com.nexus.action.outbox.OutboxDeliveryOperations.AttemptResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The publish gate when the APPLICATION clock and the DATABASE clock disagree (P4.6 safety repair, found by the targeted audit: the JVM and the
 * database container differ by more than a second on the development machine). An attempt that may have reached the broker (UNCERTAIN, completed
 * after the delivery window, or lease-recovered) is counted by the gate for one second of DATABASE time from its outcome, whatever the application
 * clock says; confirmed publications that were acknowledged at once stay counted; an uncertain command is never retried; and instances with
 * different clocks share one limit. Windows and boundaries are checked with database time, never with this JVM's clock.
 */
@Testcontainers
@Timeout(180)
class CommandPublishGateClockSkewTest {

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));

    static OutboxTestKit kit;
    static final Duration LEASE = Duration.ofSeconds(6);

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
        kit.jdbc.execute("TRUNCATE command_state_history, command_outbox");
    }

    /** An operations object whose application clock is offset from real time, sharing the one database and the rate-1 gate. */
    private OutboxTestKit skewed(Duration offset, int rate) {
        OutboxTestKit k = new OutboxTestKit(db, null, Clock.offset(Clock.systemUTC(), offset));
        k.ops.setPublishGate(new CommandPublishGate(k.jdbc, new CommandFlowSettings(100_000, 1_000, rate)));
        return k;
    }

    private UUID due(OutboxTestKit k) {
        return k.enqueueDue(k.device(), 0);
    }

    /** Seconds (database time) between the recorded terminal instant of a command and the database clock now. */
    private double ageSeconds(UUID command) {
        return kit.jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM (clock_timestamp() - terminal_at)) FROM command_outbox WHERE command_id = ?", Double.class, command);
    }

    private void assertUncertainIsCountedAgainstDatabaseTime(Duration offset) throws Exception {
        try (OutboxTestKit k = skewed(offset, 1)) {
            UUID a = due(k), b = due(k);
            var claimed = k.ops.tryClaim(a, "w1", LEASE).claimed().orElseThrow();
            k.ops.complete(claimed, AttemptResult.UNCERTAIN, "PUBLISH_TIMEOUT");
            assertEquals("UNCERTAIN_EXPIRED", k.terminalReason(a));
            assertTrue(Math.abs(ageSeconds(a)) < 5, "the uncertain terminal instant is database time (" + ageSeconds(a) + " s old), not the skewed application clock (" + offset + ")");
            double elapsedAtCheck = ageSeconds(a);
            var immediately = k.ops.tryClaim(b, "w2", LEASE);
            if (elapsedAtCheck < 0.9)
                assertTrue(immediately.rateLimited() && immediately.claimed().isEmpty(), "capacity one: the possibly-published attempt A blocks claim B inside the same database second");
            Thread.sleep(Math.max(1, immediately.retryAfterMillis()) + 200);
            assertTrue(ageSeconds(a) >= 1.0, "a full database second has passed");
            assertTrue(k.ops.tryClaim(b, "w2", LEASE).claimed().isPresent(), "B becomes eligible once A has left the rolling window, judged on the database clock");
        }
    }

    @Test
    void anUncertainAttemptStillCountsWhenTheApplicationClockIsAnHourBehindTheDatabase() throws Exception {
        assertUncertainIsCountedAgainstDatabaseTime(Duration.ofHours(-1));
    }

    @Test
    void anUncertainAttemptStillCountsWhenTheApplicationClockIsAboutOnePointTwoSecondsBehind() throws Exception {
        assertUncertainIsCountedAgainstDatabaseTime(Duration.ofMillis(-1200));
    }

    @Test
    void anUncertainAttemptStillCountsWhenTheApplicationClockIsAheadOfTheDatabase() throws Exception {
        assertUncertainIsCountedAgainstDatabaseTime(Duration.ofHours(1));
    }

    @Test
    void aSkewedClockCannotCountAnUncertainAttemptForLongerThanOneDatabaseSecond() throws Exception {
        try (OutboxTestKit k = skewed(Duration.ofHours(1), 1)) {     // an application clock far AHEAD used to keep the attempt counted for an hour
            UUID a = due(k), b = due(k);
            k.ops.complete(k.ops.tryClaim(a, "w1", LEASE).claimed().orElseThrow(), AttemptResult.UNCERTAIN, "PUBLISH_TIMEOUT");
            Thread.sleep(1400);
            assertTrue(k.ops.tryClaim(b, "w2", LEASE).claimed().isPresent(), "the limit is a rolling second of database time, not an hour");
        }
    }

    @Test
    void anAttemptCompletedAfterTheWindowAndALeaseRecoveredAttemptAreAlsoCountedOnDatabaseTime() throws Exception {
        try (OutboxTestKit k = skewed(Duration.ofHours(-1), 1)) {
            // completed after expiry (recorded as late and uncertain)
            UUID late = k.enqueueDue(k.device(), 0, Map.of("expires_at", k.jdbc.queryForObject("SELECT clock_timestamp() + interval '1 second'", Timestamp.class)));
            var claimed = k.ops.tryClaim(late, "w1", LEASE).claimed().orElseThrow();
            Thread.sleep(1500);
            k.ops.complete(claimed, AttemptResult.CONFIRMED, null);
            assertEquals("UNCERTAIN_EXPIRED", k.terminalReason(late));
            assertTrue(Math.abs(ageSeconds(late)) < 5, "database time");
            assertTrue(k.ops.tryClaim(due(k), "w2", LEASE).rateLimited());
            Thread.sleep(1200);

            // lease recovered
            UUID lost = due(k);
            k.ops.tryClaim(lost, "w3", LEASE).claimed().orElseThrow();
            k.expireLease(lost);
            assertEquals(1, k.ops.recoverExpiredLeases(10).size());
            assertEquals("UNCERTAIN_EXPIRED", k.terminalReason(lost));
            assertTrue(Math.abs(ageSeconds(lost)) < 5, "a lease-recovered attempt records database time too");
            assertTrue(k.ops.tryClaim(due(k), "w4", LEASE).rateLimited(), "and is counted");
        }
    }

    @Test
    void aConfirmedPublicationThatIsAcknowledgedAtOnceStaysCountedForItsFullSecond() throws Exception {
        try (OutboxTestKit k = skewed(Duration.ofHours(-1), 1)) {
            UUID a = due(k), b = due(k);
            k.ops.complete(k.ops.tryClaim(a, "w1", LEASE).claimed().orElseThrow(), AttemptResult.CONFIRMED, null);
            k.jdbc.update("UPDATE action_history SET ack_status = 'ACCEPTED', ack_received_at = now() WHERE request_id = ?", a);   // the ACK arrives immediately
            assertEquals("PUBLISHED", k.state(a), "the ACK changes nothing in the delivery record");
            var immediately = k.ops.tryClaim(b, "w2", LEASE);
            if (ageSeconds(a) < 0.9) assertTrue(immediately.rateLimited(), "acknowledged or not, the publication is counted for its whole rolling second");
            Thread.sleep(Math.max(1, immediately.retryAfterMillis()) + 200);
            assertTrue(k.ops.tryClaim(b, "w2", LEASE).claimed().isPresent());
        }
    }

    @Test
    void anUncertainOutcomeIsNeverRetriedAndNeverACandidateAgain() throws Exception {
        try (OutboxTestKit k = skewed(Duration.ofMillis(-1200), 5)) {
            UUID a = due(k);
            k.ops.complete(k.ops.tryClaim(a, "w1", LEASE).claimed().orElseThrow(), AttemptResult.UNCERTAIN, "PUBLISH_TIMEOUT");
            assertEquals("DEAD_LETTER", k.state(a));
            assertEquals(1, k.attempts(a));
            assertFalse(k.ops.candidates(50).contains(a), "an uncertain command is never a claim candidate again");
            assertTrue(k.ops.tryClaim(a, "w2", LEASE).claimed().isEmpty(), "and can never be claimed: no automatic resend");
            assertEquals(1, k.attempts(a));
        }
    }

    @Test
    void instancesWithDifferentClocksShareOneLimit() throws Exception {
        try (OutboxTestKit behind = skewed(Duration.ofHours(-1), 3); OutboxTestKit ahead = skewed(Duration.ofHours(1), 3); OutboxTestKit exact = skewed(Duration.ZERO, 3)) {
            List<OutboxTestKit> instances = List.of(behind, ahead, exact);
            List<UUID> commands = new ArrayList<>();
            for (int i = 0; i < 30; i++) commands.add(due(kit));
            ExecutorService pool = Executors.newFixedThreadPool(12);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int t = 0; t < 12; t++) {
                OutboxTestKit mine = instances.get(t % 3);
                int base = t * 2;
                results.add(pool.submit((Callable<Integer>) () -> {
                    go.await();
                    int claimed = 0;
                    for (int i = base; i < base + 2; i++) {
                        var r = mine.ops.tryClaim(commands.get(i), "w-" + i, LEASE);
                        if (r.claimed().isPresent()) {
                            claimed++;
                            mine.ops.complete(r.claimed().get(), AttemptResult.UNCERTAIN, "PUBLISH_TIMEOUT");
                        }
                    }
                    return claimed;
                }));
            }
            go.countDown();
            long burstStarted = System.nanoTime();
            int total = 0;
            for (Future<Integer> f : results) total += f.get(60, TimeUnit.SECONDS);
            pool.shutdownNow();
            if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - burstStarted) < 800)
                assertEquals(3, total, "inside one database second the three instances together claimed exactly the rate, not one limit each");
            int maxWindow = kit.jdbc.queryForObject("SELECT COALESCE(max(n),0) FROM (SELECT count(*) OVER (ORDER BY terminal_at RANGE BETWEEN INTERVAL '0.999 seconds' PRECEDING AND CURRENT ROW) n"
                    + " FROM command_outbox WHERE terminal_reason = 'UNCERTAIN_EXPIRED') w", Integer.class);
            assertTrue(total >= 3, "the limit was used: " + total);
            assertTrue(maxWindow <= 3, "possibly-published attempts inside any one database-time window: " + maxWindow);
        }
    }

    @Test
    void whenTheGateCannotDecideAnUncertainBurstStillFailsClosed() {
        try (OutboxTestKit k = skewed(Duration.ofHours(-1), 1)) {
            k.ops.setPublishGate(new CommandPublishGate(k.jdbc, new CommandFlowSettings(100, 10, 1)) {
                @Override public Decision permit() { throw new org.springframework.dao.DataAccessResourceFailureException("coordination unavailable"); }
            });
            UUID a = due(k);
            org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, () -> k.ops.tryClaim(a, "w", LEASE));
            assertEquals("QUEUED", k.state(a));
            assertEquals(0, k.attempts(a));
        }
    }
}
