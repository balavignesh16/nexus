package com.nexus.action.outbox;

import com.nexus.action.outbox.OutboxDeliveryOperations.Claimed;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dispatcher against a real PostgreSQL with a scripted broker: verbatim bytes, exactly one attempt, no endless claiming,
 * honest outcomes, bounded workers, fenced reactions. The scheduling annotations are not exercised here: every pass is
 * driven explicitly so the assertions are deterministic.
 */
@Testcontainers
@Timeout(90)
class CommandOutboxDispatcherTest {

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
    }

    /** A test must not leave a worker mid-outcome: the next test truncates the tables and would otherwise deadlock with it. */
    @org.junit.jupiter.api.AfterEach
    void quiesce() throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (kit.jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE state = 'IN_FLIGHT'", Integer.class) > 0
                && System.nanoTime() < end) Thread.sleep(25);
        Thread.sleep(150);   // let a worker that just committed finish returning
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(25);
        }
    }

    private CommandOutboxDispatcher dispatcher(FakeBroker broker, FakeBroker.RecordingListener listener, int workers) {
        return new CommandOutboxDispatcher(kit.ops, broker, FakeBroker.settings(workers, Duration.ofSeconds(1), Duration.ofSeconds(3)),
                FakeBroker.listenerProvider(listener), FakeBroker.noMeters());
    }

    @Test
    void aQueuedCommandIsPublishedVerbatimOnceAndOnlyThenReportedAsSuccess() throws Exception {
        FakeBroker broker = new FakeBroker();
        var listener = new FakeBroker.RecordingListener();
        var dispatcher = dispatcher(broker, listener, 2);
        UUID command = kit.enqueueDue(kit.device(), 0);
        String stored = kit.jdbc.queryForObject("SELECT payload_text FROM command_outbox WHERE command_id = ?", String.class, command);
        assertEquals("ACCEPTED_FOR_DISPATCH", kit.actionStatus(command));

        assertEquals(1, dispatcher.runPass());
        await(() -> "PUBLISHED".equals(kit.state(command)), "publication");

        assertEquals(1, broker.calls.size());
        assertEquals(stored, broker.calls.get(0).text(), "the published bytes are exactly the stored payload");
        assertEquals("SUCCESS", kit.actionStatus(command));
        await(() -> listener.published.size() == 1, "the post-commit reaction");
        assertTrue(listener.deadLettered.isEmpty());
        assertEquals(0, dispatcher.runPass(), "a published command is never claimed again");
        assertEquals(1, broker.calls.size());
    }

    @Test
    void theTimestampAndBytesAreIdenticalToAcceptanceAndNeverRefreshedAtPublishTime() throws Exception {
        FakeBroker broker = new FakeBroker();
        var dispatcher = dispatcher(broker, new FakeBroker.RecordingListener(), 1);
        UUID command = kit.enqueue(kit.device(), Instant.now().minusSeconds(8));   // accepted 8 s ago
        long acceptedEpoch = kit.jdbc.queryForObject("SELECT payload_epoch_seconds FROM command_outbox WHERE command_id = ?", Long.class, command);
        Thread.sleep(1100);
        dispatcher.runPass();
        await(() -> "PUBLISHED".equals(kit.state(command)), "publication");
        String published = broker.calls.get(0).text();
        assertTrue(published.contains("\"timestamp\":" + acceptedEpoch), "the acceptance-time timestamp is published, not a fresh one: " + published);
        assertTrue(Instant.now().getEpochSecond() > acceptedEpoch + 7);
    }

    @Test
    void aCommandThatIsNeverSentIsTriedExactlyItsAttemptBudgetThenFailsHonestlyAndIsReportedOnce() throws Exception {
        FakeBroker broker = new FakeBroker();
        broker.behaviour = (t, b) -> BrokerCommandPublisher.Attempt.notSent("CLIENT_NOT_CONNECTED");
        var listener = new FakeBroker.RecordingListener();
        var dispatcher = dispatcher(broker, listener, 2);
        UUID command = kit.enqueueDue(kit.device(), 0);
        await(() -> { dispatcher.runPass(); return "DEAD_LETTER".equals(kit.state(command)); }, "dead letter");
        for (int i = 0; i < 5; i++) { dispatcher.runPass(); dispatcher.sweepOnce(); }
        assertEquals(3, broker.calls.size(), "exactly the attempt budget, and a dead letter is never claimed or sent again");
        assertEquals(1, broker.calls.stream().map(FakeBroker.Call::text).distinct().count(), "every attempt carried the identical bytes");
        assertEquals("RETRY_EXHAUSTED", kit.terminalReason(command));
        assertEquals("FAILURE", kit.actionStatus(command));
        assertEquals(3, kit.attempts(command));
        assertEquals(List.of("ATTEMPT_STARTED", "ATTEMPT_FAILED_TRANSIENT", "ATTEMPT_STARTED", "ATTEMPT_FAILED_TRANSIENT", "ATTEMPT_STARTED", "DEAD_LETTERED"),
                kit.facts(command));
        await(() -> listener.deadLettered.size() == 1, "reaction");
        assertEquals(1, listener.deadLettered.size(), "the failure is reported exactly once, at the terminal outcome and not per attempt");
        assertTrue(listener.published.isEmpty());
    }

    @Test
    void anUncertainPublishIsDeadLetteredAsUnconfirmedAndNotResent() throws Exception {
        FakeBroker broker = new FakeBroker();
        broker.behaviour = (t, b) -> BrokerCommandPublisher.Attempt.uncertain("PUBLISH_TIMEOUT");
        var dispatcher = dispatcher(broker, new FakeBroker.RecordingListener(), 1);
        UUID command = kit.enqueueDue(kit.device(), 0);
        dispatcher.runPass();
        await(() -> "DEAD_LETTER".equals(kit.state(command)), "dead letter");
        assertEquals("UNCERTAIN_EXPIRED", kit.terminalReason(command));
        assertTrue(kit.actionMessage(command).startsWith("DELIVERY_UNCONFIRMED"));
        dispatcher.runPass();
        assertEquals(1, broker.calls.size());
    }

    @Test
    void aCrashingWorkerLeavesTheRowInFlightWhichIsNeverReclaimedAndLeaseRecoveryResolvesIt() throws Exception {
        FakeBroker broker = new FakeBroker();
        broker.behaviour = (t, b) -> { throw new IllegalStateException("worker crashed"); };
        var dispatcher = dispatcher(broker, new FakeBroker.RecordingListener(), 1);
        UUID command = kit.enqueueDue(kit.device(), 0);
        dispatcher.runPass();
        await(() -> broker.calls.size() == 1, "the single attempt");
        Thread.sleep(300);
        assertEquals("IN_FLIGHT", kit.state(command), "no outcome was written");
        for (int i = 0; i < 4; i++) dispatcher.runPass();
        assertEquals(1, broker.calls.size(), "an IN_FLIGHT row is not claimed again, so a crash cannot repeat the publication");
        assertEquals("ACCEPTED_FOR_DISPATCH", kit.actionStatus(command), "never silently reported as published");
        kit.expireLease(command);
        assertEquals(1, dispatcher.sweepOnce());
        assertEquals("DEAD_LETTER", kit.state(command));
        assertEquals("UNCERTAIN_EXPIRED", kit.terminalReason(command));
        assertEquals("FAILURE", kit.actionStatus(command));
        assertEquals(1, broker.calls.size());
    }

    @Test
    void aPayloadWhoseHashNoLongerMatchesIsNeverPublished() {
        FakeBroker broker = new FakeBroker();
        var dispatcher = dispatcher(broker, new FakeBroker.RecordingListener(), 1);
        UUID command = kit.enqueueDue(kit.device(), 0);
        Claimed real = kit.ops.claim(command, "w", Duration.ofSeconds(3)).orElseThrow();
        String tamperedHash = "0".repeat(64);
        Claimed tampered = new Claimed(real.commandId(), real.deviceId(), real.topic(),
                new OutboxPayload(real.payload().text(), tamperedHash, real.payload().epochSeconds()), real.attempt(), real.maxAttempts(),
                real.leaseOwner(), real.correlationId());
        dispatcher.deliver(tampered);
        assertTrue(broker.calls.isEmpty(), "integrity is verified before every attempt");
        assertEquals("DEAD_LETTER", kit.state(command));
        assertEquals("PAYLOAD_INTEGRITY", kit.terminalReason(command));
    }

    @Test
    void workersAreBoundedAndAClaimIsNeverLeasedWithoutAFreeWorker() throws Exception {
        FakeBroker broker = new FakeBroker();
        CountDownLatch release = new CountDownLatch(1);
        broker.behaviour = (t, b) -> {
            try { release.await(15, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return BrokerCommandPublisher.Attempt.confirmed();
        };
        var dispatcher = dispatcher(broker, new FakeBroker.RecordingListener(), 1);
        UUID a = kit.enqueueDue(kit.device(), 0), b = kit.enqueueDue(kit.device(), 1), c = kit.enqueueDue(kit.device(), 2);
        assertEquals(1, dispatcher.runPass(), "one worker, one claim");
        assertEquals(0, dispatcher.runPass(), "no free worker: nothing else is leased");
        assertEquals(1, kit.jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE state = 'IN_FLIGHT'", Integer.class));
        assertEquals(2, kit.jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE state = 'QUEUED'", Integer.class));
        release.countDown();
        await(() -> "PUBLISHED".equals(kit.state(a)), "first");
        for (int i = 0; i < 60 && !("PUBLISHED".equals(kit.state(b)) && "PUBLISHED".equals(kit.state(c))); i++) { dispatcher.runPass(); Thread.sleep(50); }
        assertEquals("PUBLISHED", kit.state(b));
        assertEquals("PUBLISHED", kit.state(c));
    }

    @Test
    void anExpiredCommandIsNeverPublishedAndTheNewerOneIsReleased() throws Exception {
        FakeBroker broker = new FakeBroker();
        var listener = new FakeBroker.RecordingListener();
        var dispatcher = dispatcher(broker, listener, 2);
        UUID device = kit.device();
        UUID expired = kit.enqueue(device, Instant.now().minusSeconds(60));
        UUID fresh = kit.enqueueDue(device, 0);
        dispatcher.runPass();
        await(() -> "PUBLISHED".equals(kit.state(fresh)), "the fresh command");
        assertEquals("EXPIRED", kit.terminalReason(expired));
        assertEquals(1, broker.calls.size());
        assertEquals(0, broker.callsFor(expired.toString()), "the expired command was never sent");
        await(() -> listener.deadLettered.size() == 1, "the expiry reaction");
    }

    @Test
    void aStaleWorkersLateResultIsFencedAndTriggersNoReaction() throws Exception {
        FakeBroker broker = new FakeBroker();
        CountDownLatch inPublish = new CountDownLatch(1), release = new CountDownLatch(1);
        broker.behaviour = (t, b) -> {
            inPublish.countDown();
            try { release.await(15, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return BrokerCommandPublisher.Attempt.confirmed();
        };
        var listener = new FakeBroker.RecordingListener();
        var dispatcher = dispatcher(broker, listener, 1);
        UUID command = kit.enqueueDue(kit.device(), 0);
        dispatcher.runPass();
        assertTrue(inPublish.await(10, TimeUnit.SECONDS));
        kit.expireLease(command);
        assertEquals(1, dispatcher.sweepOnce(), "lease recovery resolves the stalled attempt as an uncertain dead letter");
        release.countDown();
        Thread.sleep(500);
        assertEquals("DEAD_LETTER", kit.state(command), "the stalled worker's late success does not overwrite the newer state");
        assertEquals("FAILURE", kit.actionStatus(command));
        assertTrue(listener.published.isEmpty(), "a fenced result produces no reaction");
        assertEquals(1, listener.deadLettered.size());
    }

    @Test
    void aFailingReactionNeverChangesTheCommittedOutcome() throws Exception {
        FakeBroker broker = new FakeBroker();
        var listener = new FakeBroker.RecordingListener();
        listener.failing = true;
        var dispatcher = dispatcher(broker, listener, 1);
        UUID command = kit.enqueueDue(kit.device(), 0);
        dispatcher.runPass();
        await(() -> listener.published.size() == 1, "reaction attempted");
        assertEquals("PUBLISHED", kit.state(command));
        assertEquals("SUCCESS", kit.actionStatus(command));
    }

    @Test
    void theAfterCommitNudgeTriggersTheNormalClaimPass() throws Exception {
        FakeBroker broker = new FakeBroker();
        var dispatcher = dispatcher(broker, new FakeBroker.RecordingListener(), 1);
        UUID command = kit.enqueueDue(kit.device(), 0);
        dispatcher.nudge();
        await(() -> "PUBLISHED".equals(kit.state(command)), "the nudged pass");
    }

    @Test
    void severalCommandsOfOneDeviceAreSentOneAtATimeInAcceptanceOrder() throws Exception {
        FakeBroker broker = new FakeBroker();
        broker.behaviour = (t, b) -> {
            try { Thread.sleep(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return BrokerCommandPublisher.Attempt.confirmed();
        };
        var dispatcher = dispatcher(broker, new FakeBroker.RecordingListener(), 4);
        UUID device = kit.device();
        List<UUID> order = List.of(kit.enqueueDue(device, 0), kit.enqueueDue(device, 1), kit.enqueueDue(device, 2), kit.enqueueDue(device, 3));
        for (int i = 0; i < 400 && !order.stream().allMatch(c -> "PUBLISHED".equals(kit.state(c))); i++) { dispatcher.runPass(); Thread.sleep(20); }
        assertTrue(order.stream().allMatch(c -> "PUBLISHED".equals(kit.state(c))));
        assertEquals(1, broker.maxConcurrencyOnAnyTopic(), "never two sends in flight for one device");
        List<String> sent = broker.calls.stream().map(FakeBroker.Call::text).toList();
        for (int i = 0; i < order.size(); i++) assertTrue(sent.get(i).contains(order.get(i).toString()), "send " + i + " is command " + i);
    }

    @Test
    void aPassNeverClaimsMoreThanTheConfiguredBatch() throws Exception {
        FakeBroker broker = new FakeBroker();
        CountDownLatch release = new CountDownLatch(1);
        broker.behaviour = (t, b) -> {
            try { release.await(20, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return BrokerCommandPublisher.Attempt.confirmed();
        };
        var dispatcher = new CommandOutboxDispatcher(kit.ops, broker, FakeBroker.settings(8, Duration.ofSeconds(1), Duration.ofSeconds(3), 3),
                FakeBroker.listenerProvider(), FakeBroker.noMeters());
        for (int i = 0; i < 8; i++) kit.enqueueDue(kit.device(), i);
        assertEquals(3, dispatcher.runPass(), "batch size 3 although eight workers are free");
        assertEquals(3, kit.jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE state = 'IN_FLIGHT'", Integer.class));
        release.countDown();
    }

    @Test
    void theNudgeOnlyTriggersTheNormalClaimAndCannotPublishAnythingNotEligible() throws Exception {
        FakeBroker broker = new FakeBroker();
        CountDownLatch release = new CountDownLatch(1);
        UUID device = kit.device();
        UUID older = kit.enqueueDue(device, 0), newer = kit.enqueueDue(device, 1);
        UUID expired = kit.enqueue(kit.device(), Instant.now().minusSeconds(60));
        broker.behaviour = (t, b) -> {
            try { release.await(20, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return BrokerCommandPublisher.Attempt.confirmed();
        };
        var dispatcher = dispatcher(broker, new FakeBroker.RecordingListener(), 4);
        for (int i = 0; i < 5; i++) { dispatcher.nudge(); Thread.sleep(150); }
        await(() -> "IN_FLIGHT".equals(kit.state(older)), "the eligible older command");
        assertEquals("QUEUED", kit.state(newer), "a nudge cannot publish a command that is not next in line");
        assertEquals(0, broker.callsFor(newer.toString()));
        assertEquals(0, broker.callsFor(expired.toString()), "a nudge cannot publish an expired command");
        assertEquals(1, broker.callsFor(older.toString()));
        release.countDown();
    }

    @Test
    void stoppingTheDispatcherDuringAnAttemptLeavesNoStrandedRow() throws Exception {
        FakeBroker broker = new FakeBroker();
        CountDownLatch inPublish = new CountDownLatch(1);
        broker.behaviour = (t, b) -> {
            inPublish.countDown();
            try { new CountDownLatch(1).await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return BrokerCommandPublisher.Attempt.uncertain("INTERRUPTED");
        };
        var dispatcher = dispatcher(broker, new FakeBroker.RecordingListener(), 1);
        UUID command = kit.enqueueDue(kit.device(), 0);
        dispatcher.runPass();
        assertTrue(inPublish.await(10, TimeUnit.SECONDS));
        dispatcher.shutdown();      // what Spring does on context close: interrupts the publishing worker
        Thread.sleep(500);
        // either the worker recorded its outcome before dying, or the row keeps a lease that expires and is recovered
        if ("IN_FLIGHT".equals(kit.state(command))) {
            kit.expireLease(command);
            var successor = dispatcher(new FakeBroker(), new FakeBroker.RecordingListener(), 1);   // the next application start
            assertEquals(1, successor.sweepOnce());
        }
        assertTrue(List.of("PUBLISHED", "DEAD_LETTER").contains(kit.state(command)), "no row is stranded: " + kit.state(command));
        assertEquals(1, broker.callsFor(command.toString()), "and nothing was published twice");
        assertTrue(kit.ops.candidates(10).isEmpty());
    }

    @Test
    void anInstanceStartedAfterARestartDrainsWhatADeadOneLeftEvenWithTheFlagOff() throws Exception {
        // a previous run (flag ON) left a queued command and a command whose worker crashed after claiming it
        UUID freshDevice = kit.device(), lostDevice = kit.device();
        UUID fresh = kit.enqueueDue(freshDevice, 0);
        UUID lost = kit.enqueueDue(lostDevice, 1);
        kit.ops.claim(lost, "dead-instance/1", Duration.ofSeconds(6)).orElseThrow();
        kit.expireLease(lost);
        // the application restarts with the flag OFF: the new instance still services the existing obligations
        FakeBroker broker = new FakeBroker();
        var restarted = new CommandOutboxDispatcher(kit.ops, broker, FakeBroker.settings(false, 2, Duration.ofSeconds(1), Duration.ofSeconds(3), 10),
                FakeBroker.listenerProvider(), FakeBroker.noMeters());
        restarted.runPass();
        await(() -> "PUBLISHED".equals(kit.state(fresh)) && "DEAD_LETTER".equals(kit.state(lost)), "the leftovers to be drained");
        assertEquals("UNCERTAIN_EXPIRED", kit.terminalReason(lost), "the lost attempt is recovered as uncertain, never re-sent");
        assertEquals(1, broker.calls.size());
        assertTrue(broker.calls.get(0).text().contains(fresh.toString()));
        assertFalse(kit.ops.hasWork(), "nothing is left stranded");
    }
}
