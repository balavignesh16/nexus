package com.nexus.action.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dispatcher with the shared publish gate against a real PostgreSQL and a scripted broker (contract R18; ledger P4-090): publications per
 * second never exceed the limit, even with two dispatchers; an exhausted budget does not busy-poll; retries, expiry, ACKs and broker outages
 * keep their P4.3 and P4.4 meaning. The limit is measured on the database clock, from the recorded confirmation instants of the PUBLISHED rows.
 */
@Testcontainers
@Timeout(120)
class CommandOutboxFlowDispatcherTest {

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

    @AfterEach
    void quiesce() throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (kit.jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE state = 'IN_FLIGHT'", Integer.class) > 0 && System.nanoTime() < end) Thread.sleep(25);
        Thread.sleep(150);
    }

    private static void await(BooleanSupplier condition, String what, int seconds) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(25);
        }
    }

    private static ObjectProvider<MeterRegistry> meters(MeterRegistry registry) {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("meters", registry);
        return bf.getBeanProvider(MeterRegistry.class);
    }

    private CommandOutboxDispatcher dispatcher(OutboxDeliveryOperations ops, FakeBroker broker, MeterRegistry registry) {
        return new CommandOutboxDispatcher(ops, broker, FakeBroker.settings(4, Duration.ofSeconds(1), Duration.ofSeconds(3)),
                FakeBroker.listenerProvider(new FakeBroker.RecordingListener()), registry == null ? FakeBroker.noMeters() : meters(registry));
    }

    /** Ends the permit holders a test left in flight (they stand for publications that never complete). */
    private void releaseBlockers() {
        kit.jdbc.update("UPDATE command_outbox SET state = 'DEAD_LETTER', lease_owner = NULL, lease_expires_at = NULL, terminal_at = now(), terminal_reason = 'EXPIRED',"
                + " last_error_code = 'TEST_RELEASE' WHERE state = 'IN_FLIGHT'");
    }

    /** An instant on the DATABASE clock (the container clock can differ from this JVM's), so expiry timing in a test is exact. */
    private Timestamp databaseNowPlus(String interval) {
        return kit.jdbc.queryForObject("SELECT clock_timestamp() + CAST(? AS interval)", Timestamp.class, interval);
    }

    private List<UUID> dueCommands(int n) {
        List<UUID> commands = new ArrayList<>();
        for (int i = 0; i < n; i++) commands.add(kit.enqueueDue(kit.device(), 0));
        return commands;
    }

    private int published() {
        return kit.jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE state = 'PUBLISHED'", Integer.class);
    }

    /** The most confirmed publications inside any window just under one second, from the database-clock instants. */
    private int maxConfirmationsInOneSecond() {
        return kit.jdbc.queryForObject("SELECT COALESCE(max(n), 0) FROM (SELECT count(*) OVER (ORDER BY terminal_at RANGE BETWEEN INTERVAL '0.999 seconds' PRECEDING AND CURRENT ROW) AS n"
                + " FROM command_outbox WHERE state = 'PUBLISHED') w", Integer.class);
    }

    @Test
    void aBurstIsSpreadOverTimeNeverExceedsTheRateAndEveryCommandIsPublishedExactlyOnce() throws Exception {
        FakeBroker broker = new FakeBroker();
        MeterRegistry registry = new SimpleMeterRegistry();
        var dispatcher = dispatcher(kit.opsWithRate(4), broker, registry);
        List<UUID> commands = dueCommands(14);
        long started = System.nanoTime();
        while (published() < commands.size()) {
            dispatcher.runPass();
            Thread.sleep(40);
            if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(30)) throw new AssertionError("not all published: " + published());
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue(maxConfirmationsInOneSecond() <= 4, "confirmed publications in a one-second window: " + maxConfirmationsInOneSecond());
        assertTrue(elapsedMillis >= 2000, "14 commands at 4 per second cannot finish in under 3 s; took " + elapsedMillis + " ms");
        assertEquals(14, broker.calls.size());
        for (UUID c : commands) assertEquals(1, broker.callsFor(c.toString()), "exactly one publication of " + c);
        assertTrue(registry.counter("nexus.command.outbox.dispatch", "outcome", "rate_limited").count() >= 1, "the deferral is visible");
    }

    @Test
    void twoDispatchersOfTwoInstancesShareOneLimit() throws Exception {
        FakeBroker brokerA = new FakeBroker(), brokerB = new FakeBroker();
        var a = dispatcher(kit.opsWithRate(4), brokerA, null);
        var b = dispatcher(kit.opsWithRate(4), brokerB, null);
        dueCommands(20);
        long started = System.nanoTime();
        Thread ta = new Thread(() -> loop(a)), tb = new Thread(() -> loop(b));
        ta.start();
        tb.start();
        await(() -> published() == 20, "all 20 commands to be published by the two dispatchers", 40);
        ta.interrupt();
        tb.interrupt();
        ta.join(5000);
        tb.join(5000);
        assertEquals(20, brokerA.calls.size() + brokerB.calls.size(), "each command was published exactly once across both instances");
        assertTrue(maxConfirmationsInOneSecond() <= 4, "the aggregate over both instances: " + maxConfirmationsInOneSecond());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 3500, "20 commands at 4 per second need about 5 s in total");
    }

    private void loop(CommandOutboxDispatcher dispatcher) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                dispatcher.runPass();
                Thread.sleep(30);
            }
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void anExhaustedBudgetDoesNotBusyPollTheDatabaseOrThePublisher() throws Exception {
        FakeBroker broker = new FakeBroker();
        MeterRegistry registry = new SimpleMeterRegistry();
        var dispatcher = dispatcher(kit.opsWithRate(1), broker, registry);
        UUID blocker = kit.enqueueDue(kit.device(), 0);
        kit.opsWithRate(1).tryClaim(blocker, "held", Duration.ofSeconds(30));          // one permit, held in flight for the whole test
        dueCommands(5);
        long started = System.nanoTime();
        for (int i = 0; i < 300; i++) dispatcher.runPass();                            // a very hot loop
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        double asked = registry.counter("nexus.command.outbox.dispatch", "outcome", "rate_limited").count();
        double skipped = registry.counter("nexus.command.outbox.dispatch", "outcome", "rate_limited_skipped").count();
        assertTrue(asked <= elapsedMillis / 50.0 + 2, "the gate is asked at most once per 50 ms deferral however hot the loop is: asked " + asked + " in " + elapsedMillis + " ms");
        assertTrue(skipped >= 200, "the other passes were skipped locally without a database round trip: " + skipped);
        assertEquals(300, asked + skipped, "every pass was either one gate question or a local skip");
        assertEquals(0, broker.calls.size(), "nothing was published without permission");
        assertEquals(5, kit.jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE state = 'QUEUED'", Integer.class), "the waiting commands stay queued");
        releaseBlockers();
    }

    @Test
    void aCommandThatExpiresWhileWaitingForPermissionIsDeadLetteredVisiblyAndNeverPublished() throws Exception {
        FakeBroker broker = new FakeBroker();
        MeterRegistry registry = new SimpleMeterRegistry();
        OutboxDeliveryOperations ops = kit.opsWithRate(1);
        var dispatcher = dispatcher(ops, broker, registry);
        ops.tryClaim(kit.enqueueDue(kit.device(), 0), "held", Duration.ofSeconds(30));
        UUID waiting = kit.enqueueDue(kit.device(), 0, Map.of("expires_at", databaseNowPlus("1 second")));
        dispatcher.runPass();
        assertEquals("QUEUED", kit.state(waiting));
        Thread.sleep(1400);
        dispatcher.sweepOnce();
        releaseBlockers();
        assertEquals("DEAD_LETTER", kit.state(waiting));
        assertEquals("EXPIRED", kit.terminalReason(waiting));
        assertEquals(0, kit.attempts(waiting));
        assertEquals(0, broker.callsFor(waiting.toString()));
        assertEquals("FAILURE", kit.actionStatus(waiting));
        assertEquals(1, registry.counter("nexus.command.flow.expired_waiting").count());
    }

    @Test
    void aRetryUnderTheLimitKeepsItsIdentityBytesAndAttemptBudget() throws Exception {
        FakeBroker broker = new FakeBroker();
        var dispatcher = dispatcher(kit.opsWithRate(2), broker, null);
        UUID command = kit.enqueueDue(kit.device(), 0);
        String stored = kit.jdbc.queryForObject("SELECT payload_text FROM command_outbox WHERE command_id = ?", String.class, command);
        java.util.concurrent.atomic.AtomicInteger sends = new java.util.concurrent.atomic.AtomicInteger();
        broker.behaviour = (topic, bytes) -> sends.incrementAndGet() == 1 ? BrokerCommandPublisher.Attempt.notSent("CLIENT_NOT_CONNECTED") : BrokerCommandPublisher.Attempt.confirmed();
        dueCommands(4);                                                                  // other work competing for the two permits per second
        long started = System.nanoTime();
        while (!"PUBLISHED".equals(kit.state(command))) {
            dispatcher.runPass();
            Thread.sleep(40);
            if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(30)) throw new AssertionError("retry never published, state " + kit.state(command));
        }
        assertEquals(2, kit.attempts(command), "one proven non-publication, then one publication: the permit wait added no attempt");
        assertEquals(2, broker.callsFor(command.toString()));
        assertTrue(broker.calls.stream().filter(c -> c.text().contains(command.toString())).allMatch(c -> c.text().equals(stored)), "both attempts carried the stored bytes verbatim");
        assertTrue(kit.facts(command).contains("ATTEMPT_FAILED_TRANSIENT"));
    }

    @Test
    void aCommandAcknowledgedWhileItWaitsForPermissionIsNeverPublished() throws Exception {
        FakeBroker broker = new FakeBroker();
        OutboxDeliveryOperations ops = kit.opsWithRate(1);
        var dispatcher = dispatcher(ops, broker, null);
        ops.tryClaim(kit.enqueueDue(kit.device(), 0), "held", Duration.ofSeconds(30));
        UUID waiting = kit.enqueueDue(kit.device(), 0);
        dispatcher.runPass();
        kit.jdbc.update("UPDATE action_history SET ack_status = 'ACCEPTED', ack_received_at = now() WHERE request_id = ?", waiting);   // the device acknowledged (a copy was delivered earlier)
        kit.jdbc.update("UPDATE command_outbox SET state = 'DEAD_LETTER', lease_owner = NULL, lease_expires_at = NULL, terminal_at = now(), terminal_reason = 'EXPIRED', last_error_code = 'TEST_RELEASE' WHERE state = 'IN_FLIGHT'");   // the permit holder ends
        Thread.sleep(1200);
        for (int i = 0; i < 5; i++) { dispatcher.runPass(); Thread.sleep(60); }
        assertEquals(0, broker.callsFor(waiting.toString()), "a permit became free but the acknowledged command is never claimed");
        assertEquals("DEAD_LETTER", kit.state(waiting), "the existing sweep resolves it as an unconfirmed delivery");
        assertEquals("UNCERTAIN_EXPIRED", kit.terminalReason(waiting));
    }

    @Test
    void aBrokerOutageConsumesNoPermitsAndRecoveryResumesWithinTheLimit() throws Exception {
        FakeBroker broker = new FakeBroker();
        var dispatcher = dispatcher(kit.opsWithRate(3), broker, null);
        long outageEnds = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500);
        broker.behaviour = (topic, bytes) -> System.nanoTime() < outageEnds ? BrokerCommandPublisher.Attempt.notSent("CLIENT_NOT_CONNECTED") : BrokerCommandPublisher.Attempt.confirmed();
        List<UUID> commands = dueCommands(6);
        long started = System.nanoTime();
        while (System.nanoTime() - started < TimeUnit.SECONDS.toNanos(20)) {
            dispatcher.runPass();
            Thread.sleep(40);
            boolean settled = commands.stream().allMatch(c -> List.of("PUBLISHED", "DEAD_LETTER").contains(kit.state(c)));
            if (settled) break;
        }
        for (UUID c : commands) assertTrue(List.of("PUBLISHED", "DEAD_LETTER").contains(kit.state(c)), "command " + c + " is " + kit.state(c));
        assertTrue(maxConfirmationsInOneSecond() <= 3, "after the outage the limit still holds: " + maxConfirmationsInOneSecond());
        for (UUID c : commands) assertTrue(kit.attempts(c) <= 3, "never more attempts than the stored budget");
        assertEquals(0, kit.jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE state = 'PUBLISHED' AND attempt_count = 0", Integer.class));
    }

    @Test
    void aDispatcherThatDiesWhileCommandsWaitForPermissionLosesNothingAndANewOneContinuesWithinTheSameLimit() throws Exception {
        FakeBroker brokerOne = new FakeBroker(), brokerTwo = new FakeBroker();
        var first = dispatcher(kit.opsWithRate(3), brokerOne, null);
        List<UUID> commands = dueCommands(9);
        first.runPass();
        first.runPass();                                    // some are published, the rest wait for permission
        await(() -> published() >= 1, "the first publications", 10);
        first.shutdown();                                   // the process dies (no pool survives)
        int publishedByFirst = brokerOne.calls.size();
        assertTrue(publishedByFirst >= 1 && publishedByFirst <= 3, "the first instance published at most the rate before it died: " + publishedByFirst);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        var second = dispatcher(kit.opsWithRate(3), brokerTwo, null);
        while (published() < commands.size() && System.nanoTime() < deadline) { second.runPass(); Thread.sleep(40); }
        assertEquals(9, published(), "every waiting command was still delivered by the new instance");
        for (UUID c : commands) assertEquals(1, brokerOne.callsFor(c.toString()) + brokerTwo.callsFor(c.toString()), "exactly one publication of " + c + " across both lives");
        assertTrue(maxConfirmationsInOneSecond() <= 3, "the limit held across the restart: " + maxConfirmationsInOneSecond());
    }

    @Test
    void aBusyDeviceDoesNotStarveOtherDevicesBecauseWorkIsClaimedOldestFirstWithOneInFlightPerDevice() throws Exception {
        FakeBroker broker = new FakeBroker();
        var dispatcher = dispatcher(kit.opsWithRate(2), broker, null);
        UUID busy = kit.device();
        List<UUID> busyCommands = new ArrayList<>();
        for (int i = 0; i < 5; i++) busyCommands.add(kit.enqueueDue(busy, i));
        List<UUID> others = new ArrayList<>();
        for (int i = 0; i < 3; i++) others.add(kit.enqueueDue(kit.device(), 10 + i));
        long started = System.nanoTime();
        while (published() < 8 && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(40)) { dispatcher.runPass(); Thread.sleep(40); }
        assertEquals(8, published());
        Instant lastBusy = kit.jdbc.queryForObject("SELECT terminal_at FROM command_outbox WHERE command_id = ?", Timestamp.class, busyCommands.get(4)).toInstant();
        for (UUID other : others) {
            Instant at = kit.jdbc.queryForObject("SELECT terminal_at FROM command_outbox WHERE command_id = ?", Timestamp.class, other).toInstant();
            assertTrue(at.isBefore(lastBusy), "the other device's command was published before the busy device's last one: it was not starved");
        }
        for (int i = 1; i < busyCommands.size(); i++) {
            Instant earlier = kit.jdbc.queryForObject("SELECT terminal_at FROM command_outbox WHERE command_id = ?", Timestamp.class, busyCommands.get(i - 1)).toInstant();
            Instant later = kit.jdbc.queryForObject("SELECT terminal_at FROM command_outbox WHERE command_id = ?", Timestamp.class, busyCommands.get(i)).toInstant();
            assertTrue(earlier.isBefore(later), "the busy device's own order is preserved");
        }
    }

    @Test
    void whenTheLimiterCannotDecideNothingIsPublishedAndTheCommandsStayQueued() throws Exception {
        FakeBroker broker = new FakeBroker();
        MeterRegistry registry = new SimpleMeterRegistry();
        OutboxDeliveryOperations ops = new OutboxDeliveryOperations(kit.jdbc, kit.transactions, kit.history, Clock.systemUTC(), Duration.ofSeconds(30));
        ops.setPublishGate(new CommandPublishGate(kit.jdbc, new CommandFlowSettings(100, 10, 20)) {
            @Override public Decision permit() { throw new DataAccessResourceFailureException("coordination unavailable"); }
        });
        var dispatcher = dispatcher(ops, broker, registry);
        List<UUID> commands = dueCommands(3);
        for (int i = 0; i < 5; i++) dispatcher.runPass();
        assertEquals(0, broker.calls.size(), "fail closed: no coordination, no publication");
        for (UUID c : commands) {
            assertEquals("QUEUED", kit.state(c));
            assertEquals(0, kit.attempts(c));
        }
        assertTrue(registry.counter("nexus.command.outbox.dispatch", "outcome", "claim_error").count() >= 1, "the failure is visible as a metric");
        assertFalse(commands.isEmpty());
    }
}
