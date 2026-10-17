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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Claim, lease, fenced outcome, expiry and lease recovery on the real SQL (disposable TimescaleDB/PostgreSQL, V1 to latest).
 * Time-sensitive facts are judged by the database clock; nothing here uses a mocked clock for those decisions.
 */
@Testcontainers
class OutboxDeliveryOperationsTest {

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

    private Claimed claim(UUID command) {
        Optional<Claimed> c = kit.ops.claim(command, "owner-" + UUID.randomUUID(), LEASE);
        assertTrue(c.isPresent(), "claim should succeed");
        return c.get();
    }

    // ------------------------------------------------------------------------------------------------- claim

    @Test
    void aClaimSetsTheLeaseIncrementsTheAttemptAndRecordsTheAttemptStart() {
        UUID device = kit.device();
        UUID command = kit.enqueueDue(device, 0);
        assertEquals(List.of(command), kit.ops.candidates(10));
        Claimed claimed = claim(command);
        assertEquals(1, claimed.attempt());
        assertEquals("IN_FLIGHT", kit.state(command));
        assertEquals(1, kit.attempts(command));
        Double leaseSeconds = kit.jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM (lease_expires_at - now())) FROM command_outbox WHERE command_id = ?", Double.class, command);
        assertTrue(leaseSeconds > 4.5 && leaseSeconds <= 6.5, "lease is about 6 s on the database clock: " + leaseSeconds);
        assertEquals(List.of("ATTEMPT_STARTED"), kit.facts(command));
        assertTrue(kit.ops.candidates(10).isEmpty(), "an IN_FLIGHT command is never a candidate again");
        assertTrue(kit.ops.claim(command, "someone-else", LEASE).isEmpty(), "a second claim of the same row loses");
        assertEquals(1, kit.attempts(command));
    }

    @Test
    void theClaimedPayloadIsTheStoredTextVerbatimWithItsStoredHash() {
        UUID command = kit.enqueueDue(kit.device(), 0);
        String stored = kit.jdbc.queryForObject("SELECT payload_text FROM command_outbox WHERE command_id = ?", String.class, command);
        String hash = kit.jdbc.queryForObject("SELECT payload_sha256 FROM command_outbox WHERE command_id = ?", String.class, command);
        Claimed claimed = claim(command);
        assertEquals(stored, claimed.payload().text());
        assertEquals(hash, claimed.payload().sha256());
        assertTrue(claimed.payload().verify());
        assertEquals("nexus/devices/" + claimed.deviceId() + "/commands", claimed.topic());
    }

    @Test
    void anOlderUnresolvedCommandOfTheSameDeviceBlocksNewerOnesAndDifferentDevicesAreIndependent() {
        UUID deviceA = kit.device(), deviceB = kit.device();
        UUID a1 = kit.enqueueDue(deviceA, 0), a2 = kit.enqueueDue(deviceA, 1), a3 = kit.enqueueDue(deviceA, 2);
        UUID b1 = kit.enqueueDue(deviceB, 3);
        assertEquals(List.of(a1, b1), kit.ops.candidates(10), "only the oldest of each device, oldest first");
        assertTrue(kit.ops.claim(a2, "x", LEASE).isEmpty(), "a newer command cannot be claimed over an older QUEUED one");
        assertTrue(kit.ops.claim(a3, "x", LEASE).isEmpty());
        Claimed first = claim(a1);
        assertEquals(List.of(b1), kit.ops.candidates(10), "while A1 is IN_FLIGHT nothing of device A is a candidate");
        assertTrue(kit.ops.claim(a2, "x", LEASE).isEmpty(), "a newer command cannot overtake an IN_FLIGHT one");
        assertEquals(Disposition.PUBLISHED, kit.ops.complete(first, AttemptResult.CONFIRMED, null).disposition());
        assertEquals(List.of(a2, b1), kit.ops.candidates(10), "A2 becomes next only after A1 is resolved");
    }

    @Test
    void aFailedOlderCommandUnblocksTheNextOneInOrderOnlyWhenItIsTerminalAndNeverBeforeThat() {
        UUID device = kit.device();
        UUID c1 = kit.enqueueDue(device, 0), c2 = kit.enqueueDue(device, 1);
        Claimed first = claim(c1);
        // proven not sent: the command waits for its retry (RETRY_WAIT) and still blocks the newer command
        kit.ops.complete(first, AttemptResult.NOT_SENT, "CLIENT_NOT_CONNECTED");
        assertEquals("RETRY_WAIT", kit.state(c1));
        assertTrue(kit.ops.candidates(10).isEmpty(), "C2 may not overtake C1 while C1 waits for its retry");
        // an integrity failure is terminal: only then is C2 released
        kit.jdbc.update("UPDATE command_outbox SET next_attempt_at = now() - INTERVAL '1 millisecond' WHERE command_id = ?", c1);
        kit.ops.complete(claim(c1), AttemptResult.PAYLOAD_INTEGRITY, null);
        assertEquals("DEAD_LETTER", kit.state(c1));
        assertEquals(List.of(c2), kit.ops.candidates(10));
    }

    @Test
    void anExpiredCommandIsNeverACandidateNeverClaimableAndIsDeadLetteredWithoutBlockingNewerOnes() {
        UUID device = kit.device();
        UUID expired = kit.enqueue(device, Instant.now().minusSeconds(60));
        UUID fresh = kit.enqueueDue(device, 0);
        assertFalse(kit.ops.candidates(10).contains(expired), "expired rows are never selected");
        assertTrue(kit.ops.claim(expired, "x", LEASE).isEmpty(), "the claim itself refuses an expired row (database clock)");
        assertTrue(kit.ops.candidates(10).isEmpty(), "the expired head still blocks the newer command until it is resolved");
        List<Resolution> swept = kit.ops.expireDue(10);
        assertEquals(1, swept.size());
        assertEquals(OutboxTerminalReason.EXPIRED, swept.get(0).reason());
        assertEquals("DEAD_LETTER", kit.state(expired));
        assertEquals("EXPIRED", kit.terminalReason(expired));
        assertEquals(0, kit.attempts(expired), "an expired command was never attempted");
        assertEquals("FAILURE", kit.actionStatus(expired));
        assertTrue(kit.actionMessage(expired).contains("never sent"));
        assertEquals(List.of("DEAD_LETTERED"), kit.facts(expired));
        assertEquals(List.of(fresh), kit.ops.candidates(10), "the newer command is released");
        assertTrue(kit.ops.expireDue(10).isEmpty(), "idempotent");
    }

    // ----------------------------------------------------------------------------------------------- outcomes

    @Test
    void aConfirmedPublishInsideTheWindowIsPublishedAndOnlyThenReportedAsSuccess() {
        UUID command = kit.enqueueDue(kit.device(), 0);
        Claimed claimed = claim(command);
        assertEquals("ACCEPTED_FOR_DISPATCH", kit.actionStatus(command), "claiming alone never reports success");
        Resolution r = kit.ops.complete(claimed, AttemptResult.CONFIRMED, null);
        assertTrue(r.published());
        assertTrue(r.actionHistoryUpdated());
        assertEquals("PUBLISHED", kit.state(command));
        assertEquals("SUCCESS", kit.actionStatus(command));
        assertNotNull(kit.jdbc.queryForObject("SELECT published_at FROM action_history WHERE request_id = ?", java.sql.Timestamp.class, command));
        assertNotNull(kit.jdbc.queryForObject("SELECT ack_deadline_at FROM action_history WHERE request_id = ?", java.sql.Timestamp.class, command));
        assertNull(kit.terminalReason(command));
        assertEquals(List.of("ATTEMPT_STARTED", "PUBLISHED"), kit.facts(command));
        assertEquals(List.of(1, 2), kit.sequences(command), "gapless sequence");
    }

    @Test
    void aFirstDefiniteNonPublicationParksTheCommandForARetryAndIsNeitherAPublishNorAFailure() {
        UUID command = kit.enqueueDue(kit.device(), 0);
        Resolution r = kit.ops.complete(claim(command), AttemptResult.NOT_SENT, "CLIENT_NOT_CONNECTED");
        assertTrue(r.retryScheduled());
        assertFalse(r.published());
        assertFalse(r.deadLettered());
        assertEquals("RETRY_WAIT", kit.state(command));
        assertNull(kit.terminalReason(command));
        assertEquals("ACCEPTED_FOR_DISPATCH", kit.actionStatus(command), "a pending retry is neither a success nor a failure");
        assertTrue(kit.actionMessage(command).contains("not sent"));
        assertFalse(kit.actionMessage(command).toLowerCase().contains("published to mqtt"));
        assertNull(kit.jdbc.queryForObject("SELECT first_publish_confirmed_at FROM command_outbox WHERE command_id = ?", java.sql.Timestamp.class, command));
        assertEquals(List.of("ATTEMPT_STARTED", "ATTEMPT_FAILED_TRANSIENT"), kit.facts(command));
        assertEquals(1, kit.attempts(command));
        assertTrue(kit.jdbc.queryForObject("SELECT next_attempt_at > now() FROM command_outbox WHERE command_id = ?", Boolean.class, command),
                "the retry is scheduled in the future on the database clock");
    }

    @Test
    void anUncertainOutcomeIsNeverARecordedAsAPlainFailureOrASuccess() {
        UUID command = kit.enqueueDue(kit.device(), 0);
        Resolution r = kit.ops.complete(claim(command), AttemptResult.UNCERTAIN, "PUBLISH_TIMEOUT");
        assertTrue(r.deadLettered());
        assertEquals("UNCERTAIN_EXPIRED", kit.terminalReason(command));
        assertEquals("FAILURE", kit.actionStatus(command));
        assertTrue(kit.actionMessage(command).startsWith("DELIVERY_UNCONFIRMED"), kit.actionMessage(command));
        assertEquals(List.of("ATTEMPT_STARTED", "ATTEMPT_UNCERTAIN", "DEAD_LETTERED"), kit.facts(command));
    }

    @Test
    void aPayloadIntegrityFailureIsRecordedAndNeverLooksLikeAPublish() {
        UUID command = kit.enqueueDue(kit.device(), 0);
        Resolution r = kit.ops.complete(claim(command), AttemptResult.PAYLOAD_INTEGRITY, null);
        assertEquals(OutboxTerminalReason.PAYLOAD_INTEGRITY, r.reason());
        assertEquals("PAYLOAD_INTEGRITY", kit.terminalReason(command));
        assertEquals("FAILURE", kit.actionStatus(command));
    }

    @Test
    void aPublishThatCompletesAfterTheWindowIsLateAndUncertainNeverANormalPublish() throws Exception {
        UUID device = kit.device();
        // payload time 13 s ago: the 15 s window ends about 2 s from now
        UUID command = kit.enqueue(device, Instant.now().minusSeconds(13));
        Claimed claimed = claim(command);
        Thread.sleep(3200);
        Resolution r = kit.ops.complete(claimed, AttemptResult.CONFIRMED, null);
        assertTrue(r.deadLettered(), "the window ended before the completion was recorded");
        assertEquals("UNCERTAIN_EXPIRED", kit.terminalReason(command));
        assertEquals("PUBLISH_COMPLETED_AFTER_EXPIRY", kit.jdbc.queryForObject("SELECT last_error_code FROM command_outbox WHERE command_id = ?", String.class, command));
        assertEquals("FAILURE", kit.actionStatus(command));
        assertTrue(kit.actionMessage(command).startsWith("DELIVERY_UNCONFIRMED"));
        assertEquals(List.of("ATTEMPT_STARTED", "ATTEMPT_UNCERTAIN", "DEAD_LETTERED"), kit.facts(command));
    }

    @Test
    void anAcknowledgementRecordedBeforeThePublishOutcomeIsNotErased() {
        UUID command = kit.enqueueDue(kit.device(), 0);
        Claimed claimed = claim(command);
        kit.jdbc.update("UPDATE action_history SET ack_status = 'ACCEPTED', ack_received_at = now() WHERE request_id = ?", command);
        kit.ops.complete(claimed, AttemptResult.CONFIRMED, null);
        assertEquals("ACCEPTED", kit.jdbc.queryForObject("SELECT ack_status FROM action_history WHERE request_id = ?", String.class, command));
        assertEquals("SUCCESS", kit.actionStatus(command));
    }

    @Test
    void anActionHistoryRowThatIsNoLongerAcceptedIsNeverOverwrittenByTheOutcome() {
        UUID command = kit.enqueueDue(kit.device(), 0);
        Claimed claimed = claim(command);
        kit.jdbc.update("UPDATE action_history SET status = 'FAILURE', message = 'someone else decided' WHERE request_id = ?", command);
        Resolution r = kit.ops.complete(claimed, AttemptResult.CONFIRMED, null);
        assertTrue(r.published());
        assertFalse(r.actionHistoryUpdated(), "the conditional update changed nothing, so no derived fact may be emitted");
        assertEquals("someone else decided", kit.actionMessage(command));
    }

    // ------------------------------------------------------------------------------------------ lease + fencing

    @Test
    void anExpiredLeaseIsRecoveredAsAnUncertainDeadLetterAndIsNeverClaimedAgain() {
        UUID device = kit.device();
        UUID c1 = kit.enqueueDue(device, 0), c2 = kit.enqueueDue(device, 1);
        claim(c1);
        assertTrue(kit.ops.recoverExpiredLeases(10).isEmpty(), "a live lease is not recovered");
        kit.expireLease(c1);
        assertTrue(kit.ops.candidates(10).isEmpty(), "an IN_FLIGHT command whose lease ended still blocks newer commands until recovered");
        List<Resolution> recovered = kit.ops.recoverExpiredLeases(10);
        assertEquals(1, recovered.size());
        assertEquals("DEAD_LETTER", kit.state(c1));
        assertEquals("UNCERTAIN_EXPIRED", kit.terminalReason(c1));
        assertEquals("FAILURE", kit.actionStatus(c1));
        assertTrue(kit.actionMessage(c1).startsWith("DELIVERY_UNCONFIRMED"));
        assertEquals(List.of("ATTEMPT_STARTED", "ATTEMPT_UNCERTAIN", "DEAD_LETTERED"), kit.facts(c1));
        assertEquals(1, kit.attempts(c1), "recovery never re-claims: no repeated publication from a lost worker");
        assertTrue(kit.ops.candidates(10).isEmpty(), "c2 is held: the lost attempt may still reach the device (see UncertainDeliveryBarrierTest)");
        assertTrue(kit.ops.recoverExpiredLeases(10).isEmpty(), "idempotent");
    }

    @Test
    void aStaleWorkerWhoseLeaseWasRecoveredWritesNothing() {
        UUID command = kit.enqueueDue(kit.device(), 0);
        Claimed stale = claim(command);
        kit.expireLease(command);
        kit.ops.recoverExpiredLeases(10);
        List<String> factsBefore = kit.facts(command);
        String messageBefore = kit.actionMessage(command);
        Resolution r = kit.ops.complete(stale, AttemptResult.CONFIRMED, null);
        assertEquals(Disposition.FENCED, r.disposition());
        assertEquals("DEAD_LETTER", kit.state(command), "the newer state is not overwritten by the stale completion");
        assertEquals("FAILURE", kit.actionStatus(command));
        assertEquals(messageBefore, kit.actionMessage(command));
        assertEquals(factsBefore, kit.facts(command), "no fact was appended");
    }

    @Test
    void aWorkerWithTheWrongLeaseOwnerOrAttemptIsFencedOutAndTheRowIsUntouched() {
        UUID command = kit.enqueueDue(kit.device(), 0);
        Claimed real = claim(command);
        Claimed wrongOwner = new Claimed(real.commandId(), real.deviceId(), real.topic(), real.payload(), real.attempt(), real.maxAttempts(), "impostor", real.correlationId());
        Claimed wrongAttempt = new Claimed(real.commandId(), real.deviceId(), real.topic(), real.payload(), real.attempt() + 1, real.maxAttempts(), real.leaseOwner(), real.correlationId());
        assertEquals(Disposition.FENCED, kit.ops.complete(wrongOwner, AttemptResult.CONFIRMED, null).disposition());
        assertEquals(Disposition.FENCED, kit.ops.complete(wrongAttempt, AttemptResult.CONFIRMED, null).disposition());
        assertEquals("IN_FLIGHT", kit.state(command));
        assertEquals("ACCEPTED_FOR_DISPATCH", kit.actionStatus(command));
        assertEquals(Disposition.PUBLISHED, kit.ops.complete(real, AttemptResult.CONFIRMED, null).disposition());
        assertEquals(Disposition.FENCED, kit.ops.complete(real, AttemptResult.CONFIRMED, null).disposition(), "an outcome applies once");
    }

    @Test
    void aFailureWhileWritingTheOutcomeRollsBackTheWholeOutcomeTransaction() {
        java.util.concurrent.atomic.AtomicBoolean failHistory = new java.util.concurrent.atomic.AtomicBoolean(true);
        try (OutboxTestKit failing = new OutboxTestKit(db, jdbc -> new CommandStateHistory(jdbc) {
            @Override
            public void append(UUID commandId, Fact fact, String from, String to, int attempt, String reason, Source source,
                               String correlationId, Instant occurredAt, String detail) {
                if (failHistory.get() && (fact == Fact.DEAD_LETTERED || fact == Fact.PUBLISHED)) throw new IllegalStateException("injected history failure");
                super.append(commandId, fact, from, to, attempt, reason, source, correlationId, occurredAt, detail);
            }
        })) {
            UUID command = failing.enqueueDue(failing.device(), 0);
            Claimed claimed = failing.ops.claim(command, "w", LEASE).orElseThrow();
            assertThrows(IllegalStateException.class, () -> failing.ops.complete(claimed, AttemptResult.CONFIRMED, null));
            assertEquals("IN_FLIGHT", failing.state(command), "the outbox change rolled back");
            assertEquals("ACCEPTED_FOR_DISPATCH", failing.actionStatus(command), "the ActionHistory change rolled back");
            assertEquals(List.of("ATTEMPT_STARTED"), failing.facts(command), "no orphan fact");
            // the row is not lost: once the fault clears, lease recovery resolves it honestly
            failHistory.set(false);
            failing.expireLease(command);
            assertEquals(1, failing.ops.recoverExpiredLeases(10).size());
            assertEquals("UNCERTAIN_EXPIRED", failing.terminalReason(command));
        }
    }

    @Test
    @Timeout(30)
    void anOutcomeNeverTouchesTheDeviceRow() {
        // The outcome transaction must not lock the device: hold the device row FOR UPDATE in another transaction and the
        // outcome still completes (it would block here if the dispatcher took the device lock).
        UUID device = kit.device();
        UUID command = kit.enqueueDue(device, 0);
        Claimed claimed = claim(command);
        try (var holder = kit.dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var s = holder.createStatement()) {
                s.execute("SELECT id FROM devices WHERE id = '" + device + "' FOR UPDATE");
                Resolution r = kit.ops.complete(claimed, AttemptResult.CONFIRMED, null);
                assertTrue(r.published(), "completed while another transaction held the device row");
            } finally {
                holder.rollback();
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------------------ database clock

    @Test
    void anApplicationClockOneHourAheadOrBehindChangesNoClaimExpiryOrLeaseDecision() {
        for (long skewSeconds : new long[]{3600, -3600}) {
            Clock skewed = Clock.fixed(Instant.now().plusSeconds(skewSeconds), java.time.ZoneOffset.UTC);
            try (OutboxTestKit skewedKit = new OutboxTestKit(db, null, skewed)) {
                kit.jdbc.execute("TRUNCATE command_state_history, command_outbox");
                UUID device = kit.device();
                UUID live = skewedKit.enqueueDue(device, 0);
                UUID expired = skewedKit.enqueue(skewedKit.device(), Instant.now().minusSeconds(60));
                // the claim is judged by the database: the due command is claimable and its lease is relative to DATABASE time
                assertTrue(skewedKit.ops.candidates(10).contains(live), "skew " + skewSeconds);
                assertFalse(skewedKit.ops.candidates(10).contains(expired), "an expired row stays expired for an instance whose clock is behind");
                Claimed claimed = skewedKit.ops.claim(live, "skewed", LEASE).orElseThrow();
                Double leaseSeconds = skewedKit.jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM (lease_expires_at - now())) FROM command_outbox WHERE command_id = ?", Double.class, live);
                assertTrue(leaseSeconds > 4.5 && leaseSeconds <= 6.5, "lease is about 6 s on the DATABASE clock regardless of skew: " + leaseSeconds);
                // a live lease is never recovered, a live window never expires, an ended window always does
                assertTrue(skewedKit.ops.recoverExpiredLeases(10).isEmpty(), "a live lease is not recovered by an instance whose clock is ahead");
                var expiredNow = skewedKit.ops.expireDue(10);
                assertEquals(List.of(expired), expiredNow.stream().map(Resolution::commandId).toList(), "only the row whose window ended on the database clock expires");
                assertEquals("QUEUED", skewedKit.state(kit.enqueueDue(kit.device(), 5)), "a fresh window is untouched");
                assertEquals("IN_FLIGHT", skewedKit.state(live));
                assertEquals(Disposition.PUBLISHED, skewedKit.ops.complete(claimed, AttemptResult.CONFIRMED, null).disposition(),
                        "a publish confirmed inside the database window is a publish whatever the application clock says");
            }
        }
    }
}
