package com.nexus.action.outbox;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The REAL dispatcher (scheduled loops on, real SQL, a scriptable broker) driving retries end to end: a command that was
 * proven not sent is claimed again by the same dispatcher or by another instance, with the identical bytes, within the attempt
 * budget; an uncertain retry attempt is never retried; a restart resumes a pending retry; failure signals are reported once and
 * recovered after a lost reaction. Ledger: P4-026, 027, 030, 032, 035, 041, 042, 043, 045.
 */
@Testcontainers
@Timeout(120)
class CommandOutboxRetryDispatcherTest {

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
        kit.jdbc.execute("TRUNCATE command_state_history, command_outbox");
        kit.jdbc.execute("DELETE FROM rule_trigger_facts WHERE trigger_type = 'COMMAND_PUBLICATION_FAILED'");
    }

    @AfterEach
    void quiesce() throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (kit.jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE state = 'IN_FLIGHT'", Integer.class) > 0
                && System.nanoTime() < end) Thread.sleep(25);
        Thread.sleep(150);
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(25);
        }
    }

    private CommandOutboxDispatcher dispatcher(OutboxTestKit k, FakeBroker broker, FakeBroker.RecordingListener listener) {
        return new CommandOutboxDispatcher(k.ops, broker, FakeBroker.settings(2, Duration.ofSeconds(1), Duration.ofSeconds(3)),
                FakeBroker.listenerProvider(listener), FakeBroker.noMeters());
    }

    /** Drives passes until the condition holds (the scheduler is not running in these tests; one pass is one poll). */
    private void drive(CommandOutboxDispatcher d, BooleanSupplier done, String what) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            d.runPass();
            Thread.sleep(40);
        }
    }

    @Test
    void aCommandNotSentTwiceThenSentIsPublishedOnceWithIdenticalBytesAndNoFailureSignalPerAttempt() throws Exception {
        FakeBroker broker = new FakeBroker();
        AtomicInteger calls = new AtomicInteger();
        broker.behaviour = (t, b) -> calls.incrementAndGet() <= 2 ? BrokerCommandPublisher.Attempt.notSent("CLIENT_NOT_CONNECTED")
                : BrokerCommandPublisher.Attempt.confirmed();
        var listener = new FakeBroker.RecordingListener();
        var dispatcher = dispatcher(kit, broker, listener);
        UUID command = kit.enqueueDue(kit.device(), 0);
        String stored = kit.jdbc.queryForObject("SELECT payload_text FROM command_outbox WHERE command_id = ?", String.class, command);

        drive(dispatcher, () -> "PUBLISHED".equals(kit.state(command)), "publication after two non-publications");
        assertEquals(3, broker.calls.size());
        for (var call : broker.calls) assertEquals(stored, call.text(), "every attempt put the identical stored bytes on the wire");
        assertEquals(3, kit.attempts(command));
        assertEquals("SUCCESS", kit.actionStatus(command));
        assertEquals(List.of("ATTEMPT_STARTED", "ATTEMPT_FAILED_TRANSIENT", "ATTEMPT_STARTED", "ATTEMPT_FAILED_TRANSIENT", "ATTEMPT_STARTED", "PUBLISHED"),
                kit.facts(command));
        await(() -> listener.published.size() == 1, "published reaction");
        assertTrue(listener.deadLettered.isEmpty(), "an intermediate non-publication raises no failure signal: failure is reported once, at the terminal outcome");
        for (int i = 0; i < 5; i++) { dispatcher.runPass(); dispatcher.sweepOnce(); }
        assertEquals(3, broker.calls.size(), "a published command is never sent again");
    }

    @Test
    void anUncertainRetryAttemptEndsTheRetriesAndIsNeverResentNorReportedAsAFailure() throws Exception {
        FakeBroker broker = new FakeBroker();
        AtomicInteger calls = new AtomicInteger();
        broker.behaviour = (t, b) -> calls.incrementAndGet() == 1 ? BrokerCommandPublisher.Attempt.notSent("CLIENT_NOT_CONNECTED")
                : BrokerCommandPublisher.Attempt.uncertain("PUBLISH_TIMEOUT");
        var listener = new FakeBroker.RecordingListener();
        var dispatcher = dispatcher(kit, broker, listener);
        UUID command = kit.enqueueDue(kit.device(), 0);
        drive(dispatcher, () -> "DEAD_LETTER".equals(kit.state(command)), "uncertain terminal outcome");
        for (int i = 0; i < 8; i++) { dispatcher.runPass(); dispatcher.sweepOnce(); Thread.sleep(60); }
        assertEquals(2, broker.calls.size(), "attempts remained (3), but an uncertain attempt is never retried");
        assertEquals("UNCERTAIN_EXPIRED", kit.terminalReason(command));
        assertEquals(2, kit.attempts(command));
        assertTrue(kit.actionMessage(command).startsWith("DELIVERY_UNCONFIRMED"));
        await(() -> listener.deadLettered.size() == 1, "reaction");
        assertTrue(listener.deadLettered.get(0).uncertain());
    }

    @Test
    void aWorkerThatCrashedAfterSchedulingARetryLosesNothingAnotherInstanceCompletesIt() throws Exception {
        FakeBroker broker = new FakeBroker();
        broker.behaviour = (t, b) -> BrokerCommandPublisher.Attempt.notSent("CLIENT_NOT_CONNECTED");
        var listener = new FakeBroker.RecordingListener();
        UUID command = kit.enqueueDue(kit.device(), 0);
        var first = dispatcher(kit, broker, listener);
        drive(first, () -> "RETRY_WAIT".equals(kit.state(command)), "the retry to be scheduled");
        // the first instance "crashes": it is never run again. A fresh instance (new process, nothing in memory) resumes from the database.
        FakeBroker recovered = new FakeBroker();
        try (OutboxTestKit restarted = new OutboxTestKit(db)) {
            var second = dispatcher(restarted, recovered, listener);
            drive(second, () -> "PUBLISHED".equals(kit.state(command)), "the retry to be delivered after the restart");
        }
        assertFalse(recovered.calls.isEmpty(), "the second instance published the pending retry");
        assertEquals("SUCCESS", kit.actionStatus(command));
        assertTrue(kit.attempts(command) >= 2);
        for (var call : recovered.calls) assertEquals(kit.jdbc.queryForObject("SELECT payload_text FROM command_outbox WHERE command_id = ?", String.class, command), call.text());
    }

    @Test
    void aWorkerThatCrashedAfterClaimingARetryIsRecoveredAsUncertainAndNeverRepublished() throws Exception {
        FakeBroker broker = new FakeBroker();
        broker.behaviour = (t, b) -> BrokerCommandPublisher.Attempt.notSent("CLIENT_NOT_CONNECTED");
        var listener = new FakeBroker.RecordingListener();
        UUID command = kit.enqueueDue(kit.device(), 0);
        var d = dispatcher(kit, broker, listener);
        drive(d, () -> "RETRY_WAIT".equals(kit.state(command)), "retry scheduled");
        kit.jdbc.update("UPDATE command_outbox SET next_attempt_at = now() - INTERVAL '1 millisecond' WHERE command_id = ?", command);
        var claimed = kit.ops.claim(command, "crashing-worker", Duration.ofSeconds(6)).orElseThrow();   // claimed, then the process dies before publishing
        assertEquals(2, claimed.attempt());
        int callsBefore = broker.calls.size();
        kit.expireLease(command);
        assertEquals(1, d.sweepOnce());
        assertEquals("UNCERTAIN_EXPIRED", kit.terminalReason(command));
        for (int i = 0; i < 6; i++) { d.runPass(); d.sweepOnce(); }
        assertEquals(callsBefore, broker.calls.size(), "an attempt of unknown outcome is never republished");
    }

    @Test
    void twoDispatcherInstancesNeverPublishTheSameRetryAttemptTwice() throws Exception {
        FakeBroker broker = new FakeBroker();
        AtomicInteger calls = new AtomicInteger();
        broker.behaviour = (t, b) -> calls.incrementAndGet() <= 2 ? BrokerCommandPublisher.Attempt.notSent("CLIENT_NOT_CONNECTED")
                : BrokerCommandPublisher.Attempt.confirmed();
        var listener = new FakeBroker.RecordingListener();
        List<UUID> commands = new ArrayList<>();
        for (int i = 0; i < 6; i++) commands.add(kit.enqueueDue(kit.device(), i));
        try (OutboxTestKit second = new OutboxTestKit(db)) {
            var one = dispatcher(kit, broker, listener);
            var two = dispatcher(second, broker, listener);
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (commands.stream().anyMatch(c -> !"PUBLISHED".equals(kit.state(c)) && !"DEAD_LETTER".equals(kit.state(c)))) {
                if (System.nanoTime() > end) throw new AssertionError("timed out");
                one.runPass(); two.runPass(); Thread.sleep(30);
            }
        }
        for (UUID c : commands) {
            long sent = broker.callsFor(c.toString());
            assertEquals(kit.attempts(c), sent, "published attempts == recorded attempts for " + c + ": no attempt was ever published twice");
        }
    }

    @Test
    void anExhaustedCommandIsReportedOnceAndAReactionLostToACrashIsRecoveredIdempotently() throws Exception {
        FakeBroker broker = new FakeBroker();
        broker.behaviour = (t, b) -> BrokerCommandPublisher.Attempt.notSent("CLIENT_NOT_CONNECTED");
        var listener = new FakeBroker.RecordingListener();
        var d = dispatcher(kit, broker, listener);
        UUID command = kit.enqueueDue(kit.device(), 0);
        drive(d, () -> "DEAD_LETTER".equals(kit.state(command)), "retry exhaustion");
        await(() -> listener.deadLettered.size() == 1, "the single terminal reaction");
        assertEquals(3, broker.calls.size());
        // the outcome is too recent: recovery must not race the normal reaction
        assertEquals(0, d.recoverReactions());
        // simulate "the process died after the commit, before the reaction": age the outcome and leave the rule fact missing
        Thread.sleep(5300);   // terminal rows are immutable (V37), so the outcome is simply allowed to settle past the five second guard
        assertEquals(1, d.recoverReactions(), "the lost failure signal is found");
        assertEquals(command, listener.recovered.get(0).commandId());
        assertEquals(OutboxTerminalReason.RETRY_EXHAUSTED, listener.recovered.get(0).reason());
        // once the (idempotent) fact exists the dead letter is settled
        kit.jdbc.update("INSERT INTO rule_trigger_facts(fact_id, trigger_type, source_key, occurred_at, recorded_at)"
                + " VALUES (gen_random_uuid(), 'COMMAND_PUBLICATION_FAILED', ?, now(), now())", command.toString());
        assertEquals(0, d.recoverReactions());
        assertFalse(listener.recovered.size() > 1);
        assertEquals(3, broker.calls.size(), "recovery re-applies signals only: it never publishes");
    }

    @Test
    void noRestartAndNoScheduledJobEverResendsADeadLetterWhetherExhaustedOrExpiredOrUncertain() throws Exception {
        FakeBroker broker = new FakeBroker();
        broker.behaviour = (t, b) -> BrokerCommandPublisher.Attempt.notSent("CLIENT_NOT_CONNECTED");
        var listener = new FakeBroker.RecordingListener();
        var d = dispatcher(kit, broker, listener);
        UUID exhausted = kit.enqueueDue(kit.device(), 0);
        drive(d, () -> "DEAD_LETTER".equals(kit.state(exhausted)), "retry exhaustion");
        UUID expired = kit.enqueue(kit.device(), java.time.Instant.now().minusSeconds(60));
        d.sweepOnce();
        assertEquals("EXPIRED", kit.terminalReason(expired));
        int before = broker.calls.size();
        FakeBroker afterRestart = new FakeBroker();
        try (OutboxTestKit restarted = new OutboxTestKit(db)) {                 // a restart: a fresh process with no memory
            var again = dispatcher(restarted, afterRestart, new FakeBroker.RecordingListener());
            for (int i = 0; i < 12; i++) { again.runPass(); again.sweepOnce(); again.recoverReactions(); Thread.sleep(30); }
            for (int i = 0; i < 12; i++) { d.runPass(); d.sweepOnce(); d.recoverReactions(); Thread.sleep(30); }
        }
        assertEquals(0, afterRestart.calls.size(), "the restarted instance sent nothing");
        assertEquals(before, broker.calls.size(), "and the original instance sent nothing further");
        assertEquals("DEAD_LETTER", kit.state(exhausted));
        assertEquals("DEAD_LETTER", kit.state(expired));
    }
}
