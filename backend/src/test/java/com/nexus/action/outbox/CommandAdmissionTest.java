package com.nexus.action.outbox;

import com.nexus.action.outbox.OutboxDeliveryOperations.Claimed;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bounded admission on the real SQL with real, independent connections (contract R18; ledger P4-087, P4-088, P4-091). The acceptance
 * transaction is reproduced exactly where it matters: device row lock, then admission, then the writes, all in one transaction. The cap
 * checks are one-below, exactly-at and one-above at the boundary, every unresolved state occupies a slot, a terminal state releases it, and
 * the race tests use deterministic barriers (a transaction that holds the lock between its check and its insert) instead of sleeps alone.
 */
@Testcontainers
@Timeout(120)
class CommandAdmissionTest {

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

    private static CommandAdmission admission(int globalCap, int deviceCap) {
        return new CommandAdmission(kit.jdbc, new CommandFlowSettings(globalCap, deviceCap, 20), (io.micrometer.core.instrument.MeterRegistry) null);
    }

    /** One acceptance as T1 does it: lock the device row, admit, then write the action and its obligation, all in one transaction. */
    private UUID accept(CommandAdmission admission, UUID device, Runnable betweenAdmitAndWrite) {
        UUID command = UUID.randomUUID();
        new TransactionTemplate(kit.transactions).executeWithoutResult(status -> {
            kit.jdbc.queryForList("SELECT id FROM devices WHERE id = ? FOR NO KEY UPDATE", device);
            admission.admit(device, false);
            betweenAdmitAndWrite.run();
            Instant createdAt = Instant.now();
            kit.jdbc.update("INSERT INTO action_history(request_id,match_id,device_id,action_type,status,parameters,timestamp,message)"
                    + " VALUES (?,?,?,'SEND_COMMAND','ACCEPTED_FOR_DISPATCH','{}'::jsonb,?,'admitted')", command, UUID.randomUUID(), device, Timestamp.from(createdAt));
            Map<String, Object> row = OutboxSql.queued(command, device, createdAt);
            kit.jdbc.update("INSERT INTO command_outbox(" + String.join(",", row.keySet()) + ") VALUES (" + "?,".repeat(row.size() - 1) + "?)", row.values().toArray());
        });
        return command;
    }

    private UUID accept(CommandAdmission admission, UUID device) {
        return accept(admission, device, () -> { });
    }

    private long unresolved() {
        return kit.jdbc.queryForObject("SELECT COUNT(*) FROM command_outbox WHERE state IN ('QUEUED','IN_FLIGHT','RETRY_WAIT')", Long.class);
    }

    private long actionRows() {
        return kit.jdbc.queryForObject("SELECT COUNT(*) FROM action_history", Long.class);
    }

    // ------------------------------------------------------------------------------------------------- boundaries

    @Test
    void theDeviceCapIsExactOneBelowAtAndOneAbove() {
        CommandAdmission admission = admission(100, 3);
        UUID device = kit.device();
        accept(admission, device);
        accept(admission, device);                       // 2 of 3: one below the cap, still admitted
        accept(admission, device);                       // 3 of 3: exactly at the cap after this one
        assertEquals(3, unresolved());
        CommandQueueFullException refused = assertThrows(CommandQueueFullException.class, () -> accept(admission, device));   // one above
        assertEquals(CommandQueueFullException.Scope.DEVICE, refused.scope());
        assertEquals(3, refused.limit());
        assertEquals(CommandQueueFullException.CODE, refused.code());
        assertEquals(3, unresolved(), "the refused command left no obligation");
        assertEquals(3, actionRows(), "and no ActionHistory row: nothing suggests it was accepted");
    }

    @Test
    void aFullDeviceNeverBlocksAnotherDevice() {
        CommandAdmission admission = admission(100, 2);
        UUID busy = kit.device(), other = kit.device();
        accept(admission, busy);
        accept(admission, busy);
        assertThrows(CommandQueueFullException.class, () -> accept(admission, busy));
        accept(admission, other);
        accept(admission, other);
        assertThrows(CommandQueueFullException.class, () -> accept(admission, other));
        assertEquals(4, unresolved());
    }

    @Test
    void theGlobalCapIsExactAcrossDevicesAndOneDeviceCannotOccupyItAll() {
        CommandAdmission admission = admission(4, 2);
        UUID a = kit.device(), b = kit.device(), c = kit.device();
        accept(admission, a);
        accept(admission, a);
        accept(admission, b);                            // 3 of 4: one below the global cap
        accept(admission, b);                            // 4 of 4: at the cap
        CommandQueueFullException refused = assertThrows(CommandQueueFullException.class, () -> accept(admission, c));   // one above
        assertEquals(CommandQueueFullException.Scope.GLOBAL, refused.scope());
        assertEquals(4, refused.limit());
        assertEquals(4, unresolved());
        assertThrows(CommandQueueFullException.class, () -> accept(admission, a), "a device at its own cap is refused first, as DEVICE or GLOBAL, never admitted");
    }

    @Test
    void everyUnresolvedStateOccupiesASlotAndTerminalStatesReleaseIt() {
        CommandAdmission admission = admission(100, 3);
        UUID device = kit.device();
        UUID queued = accept(admission, device);
        UUID retrying = accept(admission, device);
        UUID inFlight = accept(admission, device);
        assertThrows(CommandQueueFullException.class, () -> accept(admission, device));

        // QUEUED -> IN_FLIGHT (a claim) keeps the slot; IN_FLIGHT -> RETRY_WAIT (not sent) keeps it as well: a retry is the same obligation
        Claimed claimed = kit.ops.claim(queued, "w1", Duration.ofSeconds(6)).orElseThrow();
        assertThrows(CommandQueueFullException.class, () -> accept(admission, device), "IN_FLIGHT occupies a slot");
        kit.ops.complete(claimed, OutboxDeliveryOperations.AttemptResult.NOT_SENT, "CLIENT_NOT_CONNECTED");
        assertEquals("RETRY_WAIT", kit.state(queued));
        assertThrows(CommandQueueFullException.class, () -> accept(admission, device), "RETRY_WAIT occupies a slot, and is still ONE slot");
        assertEquals(3, unresolved());

        // a terminal outcome releases the slot at once
        kit.jdbc.update("UPDATE command_outbox SET state = 'DEAD_LETTER', next_attempt_at = NULL, terminal_at = now(), terminal_reason = 'EXPIRED' WHERE command_id = ?", queued);
        accept(admission, device);
        assertThrows(CommandQueueFullException.class, () -> accept(admission, device));
        kit.jdbc.update("UPDATE command_outbox SET state = 'DEAD_LETTER', next_attempt_at = NULL, terminal_at = now(), terminal_reason = 'EXPIRED' WHERE command_id = ?", retrying);
        kit.jdbc.update("UPDATE command_outbox SET state = 'DEAD_LETTER', next_attempt_at = NULL, terminal_at = now(), terminal_reason = 'EXPIRED' WHERE command_id = ?", inFlight);
        accept(admission, device);
        accept(admission, device);
        assertEquals(3, unresolved(), "published or dead-lettered obligations never count against the cap");
    }

    @Test
    void aRestartedInstanceSeesTheSameCapacityBecauseItIsCountedFromDurableRows() {
        UUID device = kit.device();
        CommandAdmission before = admission(100, 2);
        accept(before, device);
        accept(before, device);
        CommandAdmission afterRestart = admission(100, 2);   // a new object over the same database
        assertThrows(CommandQueueFullException.class, () -> accept(afterRestart, device));
        assertEquals(2, afterRestart.unresolvedCount());
    }

    // ----------------------------------------------------------------------------------------------------- races

    @Test
    void concurrentAcceptancesForManyDevicesNeverExceedTheGlobalCap() throws Exception {
        CommandAdmission admission = admission(5, 5);
        int threads = 16;
        List<UUID> devices = new ArrayList<>();
        for (int i = 0; i < threads; i++) devices.add(kit.device());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (UUID device : devices) {
            results.add(pool.submit((Callable<Boolean>) () -> {
                go.await();
                try { accept(admission, device); return true; } catch (CommandQueueFullException full) { return false; }
            }));
        }
        go.countDown();
        int admitted = 0;
        for (Future<Boolean> f : results) if (f.get(60, TimeUnit.SECONDS)) admitted++;
        pool.shutdownNow();
        assertEquals(5, admitted, "exactly the cap, never more, never fewer");
        assertEquals(5, unresolved());
        assertEquals(5, actionRows());
    }

    @Test
    void concurrentAcceptancesForOneDeviceNeverExceedTheDeviceCap() throws Exception {
        CommandAdmission admission = admission(100, 3);
        UUID device = kit.device();
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit((Callable<Boolean>) () -> {
                go.await();
                try { accept(admission, device); return true; } catch (CommandQueueFullException full) { return false; }
            }));
        }
        go.countDown();
        int admitted = 0;
        for (Future<Boolean> f : results) if (f.get(60, TimeUnit.SECONDS)) admitted++;
        pool.shutdownNow();
        assertEquals(3, admitted);
        assertEquals(3, unresolved());
    }

    /**
     * The deterministic race for the LAST slot. Acceptance A passes the check (4 of 5) and is then held, between its check and its insert,
     * with its transaction open. Acceptance B, for another device, starts its check meanwhile. With the admission lock B cannot finish its
     * check until A commits and then sees 5 of 5 and is refused; without it B would also see 4 of 5 and both would be admitted (6 of 5).
     */
    @Test
    void anAcceptanceHeldBetweenItsCheckAndItsInsertMakesTheNextOneWaitInsteadOfOverAdmitting() throws Exception {
        CommandAdmission admission = admission(5, 5);
        for (int i = 0; i < 4; i++) accept(admission, kit.device());
        UUID deviceA = kit.device(), deviceB = kit.device();
        CountDownLatch aChecked = new CountDownLatch(1), releaseA = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<UUID> a = pool.submit(() -> accept(admission, deviceA, () -> {
            aChecked.countDown();
            try { releaseA.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }));
        assertTrue(aChecked.await(30, TimeUnit.SECONDS));
        Future<UUID> b = pool.submit(() -> accept(admission, deviceB));
        Thread.sleep(700);                                  // B has had ample time to finish its check if nothing made it wait
        assertFalse(b.isDone(), "B must be waiting for A's admission lock, not admitted on a stale count");
        releaseA.countDown();
        a.get(30, TimeUnit.SECONDS);
        java.util.concurrent.ExecutionException refused = assertThrows(java.util.concurrent.ExecutionException.class, () -> b.get(30, TimeUnit.SECONDS));
        assertTrue(refused.getCause() instanceof CommandQueueFullException, String.valueOf(refused.getCause()));
        assertEquals(CommandQueueFullException.Scope.GLOBAL, ((CommandQueueFullException) refused.getCause()).scope());
        pool.shutdownNow();
        assertEquals(5, unresolved(), "never 6 of 5");
    }

    @Test
    void theAdmissionLockIsReleasedWhenAnAcceptanceRollsBackSoNothingStaysBlocked() {
        CommandAdmission admission = admission(2, 2);
        UUID device = kit.device();
        assertThrows(IllegalStateException.class, () -> accept(admission, device, () -> { throw new IllegalStateException("acceptance failed after the check"); }));
        assertEquals(0, unresolved(), "a rolled-back acceptance leaves no obligation and no phantom slot");
        accept(admission, device);
        accept(admission, device);
        assertThrows(CommandQueueFullException.class, () -> accept(admission, device));
    }
}
