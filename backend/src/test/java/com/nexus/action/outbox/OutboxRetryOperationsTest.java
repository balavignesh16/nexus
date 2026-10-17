package com.nexus.action.outbox;

import com.nexus.action.outbox.OutboxDeliveryOperations.AttemptResult;
import com.nexus.action.outbox.OutboxDeliveryOperations.Claimed;
import com.nexus.action.outbox.OutboxDeliveryOperations.Disposition;
import com.nexus.action.outbox.OutboxDeliveryOperations.Resolution;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Durable RETRY_WAIT on the real SQL (disposable TimescaleDB, V1 to V39; no migration is added by P4.3): eligibility, persisted
 * backoff, attempt budget, the database-clock window, byte-identical payload identity, lease fencing, per-device blocking, restart
 * survival and crash-safe failure signals. The jitter source is injected so every delay is exact; time-sensitive decisions are still
 * made by the database clock. Ledger: P4-026, 030, 032, 035, 041 (state layer), 043, 044 (state layer), 045.
 */
@Testcontainers
class OutboxRetryOperationsTest {

    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(
            DockerImageName.parse("timescale/timescaledb:2.16.1-pg16").asCompatibleSubstituteFor("postgres"));

    static final Duration LEASE = Duration.ofSeconds(6);
    /** jitter 0.5 = no jitter at all: the delays are exactly 500 ms, then 1000 ms. */
    static OutboxRetryPolicy exact;
    static OutboxTestKit kit;

    @BeforeAll
    static void start() {
        OutboxTestKit.migrate(db);
        exact = new OutboxRetryPolicy(FakeBroker.settings(2, Duration.ofSeconds(3), LEASE), () -> 0.5);
        kit = new OutboxTestKit(db, exact);
    }

    @AfterAll
    static void stop() {
        kit.close();
    }

    @BeforeEach
    void clean() {
        kit.jdbc.execute("TRUNCATE command_state_history, command_outbox");
    }

    private Claimed claim(UUID command) {
        Optional<Claimed> c = kit.ops.claim(command, "owner-" + UUID.randomUUID(), LEASE);
        assertTrue(c.isPresent(), "claim should succeed");
        return c.get();
    }

    /** Makes the backoff of a RETRY_WAIT row end now (the dispatcher compares next_attempt_at with the database clock). */
    private void backoffElapsed(UUID command) {
        kit.jdbc.update("UPDATE command_outbox SET next_attempt_at = now() - INTERVAL '1 millisecond' WHERE command_id = ? AND state = 'RETRY_WAIT'", command);
    }

    /** A point in time measured on the DATABASE clock (the container clock may differ from this JVM's), for delivery bounds that are immutable once created. */
    private java.sql.Timestamp dbPlus(long millis) {
        return kit.jdbc.queryForObject("SELECT clock_timestamp() + make_interval(secs => ?)", java.sql.Timestamp.class, millis / 1000.0);
    }

    private Map<String, Object> identity(UUID command) {
        return kit.jdbc.queryForMap("SELECT command_id, topic, payload_text, payload_sha256, payload_epoch_seconds, created_at, expires_at, max_attempts"
                + " FROM command_outbox WHERE command_id = ?", command);
    }

    // ------------------------------------------------------------------------ RETRY_WAIT is durable, scheduled and gated

    @Test
    void aProvenNonPublicationIsScheduledOnTheDatabaseClockAndIsNotClaimableBeforeItsBackoffEnds() {
        UUID command = kit.enqueueDue(kit.device(), 0);
        Resolution r = kit.ops.complete(claim(command), AttemptResult.NOT_SENT, "CLIENT_NOT_CONNECTED");
        assertEquals(Disposition.RETRY_SCHEDULED, r.disposition());
        assertEquals("RETRY_WAIT", kit.state(command));
        Long waitMillis = kit.jdbc.queryForObject("SELECT (EXTRACT(EPOCH FROM (next_attempt_at - clock_timestamp())) * 1000)::bigint FROM command_outbox WHERE command_id = ?",
                Long.class, command);
        assertTrue(waitMillis > 0 && waitMillis <= 500, "exactly the 500 ms backoff counted from the database clock: " + waitMillis);
        assertEquals("NOT_SENT_CLIENT_NOT_CONNECTED", kit.jdbc.queryForObject("SELECT last_error_code FROM command_outbox WHERE command_id = ?", String.class, command));
        assertFalse(kit.ops.candidates(10).contains(command), "not due yet");
        assertTrue(kit.ops.claim(command, "w", LEASE).isEmpty(), "a retry cannot be claimed before it is due");
        assertEquals(1, kit.attempts(command));
        assertEquals("ACCEPTED_FOR_DISPATCH", kit.actionStatus(command));
    }

    @Test
    void aDueRetryIsClaimedThroughTheSameFencedClaimAndRecordedAsARetryClaim() {
        UUID command = kit.enqueueDue(kit.device(), 0);
        kit.ops.complete(claim(command), AttemptResult.NOT_SENT, "CLIENT_NOT_CONNECTED");
        backoffElapsed(command);
        assertEquals(List.of(command), kit.ops.candidates(10));
        Claimed second = claim(command);
        assertEquals(2, second.attempt());
        assertEquals("IN_FLIGHT", kit.state(command));
        assertEquals(List.of("ATTEMPT_STARTED", "ATTEMPT_FAILED_TRANSIENT", "ATTEMPT_STARTED"), kit.facts(command));
        assertEquals("RETRY_CLAIMED", kit.jdbc.queryForObject("SELECT reason_code FROM command_state_history WHERE command_id = ? AND sequence = 3", String.class, command));
        assertEquals(List.of(1, 2, 3), kit.sequences(command), "the history stays gapless and ordered");
    }

    @Test
    void everyRetryUsesTheIdenticalStoredPayloadCommandIdHashAndWireTimestamp() {
        UUID command = kit.enqueueDue(kit.device(), 0);
        Map<String, Object> before = identity(command);
        Claimed first = claim(command);
        kit.ops.complete(first, AttemptResult.NOT_SENT, "CLIENT_NOT_CONNECTED");
        backoffElapsed(command);
        Claimed second = claim(command);
        kit.ops.complete(second, AttemptResult.NOT_SENT, "CLIENT_NOT_CONNECTED");
        backoffElapsed(command);
        Claimed third = claim(command);
        assertEquals(3, third.attempt());
        for (Claimed c : List.of(first, second, third)) {
            assertEquals(first.payload().text(), c.payload().text(), "byte-identical payload text on every attempt");
            assertEquals(first.payload().sha256(), c.payload().sha256());
            assertEquals(first.payload().epochSeconds(), c.payload().epochSeconds(), "the wire timestamp is never refreshed");
            assertTrue(c.payload().verify());
        }
        assertEquals(before, identity(command), "the stored identity of the command (payload, hash, timestamp, expiry, budget) never changed");
        assertEquals(1, kit.jdbc.queryForObject("SELECT count(*) FROM action_history WHERE request_id = ?", Integer.class, command),
                "a retry creates no second command or action: still exactly one ActionHistory row");
    }

    // -------------------------------------------------------------------------------------- attempt budget (P4-035)

    @Test
    void theThirdNonPublicationExhaustsTheBudgetAndFailsTruthfullyWithNoFurtherClaim() {
        UUID command = kit.enqueueDue(kit.device(), 0);
        Resolution last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            Claimed c = claim(command);
            assertEquals(attempt, c.attempt());
            last = kit.ops.complete(c, AttemptResult.NOT_SENT, "CLIENT_NOT_CONNECTED");
            if (attempt < 3) { assertTrue(last.retryScheduled()); backoffElapsed(command); }
        }
        assertTrue(last.deadLettered());
        assertEquals(OutboxTerminalReason.RETRY_EXHAUSTED, last.reason());
        assertEquals("DEAD_LETTER", kit.state(command));
        assertEquals("RETRY_EXHAUSTED", kit.terminalReason(command));
        assertEquals("FAILURE", kit.actionStatus(command));
        assertTrue(kit.actionMessage(command).contains("not sent"));
        assertEquals(3, kit.attempts(command));
        assertTrue(kit.ops.claim(command, "w", LEASE).isEmpty(), "a dead letter is never claimed again");
        assertTrue(kit.ops.candidates(10).isEmpty());
        assertEquals(List.of("ATTEMPT_STARTED", "ATTEMPT_FAILED_TRANSIENT", "ATTEMPT_STARTED", "ATTEMPT_FAILED_TRANSIENT", "ATTEMPT_STARTED", "DEAD_LETTERED"),
                kit.facts(command));
    }

    @Test
    void aBudgetOfOneAttemptNeverRetries() {
        UUID device = kit.device();
        UUID command = kit.enqueueDue(device, 0, Map.of("max_attempts", 1));
        Resolution r = kit.ops.complete(claim(command), AttemptResult.NOT_SENT, "CLIENT_NOT_CONNECTED");
        assertEquals(OutboxTerminalReason.RETRY_EXHAUSTED, r.reason());
        assertEquals(1, kit.attempts(command));
    }

    // ---------------------------------------------------------------------------- the delivery window (database clock)

    @Test
    void aRetryThatCannotStartBeforeExpiryIsDeadLetteredAsExpiredAndNeverSent() {
        UUID device = kit.device();
        // created 14.6 s ago: the stored window ends 0.4 s after the claim (payload timestamps have second precision, so allow some slack)
        // the window ends about 400 ms after creation (immutable bounds are set at creation): under the 500 ms backoff, so no retry can start before expiry
        UUID command = kit.enqueueDue(device, 0, Map.of("expires_at", dbPlus(400)));
        Claimed c = claim(command);
        Resolution r = kit.ops.complete(c, AttemptResult.NOT_SENT, "CLIENT_NOT_CONNECTED");
        assertTrue(r.deadLettered());
        assertEquals(OutboxTerminalReason.EXPIRED, r.reason());
        assertEquals("EXPIRED", kit.terminalReason(command));
        assertEquals("FAILURE", kit.actionStatus(command));
        assertTrue(kit.actionMessage(command).contains("never sent"), kit.actionMessage(command));
        assertEquals(1, kit.attempts(command));
        assertNull(kit.jdbc.queryForObject("SELECT first_publish_confirmed_at FROM command_outbox WHERE command_id = ?", java.sql.Timestamp.class, command));
    }

    @Test
    void aRetryWaitRowWhoseWindowEndedDuringTheBackoffExpiresWithoutAnyFurtherAttempt() throws Exception {
        UUID device = kit.device();
        // a 1.3 s window: the first attempt fails, the 500 ms retry would still fit, but the window then really ends on the database clock
        UUID command = kit.enqueueDue(device, 0, Map.of("expires_at", dbPlus(1300)));
        kit.ops.complete(claim(command), AttemptResult.NOT_SENT, "CLIENT_NOT_CONNECTED");
        assertEquals("RETRY_WAIT", kit.state(command), "a retry that still fits the window is scheduled");
        Thread.sleep(1500);
        assertFalse(kit.ops.candidates(10).contains(command), "an expired retry is never a candidate");
        assertTrue(kit.ops.claim(command, "w", LEASE).isEmpty(), "and can never be claimed: the database clock decides");
        List<Resolution> expired = kit.ops.expireDue(10);
        assertEquals(1, expired.size());
        assertEquals(OutboxTerminalReason.EXPIRED, expired.get(0).reason());
        assertEquals("DEAD_LETTER", kit.state(command));
        assertEquals("FAILURE", kit.actionStatus(command));
        assertEquals(1, kit.attempts(command), "the retry never ran");
    }

    @Test
    void aSkewedApplicationClockCannotExtendTheWindowOrMakeARetryDueEarly() {
        // the application clock is a day AHEAD: nothing about eligibility or the schedule may follow it
        var skewed = new OutboxTestKit(db, null, java.time.Clock.offset(java.time.Clock.systemUTC(), Duration.ofDays(1)));
        try {
            UUID command = skewed.enqueueDue(skewed.device(), 0);
            Claimed c = skewed.ops.claim(command, "w", LEASE).orElseThrow();
            skewed.ops.complete(c, AttemptResult.NOT_SENT, "X");
            Long wait = skewed.jdbc.queryForObject("SELECT (EXTRACT(EPOCH FROM (next_attempt_at - clock_timestamp())) * 1000)::bigint FROM command_outbox WHERE command_id = ?", Long.class, command);
            assertTrue(wait > 0 && wait < 5000, "the retry is scheduled by the database clock, not the skewed application clock: " + wait);
            assertTrue(skewed.ops.candidates(10).isEmpty());
        } finally {
            skewed.close();
        }
    }

    // ---------------------------------------------------------------- uncertain outcomes and lost leases are never retried

    @Test
    void anUncertainOutcomeIsNeverRetriedEvenOnTheSecondAttemptAndKeepsItsHold() {
        UUID device = kit.device();
        UUID command = kit.enqueueDue(device, 0);
        kit.ops.complete(claim(command), AttemptResult.NOT_SENT, "CLIENT_NOT_CONNECTED");
        backoffElapsed(command);
        Resolution r = kit.ops.complete(claim(command), AttemptResult.UNCERTAIN, "PUBLISH_TIMEOUT");
        assertTrue(r.uncertain());
        assertEquals("DEAD_LETTER", kit.state(command));
        assertEquals("UNCERTAIN_EXPIRED", kit.terminalReason(command));
        assertEquals(2, kit.attempts(command));
        assertTrue(kit.actionMessage(command).startsWith("DELIVERY_UNCONFIRMED"));
        assertTrue(kit.ops.claim(command, "w", LEASE).isEmpty());
        UUID next = kit.enqueueDue(device, 0);
        assertFalse(kit.ops.candidates(10).contains(next), "the P4.2 uncertainty hold still protects the device");
    }

    @Test
    void anExpiredLeaseOnARetryAttemptIsRecoveredAsUncertainNotRetriedAgain() {
        UUID command = kit.enqueueDue(kit.device(), 0);
        kit.ops.complete(claim(command), AttemptResult.NOT_SENT, "CLIENT_NOT_CONNECTED");
        backoffElapsed(command);
        claim(command);
        kit.expireLease(command);
        List<Resolution> recovered = kit.ops.recoverExpiredLeases(10);
        assertEquals(1, recovered.size());
        assertTrue(recovered.get(0).uncertain());
        assertEquals("UNCERTAIN_EXPIRED", kit.terminalReason(command));
        assertEquals("LEASE_EXPIRED_UNCERTAIN", kit.jdbc.queryForObject("SELECT last_error_code FROM command_outbox WHERE command_id = ?", String.class, command));
        assertTrue(kit.ops.claim(command, "w", LEASE).isEmpty(), "attempts remained, but an uncertain attempt is never retried");
        assertEquals(2, kit.attempts(command));
    }

    @Test
    void aPayloadThatFailsItsIntegrityCheckIsPermanentAndNeverRetried() {
        UUID command = kit.enqueueDue(kit.device(), 0);
        Resolution r = kit.ops.complete(claim(command), AttemptResult.PAYLOAD_INTEGRITY, null);
        assertEquals(OutboxTerminalReason.PAYLOAD_INTEGRITY, r.reason());
        assertEquals(1, kit.attempts(command));
        assertTrue(kit.ops.claim(command, "w", LEASE).isEmpty());
    }

    // ------------------------------------------------------------------------------ fencing and late completion (P4-032)

    @Test
    void aStaleWorkerCannotCompleteAnAttemptAfterANewerClaimOrAfterItsOwnOutcome() {
        UUID command = kit.enqueueDue(kit.device(), 0);
        Claimed first = claim(command);
        kit.ops.complete(first, AttemptResult.NOT_SENT, "CLIENT_NOT_CONNECTED");
        backoffElapsed(command);
        Claimed second = claim(command);
        // the first worker reports again (it never learned its result was recorded): fenced, nothing written
        Resolution late = kit.ops.complete(first, AttemptResult.CONFIRMED, null);
        assertEquals(Disposition.FENCED, late.disposition());
        assertEquals("IN_FLIGHT", kit.state(command), "the newer claim is untouched");
        assertEquals("ACCEPTED_FOR_DISPATCH", kit.actionStatus(command));
        // the second worker completes normally, then the first reports a success AFTER the terminal outcome: still fenced
        assertTrue(kit.ops.complete(second, AttemptResult.CONFIRMED, null).published());
        assertEquals(Disposition.FENCED, kit.ops.complete(second, AttemptResult.NOT_SENT, "X").disposition(), "a terminal row cannot be resurrected");
        assertEquals(Disposition.FENCED, kit.ops.complete(first, AttemptResult.NOT_SENT, "X").disposition());
        assertEquals("PUBLISHED", kit.state(command));
        assertEquals("SUCCESS", kit.actionStatus(command));
    }

    @Test
    @Timeout(60)
    void twoDispatchersRacingForTheSameDueRetryClaimItExactlyOnce() throws Exception {
        UUID command = kit.enqueueDue(kit.device(), 0);
        kit.ops.complete(claim(command), AttemptResult.NOT_SENT, "CLIENT_NOT_CONNECTED");
        backoffElapsed(command);
        try (OutboxTestKit other = new OutboxTestKit(db, exact)) {
            for (int round = 0; round < 1; round++) {
                ExecutorService pool = Executors.newFixedThreadPool(2);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Optional<Claimed>>> results = new ArrayList<>();
                for (OutboxTestKit k : List.of(kit, other))
                    results.add(pool.submit(() -> { go.await(); return k.ops.claim(command, "owner-" + UUID.randomUUID(), LEASE); }));
                go.countDown();
                long winners = 0;
                for (var f : results) if (f.get().isPresent()) winners++;
                pool.shutdownNow();
                assertEquals(1, winners, "exactly one instance wins the retry");
            }
        }
        assertEquals(2, kit.attempts(command), "the attempt counter advanced exactly once");
        assertEquals(List.of("ATTEMPT_STARTED", "ATTEMPT_FAILED_TRANSIENT", "ATTEMPT_STARTED"), kit.facts(command));
    }

    // --------------------------------------------------------------------------------- per-device ordering (P4-032 / S5)

    @Test
    void aCommandWaitingForItsRetryBlocksEveryLaterCommandOfThatDeviceButNotOtherDevices() {
        UUID deviceX = kit.device();
        UUID deviceY = kit.device();
        UUID a = kit.enqueueDue(deviceX, 0);
        kit.ops.complete(claim(a), AttemptResult.NOT_SENT, "CLIENT_NOT_CONNECTED");
        UUID b = kit.enqueueDue(deviceX, 5);
        UUID c = kit.enqueueDue(deviceY, 6);
        List<UUID> candidates = kit.ops.candidates(10);
        assertFalse(candidates.contains(b), "B must not overtake A while A waits for its retry");
        assertTrue(candidates.contains(c), "a blocked device X must not block device Y");
        assertTrue(kit.ops.claim(b, "w", LEASE).isEmpty(), "and the atomic claim refuses it too");
        backoffElapsed(a);
        List<UUID> afterBackoff = kit.ops.candidates(10);
        assertTrue(afterBackoff.contains(a), "A is next for its device once its backoff ended");
        assertTrue(afterBackoff.contains(c));
        assertFalse(afterBackoff.contains(b), "B is still behind A until A is resolved");
    }

    @Test
    void whenTheRetrySucceedsTheNextCommandIsReleasedInOrder() {
        UUID device = kit.device();
        UUID a = kit.enqueueDue(device, 0);
        kit.ops.complete(claim(a), AttemptResult.NOT_SENT, "CLIENT_NOT_CONNECTED");
        UUID b = kit.enqueueDue(device, 5);
        backoffElapsed(a);
        assertTrue(kit.ops.complete(claim(a), AttemptResult.CONFIRMED, null).published());
        assertTrue(kit.ops.candidates(10).contains(b), "A is published, so B may now go");
        assertEquals(2, kit.attempts(a));
        assertEquals(0, kit.attempts(b));
    }

    @Test
    void whenTheRetryWindowEndsTheNextCommandIsReleasedOnlyAfterTheTerminalOutcome() throws Exception {
        UUID device = kit.device();
        UUID a = kit.enqueueDue(device, 0, Map.of("expires_at", dbPlus(1300)));
        kit.ops.complete(claim(a), AttemptResult.NOT_SENT, "CLIENT_NOT_CONNECTED");
        UUID b = kit.enqueueDue(device, 5);
        Thread.sleep(1500);
        assertFalse(kit.ops.candidates(10).contains(b), "A is still unresolved (RETRY_WAIT) until the sweep records its terminal outcome");
        assertEquals(1, kit.ops.expireDue(10).size());
        assertTrue(kit.ops.candidates(10).contains(b), "released only after A reached DEAD_LETTER EXPIRED (never sent)");
    }

    @Test
    void aRetryWaitStateAndItsScheduleSurviveARestart() {
        UUID device = kit.device();
        UUID a = kit.enqueueDue(device, 0);
        kit.ops.complete(claim(a), AttemptResult.NOT_SENT, "CLIENT_NOT_CONNECTED");
        UUID b = kit.enqueueDue(device, 5);
        var scheduled = kit.jdbc.queryForObject("SELECT next_attempt_at FROM command_outbox WHERE command_id = ?", java.sql.Timestamp.class, a);
        try (OutboxTestKit restarted = new OutboxTestKit(db, exact)) {      // a fresh process: no in-memory state at all
            assertEquals("RETRY_WAIT", restarted.state(a));
            assertEquals(1, restarted.attempts(a), "the attempt count survived");
            assertEquals(scheduled, restarted.jdbc.queryForObject("SELECT next_attempt_at FROM command_outbox WHERE command_id = ?", java.sql.Timestamp.class, a));
            assertFalse(restarted.ops.candidates(10).contains(b), "B still cannot overtake A after the restart");
            assertTrue(restarted.ops.hasWork());
            backoffElapsed(a);
            assertEquals(2, restarted.ops.claim(a, "restarted", LEASE).orElseThrow().attempt());
        }
    }

    // ------------------------------------------------------------------------------- crash-safe failure signals (P4-041/042)

    private UUID deadLetter(String reason, boolean acked, int terminalSecondsAgo) {
        UUID device = kit.device();
        UUID command = kit.enqueueDue(device, 0);
        Claimed c = claim(command);
        kit.ops.complete(c, AttemptResult.NOT_SENT, "X");
        kit.jdbc.update("UPDATE command_outbox SET state = 'DEAD_LETTER', next_attempt_at = NULL, terminal_at = now() - make_interval(secs => ?),"
                + " terminal_reason = ?, last_error_code = 'NOT_SENT_X' WHERE command_id = ?", terminalSecondsAgo, reason, command);
        kit.jdbc.update("UPDATE action_history SET status = 'FAILURE', message = 'failed'" + (acked ? ", ack_status = 'ACCEPTED', ack_received_at = now()" : "")
                + " WHERE request_id = ?", command);
        return command;
    }

    @Test
    void aProvenNotSentDeadLetterWhoseRuleFactIsMissingIsReportedForRecoveryUntilTheFactExists() {
        kit.jdbc.execute("DELETE FROM rule_trigger_facts WHERE trigger_type = 'COMMAND_PUBLICATION_FAILED'");
        UUID command = deadLetter("RETRY_EXHAUSTED", false, 30);
        assertEquals(List.of(command), kit.ops.unreactedFailures(10).stream().map(u -> u.resolution().commandId()).toList());
        assertFalse(kit.ops.unreactedFailures(10).get(0).twinPending(), "no PENDING verification: only the rule fact is missing, so the Twin (and its device lock) is not touched");
        // once the fact exists the same dead letter is no longer reported (the signal was applied, possibly by the recovery itself)
        kit.jdbc.update("INSERT INTO rule_trigger_facts(fact_id, trigger_type, source_key, device_id, occurred_at, recorded_at)"
                + " VALUES (gen_random_uuid(), 'COMMAND_PUBLICATION_FAILED', ?, NULL, now(), now())", command.toString());
        assertTrue(kit.ops.unreactedFailures(10).isEmpty());
    }

    @Test
    void recoveryNeverReportsAnUncertainOutcomeAnAcknowledgedCommandOrARecentOutcomeStillBeingReactedTo() {
        kit.jdbc.execute("DELETE FROM rule_trigger_facts WHERE trigger_type = 'COMMAND_PUBLICATION_FAILED'");
        deadLetter("UNCERTAIN_EXPIRED", false, 30);
        deadLetter("RETRY_EXHAUSTED", true, 30);
        deadLetter("EXPIRED", false, 1);
        assertTrue(kit.ops.unreactedFailures(10).isEmpty(),
                "uncertain (a rule could compensate for a command that may execute), acknowledged, and not-yet-settled outcomes are all excluded");
    }

    @Test
    void aStillPendingTwinVerificationKeepsAnOldDeadLetterReportedWithTwinPendingTrue() {
        kit.jdbc.execute("DELETE FROM rule_trigger_facts WHERE trigger_type = 'COMMAND_PUBLICATION_FAILED'");
        UUID command = deadLetter("RETRY_EXHAUSTED", false, 3600);                       // an hour old: far outside the rule-fact lookback
        UUID device = kit.jdbc.queryForObject("SELECT device_id FROM command_outbox WHERE command_id = ?", UUID.class, command);
        kit.jdbc.update("INSERT INTO current_desired_twin_state(device_id, target_key, subtarget_key, legacy_capability_type, capability_key, value_type,"
                + " boolean_value, source, command_id, requested_at, accepted_for_dispatch_at)"
                + " VALUES (?, 'legacy:LED', '', 'LED', 'led', 'BOOLEAN', true, 'RULE_ACTION', ?, now(), now())", device, command);
        kit.jdbc.update("INSERT INTO current_twin_verification_state(device_id, target_key, subtarget_key, desired_command_id, verification_status, verification_updated_at)"
                + " VALUES (?, 'legacy:LED', '', ?, 'PENDING', now())", device, command);
        var found = kit.ops.unreactedFailures(10);
        assertEquals(1, found.size());
        assertTrue(found.get(0).twinPending(), "the Twin was never told: it is reported with no age limit");
        // once the Twin has been told (PENDING moves to UNVERIFIED) and the old rule-fact lookback has passed, nothing is left to recover
        kit.jdbc.update("UPDATE current_twin_verification_state SET verification_status = 'UNVERIFIED' WHERE desired_command_id = ?", command);
        assertTrue(kit.ops.unreactedFailures(10).isEmpty());
    }

    @Test
    void anOutcomeOlderThanTheLookbackIsNotReportedUnlessItsTwinVerificationIsStillPending() {
        kit.jdbc.execute("DELETE FROM rule_trigger_facts WHERE trigger_type = 'COMMAND_PUBLICATION_FAILED'");
        deadLetter("RETRY_EXHAUSTED", false, 3600);
        assertTrue(kit.ops.unreactedFailures(10).isEmpty(), "an hour old with no pending verification: nothing left to recover");
    }
}
