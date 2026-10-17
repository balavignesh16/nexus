package com.nexus.action.outbox;

import com.nexus.action.outbox.OutboxDeliveryOperations.AttemptResult;
import com.nexus.action.outbox.OutboxDeliveryOperations.ClaimResult;
import com.nexus.action.outbox.OutboxDeliveryOperations.Claimed;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared publish gate on the real SQL with real connections (contract R18; ledger P4-090). It is consulted inside every claim, so a command
 * that may not be published yet is simply not claimed: it keeps its state, attempt count, payload, timestamp and deadline, and nothing is
 * resent, refreshed or re-identified. The limiter state is derived from durable database facts, so a restart or another instance sees the
 * same limit. Every refusal is compared byte for byte with the row before it.
 */
@Testcontainers
@Timeout(180)
class CommandPublishGateTest {

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

    /** A due command on its own device (so the per-device guards never interfere with the rate being tested). */
    private UUID due() {
        return kit.enqueueDue(kit.device(), 0);
    }

    private String snapshot(UUID command) {
        return kit.jdbc.queryForObject("SELECT t::text FROM command_outbox t WHERE command_id = ?", String.class, command)
                + "|" + kit.facts(command);
    }

    private Claimed claim(OutboxDeliveryOperations ops, UUID command) {
        ClaimResult r = ops.tryClaim(command, "w-" + command.toString().substring(0, 4), LEASE);
        assertTrue(r.claimed().isPresent(), "expected a claim, rateLimited=" + r.rateLimited());
        return r.claimed().get();
    }

    // ---------------------------------------------------------------------------------------- the rate and its boundary

    @Test
    void exactlyTheRateOfClaimsIsAllowedAndTheNextOneIsRefusedWithoutTouchingTheRow() {
        OutboxDeliveryOperations ops = kit.opsWithRate(3);
        List<UUID> commands = new ArrayList<>();
        for (int i = 0; i < 5; i++) commands.add(due());
        for (int i = 0; i < 3; i++) claim(ops, commands.get(i));                 // 0, 1 and 2 of 3: one below and exactly at the rate

        UUID waiting = commands.get(3);
        String before = snapshot(waiting);
        ClaimResult refused = ops.tryClaim(waiting, "late", LEASE);               // one above
        assertTrue(refused.rateLimited());
        assertTrue(refused.claimed().isEmpty());
        assertTrue(refused.retryAfterMillis() >= 1);
        assertEquals(before, snapshot(waiting), "a rate-limited command is byte for byte what it was: QUEUED, attempt 0, same payload, timestamp, deadline, no history fact");
        assertEquals("QUEUED", kit.state(waiting));
        assertEquals(0, kit.attempts(waiting));
        assertEquals(3, kit.jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE state = 'IN_FLIGHT'", Integer.class));
    }

    @Test
    void aClaimedButUnfinishedPublicationCountsUntilItsOutcomeIsRecorded() {
        OutboxDeliveryOperations ops = kit.opsWithRate(1);
        UUID first = due(), second = due();
        Claimed inFlight = claim(ops, first);
        assertTrue(ops.tryClaim(second, "w2", LEASE).rateLimited(), "a publication that is in flight holds the permit");
        ops.complete(inFlight, AttemptResult.CONFIRMED, null);
        assertTrue(ops.tryClaim(second, "w2", LEASE).rateLimited(), "and for one more second after its confirmation");
    }

    @Test
    void thePermitReturnsWhenTheOldestConfirmationLeavesTheWindowAndTheRefusalSaysWhen() throws Exception {
        OutboxDeliveryOperations ops = kit.opsWithRate(2);
        UUID a = due(), b = due(), c = due();
        ops.complete(claim(ops, a), AttemptResult.CONFIRMED, null);
        ops.complete(claim(ops, b), AttemptResult.CONFIRMED, null);
        ClaimResult refused = ops.tryClaim(c, "w3", LEASE);
        assertTrue(refused.rateLimited());
        assertTrue(refused.retryAfterMillis() > 0 && refused.retryAfterMillis() <= 1000, "wait hint: " + refused.retryAfterMillis());
        Thread.sleep(refused.retryAfterMillis() + 150);
        assertTrue(ops.tryClaim(c, "w3", LEASE).claimed().isPresent(), "allowed again once the oldest publication left the one-second window");
    }

    @Test
    void aNotSentAttemptConsumesNothingBecauseNothingReachedTheBroker() {
        OutboxDeliveryOperations ops = kit.opsWithRate(1);
        UUID a = due(), b = due();
        ops.complete(claim(ops, a), AttemptResult.NOT_SENT, "CLIENT_NOT_CONNECTED");
        assertEquals("RETRY_WAIT", kit.state(a));
        assertTrue(ops.tryClaim(b, "w2", LEASE).claimed().isPresent(), "the permit was released: the first attempt published nothing");
    }

    @Test
    void anUncertainAttemptCountsLikeAPublicationBecauseTheBrokerMayHaveReceivedIt() {
        OutboxDeliveryOperations ops = kit.opsWithRate(1);
        UUID a = due(), b = due();
        ops.complete(claim(ops, a), AttemptResult.UNCERTAIN, "PUBLISH_TIMEOUT");
        assertEquals("UNCERTAIN_EXPIRED", kit.terminalReason(a));
        assertTrue(ops.tryClaim(b, "w2", LEASE).rateLimited());
    }

    // ------------------------------------------------------------------------------ interaction with retries, expiry, ACKs, ordering

    @Test
    void aRetryThatBecomesDueWhileThePermitsAreExhaustedWaitsWithItsAttemptCounterAndDeadlineIntact() throws Exception {
        OutboxDeliveryOperations ops = kit.opsWithRate(1);
        UUID retrying = due();
        ops.complete(claim(ops, retrying), AttemptResult.NOT_SENT, "CLIENT_NOT_CONNECTED");
        kit.jdbc.update("UPDATE command_outbox SET next_attempt_at = now() - INTERVAL '1 second' WHERE command_id = ?", retrying);   // the backoff ended
        UUID other = due();
        ops.complete(claim(ops, other), AttemptResult.CONFIRMED, null);                                                          // the permit is used up

        String before = snapshot(retrying);
        long payloadEpoch = kit.jdbc.queryForObject("SELECT payload_epoch_seconds FROM command_outbox WHERE command_id = ?", Long.class, retrying);
        ClaimResult refused = ops.tryClaim(retrying, "w-retry", LEASE);
        assertTrue(refused.rateLimited());
        assertEquals(before, snapshot(retrying), "the retry is still RETRY_WAIT, attempt 1, same payload, same deadline: delay did not consume or reset anything");
        assertEquals("RETRY_WAIT", kit.state(retrying));
        assertEquals(1, kit.attempts(retrying));

        Thread.sleep(refused.retryAfterMillis() + 150);
        Claimed second = claim(ops, retrying);
        assertEquals(2, second.attempt(), "the second attempt, not the third: waiting for permission is not an attempt");
        assertEquals(payloadEpoch, kit.jdbc.queryForObject("SELECT payload_epoch_seconds FROM command_outbox WHERE command_id = ?", Long.class, retrying), "the wire timestamp is never refreshed");
    }

    @Test
    void aCommandWhoseWindowEndsWhileItWaitsForPermissionIsExpiredNeverSentAndNeverRefreshed() throws Exception {
        OutboxDeliveryOperations ops = kit.opsWithRate(1);
        UUID blocker = due();
        claim(ops, blocker);                                                  // in flight for as long as the test needs: the single permit is held
        UUID waiting = kit.enqueueDue(kit.device(), 0, Map.of("expires_at", kit.jdbc.queryForObject("SELECT clock_timestamp() + interval '1 second'", Timestamp.class)));
        String payloadBefore = kit.jdbc.queryForObject("SELECT payload_text || payload_sha256 || payload_epoch_seconds FROM command_outbox WHERE command_id = ?", String.class, waiting);
        assertTrue(ops.tryClaim(waiting, "w", LEASE).rateLimited());
        Thread.sleep(1500);
        assertTrue(ops.tryClaim(waiting, "w", LEASE).claimed().isEmpty(), "an expired command is never claimed, whatever the permits");
        List<OutboxDeliveryOperations.Resolution> expired = ops.expireDue(10);
        assertEquals(1, expired.size());
        assertEquals("DEAD_LETTER", kit.state(waiting));
        assertEquals("EXPIRED", kit.terminalReason(waiting));
        assertEquals(0, kit.attempts(waiting), "it was never sent");
        assertEquals("FAILURE", kit.actionStatus(waiting));
        assertTrue(kit.actionMessage(waiting).contains("never sent"), kit.actionMessage(waiting));
        assertEquals(payloadBefore, kit.jdbc.queryForObject("SELECT payload_text || payload_sha256 || payload_epoch_seconds FROM command_outbox WHERE command_id = ?", String.class, waiting));
    }

    @Test
    void anExpiredCommandIsNeverClaimedEvenWhenAPermitIsFree() throws Exception {
        OutboxDeliveryOperations ops = kit.opsWithRate(5);
        UUID expired = kit.enqueueDue(kit.device(), 0, Map.of("expires_at", kit.jdbc.queryForObject("SELECT clock_timestamp() + interval '1 second'", Timestamp.class)));
        Thread.sleep(1500);
        String before = snapshot(expired);
        ClaimResult r = ops.tryClaim(expired, "w", LEASE);
        assertFalse(r.rateLimited(), "a permit is free");
        assertTrue(r.claimed().isEmpty(), "but the original delivery window is over: the command is never claimed and never published");
        assertEquals(before, snapshot(expired));
    }

    @Test
    void aRetryStillInBackoffIsNotClaimedEvenWhenAPermitIsFree() {
        OutboxDeliveryOperations ops = kit.opsWithRate(5);
        UUID retrying = due();
        ops.complete(claim(ops, retrying), AttemptResult.NOT_SENT, "CLIENT_NOT_CONNECTED");
        kit.jdbc.update("UPDATE command_outbox SET next_attempt_at = now() + INTERVAL '5 seconds' WHERE command_id = ?", retrying);
        String before = snapshot(retrying);
        ClaimResult r = ops.tryClaim(retrying, "w", LEASE);
        assertFalse(r.rateLimited());
        assertTrue(r.claimed().isEmpty(), "the backoff has not ended: the retry is not eligible, whatever the permits");
        assertEquals(before, snapshot(retrying));
        assertEquals(1, kit.attempts(retrying));
    }

    @Test
    void anAcknowledgedCommandIsNeverClaimedAndTakesNoPermit() {
        OutboxDeliveryOperations ops = kit.opsWithRate(1);
        UUID acknowledged = due();
        kit.jdbc.update("UPDATE action_history SET ack_status = 'ACCEPTED', ack_received_at = now() WHERE request_id = ?", acknowledged);
        String before = snapshot(acknowledged);
        ClaimResult r = ops.tryClaim(acknowledged, "w", LEASE);
        assertFalse(r.rateLimited());
        assertTrue(r.claimed().isEmpty(), "the device already holds this command: it is never published (again)");
        assertEquals(before, snapshot(acknowledged));
        UUID next = due();
        assertTrue(ops.tryClaim(next, "w2", LEASE).claimed().isPresent(), "no permit was consumed by the acknowledged command");
    }

    @Test
    void aLaterCommandNeverOvertakesAnEarlierOneThatIsWaitingForPermission() throws Exception {
        OutboxDeliveryOperations ops = kit.opsWithRate(2);
        UUID device = kit.device();
        UUID earlier = kit.enqueueDue(device, 0);
        UUID later = kit.enqueueDue(device, 1);
        UUID p1 = due(), p2 = due();
        ops.complete(claim(ops, p1), AttemptResult.CONFIRMED, null);
        ops.complete(claim(ops, p2), AttemptResult.CONFIRMED, null);             // both permits used by other devices
        assertTrue(ops.tryClaim(earlier, "w", LEASE).rateLimited());
        assertTrue(ops.tryClaim(later, "w", LEASE).rateLimited());

        Thread.sleep(1200);                                                        // permits are free again
        assertEquals(List.of(earlier), kit.ops.candidates(10).stream().filter(c -> c.equals(earlier) || c.equals(later)).toList(),
                "only the older command of the device is a candidate");
        ClaimResult overtaking = ops.tryClaim(later, "w", LEASE);
        assertFalse(overtaking.rateLimited(), "a free permit exists");
        assertTrue(overtaking.claimed().isEmpty(), "but the later command stays blocked behind the earlier unresolved one");
        assertEquals("QUEUED", kit.state(later));
        assertTrue(ops.tryClaim(earlier, "w", LEASE).claimed().isPresent());
        assertTrue(ops.tryClaim(later, "w", LEASE).claimed().isEmpty(), "and behind it while it is in flight");
    }

    @Test
    void aClaimThatLosesItsLeaseIsFencedOutAndNothingIsPublishedOrCountedTwice() {
        OutboxDeliveryOperations ops = kit.opsWithRate(5);
        UUID command = due();
        Claimed stale = claim(ops, command);
        kit.expireLease(command);
        List<OutboxDeliveryOperations.Resolution> recovered = ops.recoverExpiredLeases(10);
        assertEquals(1, recovered.size());
        assertEquals("UNCERTAIN_EXPIRED", kit.terminalReason(command));
        OutboxDeliveryOperations.Resolution late = ops.complete(stale, AttemptResult.CONFIRMED, null);
        assertEquals(OutboxDeliveryOperations.Disposition.FENCED, late.disposition(), "the stale worker writes nothing");
        assertEquals("DEAD_LETTER", kit.state(command));
    }

    // ----------------------------------------------------------------------------------------- shared by every instance

    @Test
    void manyInstancesAndThreadsRacingForTheLimitedPermitsClaimExactlyTheRate() throws Exception {
        int rate = 5, instances = 3, threadsPerInstance = 8;
        List<OutboxDeliveryOperations> ops = new ArrayList<>();
        for (int i = 0; i < instances; i++) ops.add(kit.opsWithRate(rate));
        List<UUID> commands = new ArrayList<>();
        for (int i = 0; i < 48; i++) commands.add(due());
        ExecutorService pool = Executors.newFixedThreadPool(instances * threadsPerInstance);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger next = new AtomicInteger();
        List<Future<int[]>> results = new ArrayList<>();
        for (int t = 0; t < instances * threadsPerInstance; t++) {
            OutboxDeliveryOperations mine = ops.get(t % instances);
            results.add(pool.submit((Callable<int[]>) () -> {
                go.await();
                int claimed = 0, limited = 0;
                for (int k = 0; k < 2; k++) {
                    int i = next.getAndIncrement();
                    if (i >= commands.size()) break;
                    ClaimResult r = mine.tryClaim(commands.get(i), "w-" + i, LEASE);
                    if (r.claimed().isPresent()) claimed++;
                    if (r.rateLimited()) limited++;
                }
                return new int[]{claimed, limited};
            }));
        }
        go.countDown();
        int claimed = 0, limited = 0;
        for (Future<int[]> f : results) {
            int[] r = f.get(90, TimeUnit.SECONDS);
            claimed += r[0];
            limited += r[1];
        }
        pool.shutdownNow();
        assertEquals(rate, claimed, "the aggregate over all instances is exactly the rate, never more");
        assertEquals(rate, kit.jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE state = 'IN_FLIGHT'", Integer.class));
        assertTrue(limited >= 1, "the rest were refused, not claimed");
    }

    /** The mechanism, shown deterministically: while another transaction holds the gate lock a claim cannot even decide, so two claimers cannot both see a free permit. */
    @Test
    void aClaimWaitsWhileAnotherTransactionHoldsTheGateLockInsteadOfDecidingOnAStaleCount() throws Exception {
        OutboxDeliveryOperations ops = kit.opsWithRate(1);
        UUID command = due();
        CountDownLatch holding = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> holder = pool.submit(() -> new TransactionTemplate(kit.transactions).executeWithoutResult(status -> {
            kit.jdbc.query("SELECT pg_advisory_xact_lock(?, ?)", rs -> { }, CommandPublishGate.LOCK_CLASS, CommandPublishGate.GATE_LOCK);
            holding.countDown();
            try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }));
        assertTrue(holding.await(30, TimeUnit.SECONDS));
        Future<ClaimResult> claimer = pool.submit(() -> ops.tryClaim(command, "w", LEASE));
        Thread.sleep(700);
        assertFalse(claimer.isDone(), "the claim must be waiting for the gate lock");
        release.countDown();
        holder.get(30, TimeUnit.SECONDS);
        assertTrue(claimer.get(30, TimeUnit.SECONDS).claimed().isPresent());
        pool.shutdownNow();
    }

    @Test
    void aRestartedInstanceSeesTheSameLimiterStateBecauseItIsDerivedFromDurableRows() {
        UUID a = due(), b = due();
        OutboxDeliveryOperations before = kit.opsWithRate(1);
        before.complete(claim(before, a), AttemptResult.CONFIRMED, null);
        OutboxDeliveryOperations afterRestart = kit.opsWithRate(1);              // a new object, nothing remembered in memory
        assertTrue(afterRestart.tryClaim(b, "w", LEASE).rateLimited(), "the confirmation of the previous run still counts");
    }

    @Test
    void whenTheGateCannotDecideTheClaimFailsClosedAndNothingIsPublishable() {
        OutboxDeliveryOperations ops = new OutboxDeliveryOperations(kit.jdbc, kit.transactions, kit.history, java.time.Clock.systemUTC(), Duration.ofSeconds(30));
        ops.setPublishGate(new CommandPublishGate(kit.jdbc, new CommandFlowSettings(100, 10, 20)) {
            @Override public Decision permit() { throw new DataAccessResourceFailureException("the rate-limiter state is unavailable"); }
        });
        UUID command = due();
        String before = snapshot(command);
        assertThrows(DataAccessResourceFailureException.class, () -> ops.tryClaim(command, "w", LEASE), "no permit, no claim: the failure propagates");
        assertEquals(before, snapshot(command));
        assertEquals("QUEUED", kit.state(command));
        assertEquals(0, kit.jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE state = 'IN_FLIGHT'", Integer.class));
    }

    @Test
    void aSingleClaimTakesExactlyOnePermitSoTheGateAndTheClaimCannotDisagree() {
        OutboxDeliveryOperations ops = kit.opsWithRate(2);
        UUID a = due(), b = due(), c = due();
        claim(ops, a);
        claim(ops, b);
        assertEquals(2, kit.jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE state = 'IN_FLIGHT'", Integer.class));
        assertTrue(ops.tryClaim(c, "w", LEASE).rateLimited());
        assertEquals(0, kit.attempts(c));
    }
}
