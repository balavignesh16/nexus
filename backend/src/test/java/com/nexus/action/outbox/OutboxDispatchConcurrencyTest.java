package com.nexus.action.outbox;

import com.nexus.action.outbox.OutboxDeliveryOperations.AttemptResult;
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

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrency evidence on a real PostgreSQL: several workers and two "instances" (independent dispatchers and history
 * writers sharing one database, in-process locks play no part), per-device ordering and single in-flight, lease and
 * failure interleavings, the 23505 race as a normal outcome, and the absence of lock-order deadlocks between the
 * dispatcher, acceptance, the ACK handler and device removal.
 */
@Testcontainers
@Timeout(240)
class OutboxDispatchConcurrencyTest {

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
        Thread.sleep(400);   // some tests deliberately leave an IN_FLIGHT row; only in-progress transactions must be allowed to finish
    }

    private static void await(java.util.function.BooleanSupplier condition, String what, int seconds) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("timed out waiting for " + what);
            Thread.sleep(25);
        }
    }

    // ---------------------------------------------------------------------------------------------- claim races

    @Test
    void manyThreadsRacingForOneRowProduceExactlyOneClaim() throws Exception {
        UUID command = kit.enqueueDue(kit.device(), 0);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Future<Optional<Claimed>>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String owner = "racer-" + i;
            results.add(pool.submit(() -> { barrier.await(); return kit.ops.claim(command, owner, Duration.ofSeconds(6)); }));
        }
        int winners = 0;
        for (Future<Optional<Claimed>> f : results) if (f.get(30, TimeUnit.SECONDS).isPresent()) winners++;
        pool.shutdownNow();
        assertEquals(1, winners);
        assertEquals(1, kit.attempts(command), "the attempt number was incremented exactly once");
        assertEquals(List.of("ATTEMPT_STARTED"), kit.facts(command));
    }

    @Test
    void theSingleInFlightRaceOnTheUniqueIndexIsANormalOutcomeNotAnError() throws Exception {
        // Two commands of one device with an IDENTICAL created_at are not ordered against each other by the claim guard, so only
        // the V37 unique index can stop the second claim: SQLSTATE 23505 must surface as "lost the race", never as an exception.
        int lost = 0, won = 0;
        for (int round = 0; round < 15; round++) {
            UUID device = kit.device();
            Instant tied = Instant.now().minusSeconds(2).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            UUID a = kit.enqueue(device, tied), b = kit.enqueue(device, tied);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Optional<Claimed>> fa = pool.submit(() -> { barrier.await(); return kit.ops.claim(a, "wa", Duration.ofSeconds(6)); });
            Future<Optional<Claimed>> fb = pool.submit(() -> { barrier.await(); return kit.ops.claim(b, "wb", Duration.ofSeconds(6)); });
            int present = (fa.get(30, TimeUnit.SECONDS).isPresent() ? 1 : 0) + (fb.get(30, TimeUnit.SECONDS).isPresent() ? 1 : 0);
            pool.shutdownNow();
            assertEquals(1, present, "round " + round + ": exactly one of two same-device claims may win");
            assertEquals(1, kit.jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE device_id = ? AND state = 'IN_FLIGHT'", Integer.class, device));
            won += present;
            lost += 2 - present;
        }
        assertEquals(15, won);
        assertEquals(15, lost);
    }

    // ---------------------------------------------------------------------------- two instances, ordering, once

    @Test
    void twoInstancesPublishEveryCommandExactlyOnceSingleInFlightPerDeviceAndInAcceptanceOrder() throws Exception {
        FakeBroker broker = new FakeBroker();
        broker.behaviour = (t, b) -> {
            try { Thread.sleep(5 + (int) (Math.random() * 10)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return BrokerCommandPublisher.Attempt.confirmed();
        };
        try (OutboxTestKit second = new OutboxTestKit(db)) {
            CommandOutboxDispatcher one = new CommandOutboxDispatcher(kit.ops, broker, FakeBroker.settings(4, Duration.ofSeconds(1), Duration.ofSeconds(3)),
                    FakeBroker.listenerProvider(), FakeBroker.noMeters());
            CommandOutboxDispatcher two = new CommandOutboxDispatcher(second.ops, broker, FakeBroker.settings(4, Duration.ofSeconds(1), Duration.ofSeconds(3)),
                    FakeBroker.listenerProvider(), FakeBroker.noMeters());
            Map<UUID, List<UUID>> expectedOrder = new HashMap<>();
            int index = 0;
            for (int d = 0; d < 10; d++) {
                UUID device = kit.device();
                List<UUID> list = new ArrayList<>();
                for (int n = 0; n < 5; n++) list.add(kit.enqueueDue(device, index++));
                expectedOrder.put(device, list);
            }
            List<UUID> all = expectedOrder.values().stream().flatMap(List::stream).toList();
            ExecutorService drivers = Executors.newFixedThreadPool(4);
            for (int i = 0; i < 4; i++) {
                CommandOutboxDispatcher d = i % 2 == 0 ? one : two;
                drivers.submit(() -> {
                    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
                    while (System.nanoTime() < end && !all.stream().allMatch(c -> "PUBLISHED".equals(kit.state(c)))) {
                        d.runPass();
                        try { Thread.sleep(10); } catch (InterruptedException e) { return; }
                    }
                });
            }
            await(() -> all.stream().allMatch(c -> "PUBLISHED".equals(kit.state(c))), "all 50 commands published", 100);
            drivers.shutdownNow();

            assertEquals(50, broker.calls.size(), "every command was sent exactly once");
            for (UUID c : all) assertEquals(1, broker.callsFor(c.toString()), "command " + c + " sent once");
            assertEquals(1, broker.maxConcurrencyOnAnyTopic(), "never two commands of one device in flight");
            for (var e : expectedOrder.entrySet()) {
                List<String> sentToDevice = broker.calls.stream().filter(call -> call.topic().contains(e.getKey().toString())).map(FakeBroker.Call::text).toList();
                for (int i = 0; i < 5; i++)
                    assertTrue(sentToDevice.get(i).contains(e.getValue().get(i).toString()), "device " + e.getKey() + " send " + i + " is its command " + i);
            }
            for (UUID c : all) {
                assertEquals(1, kit.attempts(c));
                assertEquals(List.of(1, 2), kit.sequences(c), "gapless history even with two writers");
                assertEquals("SUCCESS", kit.actionStatus(c));
            }
        }
    }

    @Test
    void aStalledOlderAttemptNeverLetsANewerCommandOvertakeAndTheHoldThatFollowsItsLossIsEnforced() throws Exception {
        FakeBroker broker = new FakeBroker();
        CountDownLatch stalled = new CountDownLatch(1), release = new CountDownLatch(1);
        UUID device = kit.device();
        UUID c1 = kit.enqueueDue(device, 0), c2 = kit.enqueueDue(device, 1), c3 = kit.enqueueDue(device, 2);
        broker.behaviour = (topic, bytes) -> {
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            if (text.contains(c1.toString())) {
                stalled.countDown();
                try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return BrokerCommandPublisher.Attempt.confirmed();
        };
        CommandOutboxDispatcher dispatcher = new CommandOutboxDispatcher(kit.ops, broker, FakeBroker.settings(4, Duration.ofSeconds(1), Duration.ofSeconds(3)),
                FakeBroker.listenerProvider(), FakeBroker.noMeters());
        dispatcher.runPass();
        assertTrue(stalled.await(10, TimeUnit.SECONDS));
        // while c1 is IN_FLIGHT, even with its lease EXPIRED, nothing newer may be sent
        kit.expireLease(c1);
        for (int i = 0; i < 5; i++) { assertEquals(0, kit.ops.candidates(10).size()); }
        assertEquals(0, broker.callsFor(c2.toString()));
        assertEquals(0, broker.callsFor(c3.toString()));
        // recovery resolves c1 as UNCERTAIN: its message may still reach the device, so c2 and c3 stay held (never overtake it)
        assertEquals(1, dispatcher.sweepOnce());
        for (int i = 0; i < 20; i++) { dispatcher.runPass(); Thread.sleep(50); }
        assertEquals(1, broker.calls.size(), "only c1 was ever sent");
        assertEquals("QUEUED", kit.state(c2));
        assertEquals("QUEUED", kit.state(c3));
        // their own delivery windows end during the hold: they expire visibly and are never sent
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!("DEAD_LETTER".equals(kit.state(c2)) && "DEAD_LETTER".equals(kit.state(c3))) && System.nanoTime() < end) { dispatcher.sweepOnce(); Thread.sleep(200); }
        assertEquals("EXPIRED", kit.terminalReason(c2));
        assertEquals("EXPIRED", kit.terminalReason(c3));
        assertEquals(1, broker.calls.size(), "c2 and c3 were never sent");
        release.countDown();
        Thread.sleep(400);
        assertEquals("DEAD_LETTER", kit.state(c1), "the stalled worker's late completion is fenced");
        assertEquals("FAILURE", kit.actionStatus(c1));
    }

    // ------------------------------------------------------------------------------- history and isolation

    @Test
    void concurrentWritersOfOneCommandReceiveUniqueIncreasingGaplessSequenceNumbers() throws Exception {
        UUID command = kit.enqueueDue(kit.device(), 0);
        int threads = 8, perThread = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        var tx = new org.springframework.transaction.support.TransactionTemplate(kit.transactions);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                barrier.await();
                for (int i = 0; i < perThread; i++)
                    tx.executeWithoutResult(s -> kit.history.append(command, CommandStateHistory.Fact.ATTEMPT_UNCERTAIN, null, null, 0, "CONCURRENT",
                            CommandStateHistory.Source.DISPATCHER, null, Instant.now(), null));
                return null;
            }));
        }
        for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        pool.shutdownNow();
        List<Integer> sequences = kit.sequences(command);
        assertEquals(threads * perThread, sequences.size());
        for (int i = 0; i < sequences.size(); i++) assertEquals(i + 1, sequences.get(i), "gapless and unique at position " + i);
    }

    @Test
    void aSlowOrFailingCommandOfOneDeviceDoesNotDelayAnotherDevice() throws Exception {
        FakeBroker broker = new FakeBroker();
        CountDownLatch holdA = new CountDownLatch(1);
        UUID deviceA = kit.device(), deviceB = kit.device();
        UUID a = kit.enqueueDue(deviceA, 0), b = kit.enqueueDue(deviceB, 1);
        broker.behaviour = (topic, bytes) -> {
            if (topic.contains(deviceA.toString())) {
                try { holdA.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return BrokerCommandPublisher.Attempt.uncertain("PUBLISH_TIMEOUT");
            }
            return BrokerCommandPublisher.Attempt.confirmed();
        };
        CommandOutboxDispatcher dispatcher = new CommandOutboxDispatcher(kit.ops, broker, FakeBroker.settings(2, Duration.ofSeconds(1), Duration.ofSeconds(3)),
                FakeBroker.listenerProvider(), FakeBroker.noMeters());
        dispatcher.runPass();
        await(() -> "PUBLISHED".equals(kit.state(b)), "device B while device A is stuck", 20);
        assertEquals("IN_FLIGHT", kit.state(a), "device A is still stuck");
        holdA.countDown();
        await(() -> "DEAD_LETTER".equals(kit.state(a)), "device A to resolve", 20);
    }

    // -------------------------------------------------------------------------------------------- lock order

    @Test
    void dispatcherAcceptanceAckAndDeviceRemovalNeverDeadlock() throws Exception {
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger deadlocks = new AtomicInteger(), refused = new AtomicInteger(), completed = new AtomicInteger();
        UUID device = kit.device(), otherDevice = kit.device();
        // the device already has delivery evidence, so every removal attempt is refused from the first instant (otherwise the
        // very first attempt could legitimately delete a device that has no outbox row yet)
        UUID seed = kit.enqueueDue(device, 0);
        kit.ops.complete(kit.ops.claim(seed, "seed", Duration.ofSeconds(6)).orElseThrow(), AttemptResult.CONFIRMED, null);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        int iterations = 60;

        // 1) the dispatcher pattern: outbox row, then action_history row (claim then outcome), never the device row
        tasks.add(pool.submit(() -> { start.await(); for (int i = 0; i < iterations; i++) {
            try {
                UUID c = kit.enqueueDue(device, i);
                Optional<Claimed> claim = kit.ops.claim(c, "w" + i, Duration.ofSeconds(6));
                if (claim.isPresent()) { kit.ops.complete(claim.get(), AttemptResult.CONFIRMED, null); completed.incrementAndGet(); }
            } catch (Throwable t) { record(t, failures, deadlocks); }
        } return null; }));
        // 2) the acceptance pattern: device row FOR UPDATE, then action_history insert/update, then the outbox row
        tasks.add(pool.submit(() -> { start.await(); for (int i = 0; i < iterations; i++) {
            try (Connection c = kit.dataSource.getConnection()) {
                c.setAutoCommit(false);
                try (var s = c.createStatement()) {
                    s.execute("SELECT id FROM devices WHERE id = '" + device + "' FOR UPDATE");
                    UUID command = OutboxSql.action(c, device, "ACCEPTED_FOR_DISPATCH", Instant.now().minusSeconds(2));
                    OutboxSql.insert(c, OutboxSql.queued(command, otherDevice, Instant.now().minusSeconds(2).plusMillis(i)));
                    c.commit();
                } catch (SQLException e) { c.rollback(); throw e; }
            } catch (Throwable t) { record(t, failures, deadlocks); }
        } return null; }));
        // 3) the ACK pattern: device row FOR UPDATE, then action_history update
        tasks.add(pool.submit(() -> { start.await(); for (int i = 0; i < iterations; i++) {
            try (Connection c = kit.dataSource.getConnection()) {
                c.setAutoCommit(false);
                try (var s = c.createStatement()) {
                    s.execute("SELECT id FROM devices WHERE id = '" + device + "' FOR UPDATE");
                    s.executeUpdate("UPDATE action_history SET ack_status = 'ACCEPTED', ack_received_at = now() WHERE device_id = '" + device + "' AND ack_status IS NULL AND request_id IN (SELECT request_id FROM action_history WHERE device_id = '" + device + "' LIMIT 3)");
                    c.commit();
                } catch (SQLException e) { c.rollback(); throw e; }
            } catch (Throwable t) { record(t, failures, deadlocks); }
        } return null; }));
        // 4) device removal attempts: always refused (fail closed), never a deadlock
        tasks.add(pool.submit(() -> { start.await(); for (int i = 0; i < iterations; i++) {
            try (Connection c = kit.dataSource.getConnection(); var s = c.createStatement()) {
                s.executeUpdate("DELETE FROM devices WHERE id = '" + device + "'");
                failures.add(new AssertionError("device deletion must be refused while delivery evidence exists"));
            } catch (SQLException e) {
                if (e.getMessage() != null && e.getMessage().contains("DEVICE_HAS_")) refused.incrementAndGet(); else record(e, failures, deadlocks);
            } catch (Throwable t) { record(t, failures, deadlocks); }
        } return null; }));
        // 5) the sweeps (expiry and lease recovery) interleaved with all of the above
        tasks.add(pool.submit(() -> { start.await(); for (int i = 0; i < iterations; i++) {
            try { kit.ops.expireDue(20); kit.ops.recoverExpiredLeases(20); } catch (Throwable t) { record(t, failures, deadlocks); }
        } return null; }));

        start.countDown();
        for (Future<?> f : tasks) f.get(150, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertEquals(0, deadlocks.get(), "no deadlock (SQLSTATE 40P01) between dispatcher, acceptance, ACK and device removal");
        assertTrue(failures.isEmpty(), "unexpected failures: " + failures);
        assertEquals(iterations, refused.get(), "every deletion attempt was refused");
        assertTrue(completed.get() > 0);
        assertFalse(kit.jdbc.queryForList("SELECT 1 FROM devices WHERE id = ?", device).isEmpty(), "the device still exists");
    }

    private static void record(Throwable t, List<Throwable> failures, AtomicInteger deadlocks) {
        for (Throwable c = t; c != null; c = c.getCause())
            if (c instanceof SQLException sql && "40P01".equals(sql.getSQLState())) { deadlocks.incrementAndGet(); return; }
        failures.add(t);
    }
}
