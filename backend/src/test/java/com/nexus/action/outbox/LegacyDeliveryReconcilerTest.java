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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime legacy reconciliation (contract R16, ledger P4-070, P4-071) on the real SQL, over populated legacy rows of every kind: a
 * stranded command is classified once and conservatively, anything with evidence, an outbox row, a fact, an ACK or a young age is left
 * alone, concurrent reconcilers and interrupted runs are safe, and the reconciler changes nothing but the history (it has no publisher).
 */
@Testcontainers
class LegacyDeliveryReconcilerTest {

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

    private static LegacyDeliveryReconciler reconciler(OutboxTestKit k, Duration grace) {
        return new LegacyDeliveryReconciler(k.jdbc, k.transactions, k.history, new DefaultListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class), grace, true);
    }

    private static LegacyDeliveryReconciler reconciler(OutboxTestKit k) {
        return reconciler(k, Duration.ofMinutes(5));
    }

    /** A legacy-path command row (SEND_COMMAND) {@code ageSeconds} old with the given status; no outbox row. */
    private UUID legacy(String status, long ageSeconds) {
        try (Connection c = kit.dataSource.getConnection()) {
            return OutboxSql.action(c, kit.device(), status, Instant.now().minusSeconds(ageSeconds));
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> facts(UUID command) {
        return kit.facts(command);
    }

    private String actionRow(UUID command) {
        return kit.jdbc.queryForObject("SELECT row_to_json(a)::text FROM action_history a WHERE request_id = ?", String.class, command);
    }

    @Test
    void aStrandedLegacyCommandIsMarkedDeliveryUnknownExactlyOnceAndNothingElseChanges() {
        UUID stranded = legacy("ACCEPTED_FOR_DISPATCH", 3600);
        String before = actionRow(stranded);
        var reconciler = reconciler(kit);
        assertEquals(1, reconciler.reconcile(10));
        assertEquals(0, reconciler.reconcile(10));
        assertEquals(0, reconciler.reconcile(10));
        assertEquals(List.of("DELIVERY_UNKNOWN"), facts(stranded));
        assertEquals("RECONCILER", kit.jdbc.queryForObject("SELECT source FROM command_state_history WHERE command_id = ?", String.class, stranded));
        assertEquals("LEGACY_DELIVERY_UNRECORDED", kit.jdbc.queryForObject("SELECT reason_code FROM command_state_history WHERE command_id = ?", String.class, stranded));
        assertEquals(before, actionRow(stranded), "ActionHistory is not touched: nothing is fabricated, nothing is resolved");
        assertEquals(0, kit.jdbc.queryForObject("SELECT count(*) FROM command_outbox", Integer.class), "no outbox row is created, so nothing can ever be published");
    }

    @Test
    void onlyAStrandedUnevidencedUnclaimedAndOldEnoughCommandIsEverClassified() {
        UUID young = legacy("ACCEPTED_FOR_DISPATCH", 60);                                // inside the grace: may be mid-dispatch
        UUID justInside = legacy("ACCEPTED_FOR_DISPATCH", 290);
        UUID published = legacy("SUCCESS", 3600);                                        // has a recorded outcome
        UUID failed = legacy("FAILURE", 3600);
        UUID acked = legacy("ACCEPTED_FOR_DISPATCH", 3600);
        kit.jdbc.update("UPDATE action_history SET ack_status = 'ACCEPTED', ack_received_at = now() WHERE request_id = ?", acked);
        UUID publishedAt = legacy("ACCEPTED_FOR_DISPATCH", 3600);                        // a recorded publication time
        kit.jdbc.update("UPDATE action_history SET published_at = now() WHERE request_id = ?", publishedAt);
        UUID hasOutbox = kit.enqueue(kit.device(), Instant.now().minusSeconds(3600));    // a flag-ON obligation: never touched, even if very old
        UUID alreadyKnown = legacy("ACCEPTED_FOR_DISPATCH", 3600);
        kit.jdbc.update("INSERT INTO command_state_history(command_id, sequence, fact_type, to_state, attempt, reason_code, source, occurred_at)"
                + " VALUES (?, 1, 'DELIVERY_UNKNOWN', 'DELIVERY_UNKNOWN', 0, 'LEGACY_DELIVERY_UNRECORDED', 'MIGRATION', now())", alreadyKnown);
        UUID stranded = legacy("ACCEPTED_FOR_DISPATCH", 3600);

        assertEquals(1, reconciler(kit).reconcile(100), "exactly the one stranded command");
        for (UUID untouched : List.of(young, justInside, published, failed, acked, publishedAt, hasOutbox))
            assertTrue(facts(untouched).isEmpty() || untouched.equals(hasOutbox) && facts(untouched).stream().noneMatch(f -> f.equals("DELIVERY_UNKNOWN")),
                    "left alone: " + untouched);
        assertEquals(List.of("DELIVERY_UNKNOWN"), facts(alreadyKnown), "an existing fact is never duplicated");
        assertEquals(List.of("DELIVERY_UNKNOWN"), facts(stranded));
        assertEquals("MIGRATION", kit.jdbc.queryForObject("SELECT source FROM command_state_history WHERE command_id = ?", String.class, alreadyKnown), "and never rewritten");
    }

    @Test
    void aCommandInsideTheGraceIsNotFalselyMarkedAndIsMarkedOnceItsAgeExceedsIt() {
        UUID command = legacy("ACCEPTED_FOR_DISPATCH", 100);
        assertEquals(0, reconciler(kit).reconcile(10));
        assertTrue(facts(command).isEmpty());
        // a shorter configured grace is refused outright: it has to exceed any legitimate legacy dispatch
        assertThrows(IllegalStateException.class, () -> reconciler(kit, Duration.ofMinutes(4)));
        assertThrows(IllegalStateException.class, () -> reconciler(kit, Duration.ofSeconds(30)));
        // age moves on the database clock only: a command back-dated by the test is then eligible
        kit.jdbc.update("UPDATE action_history SET \"timestamp\" = now() - INTERVAL '10 minutes' WHERE request_id = ?", command);
        assertEquals(1, reconciler(kit).reconcile(10));
    }

    @Test
    void aCommandThatCompletedOrWasAcknowledgedMeanwhileIsSkippedBecauseEveryConditionIsRecheckedUnderTheRowLock() throws Exception {
        UUID command = legacy("ACCEPTED_FOR_DISPATCH", 3600);
        try (Connection other = kit.dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (var st = other.createStatement()) {
                st.execute("SELECT 1 FROM action_history WHERE request_id = '" + command + "' FOR UPDATE");
                ExecutorService pool = Executors.newSingleThreadExecutor();
                try {
                    Future<Integer> run = pool.submit(() -> reconciler(kit).reconcile(10));
                    Thread.sleep(700);                                                   // the reconciler selected it and now waits for the row lock
                    st.execute("UPDATE action_history SET status = 'SUCCESS', published_at = now() WHERE request_id = '" + command + "'");
                    other.commit();                                                      // the (slow legacy) publish completed first
                    assertEquals(0, run.get(), "the recheck under the lock sees the publication: nothing is classified");
                } finally {
                    pool.shutdownNow();
                }
            }
        }
        assertTrue(facts(command).isEmpty());
    }

    @Test
    @Timeout(60)
    void concurrentReconcilersMarkEachStrandedCommandExactlyOnce() throws Exception {
        List<UUID> commands = new ArrayList<>();
        for (int i = 0; i < 10; i++) commands.add(legacy("ACCEPTED_FOR_DISPATCH", 3600));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<OutboxTestKit> kits = new ArrayList<>();
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                OutboxTestKit k = new OutboxTestKit(db);
                kits.add(k);
                results.add(pool.submit(() -> { go.await(); return reconciler(k).reconcile(100); }));
            }
            go.countDown();
            int total = 0;
            for (var f : results) total += f.get();
            assertEquals(10, total);
        } finally {
            pool.shutdownNow();
            kits.forEach(OutboxTestKit::close);
        }
        for (UUID c : commands) assertEquals(List.of("DELIVERY_UNKNOWN"), facts(c));
    }

    @Test
    void anInterruptedRunIsRepeatedWithoutDuplicatesAndABatchIsBounded() {
        List<UUID> commands = new ArrayList<>();
        for (int i = 0; i < 5; i++) commands.add(legacy("ACCEPTED_FOR_DISPATCH", 3600 + i));
        assertEquals(2, reconciler(kit).reconcile(2));                                  // the first run stops after one bounded batch (an interruption)
        try (OutboxTestKit restarted = new OutboxTestKit(db)) {                        // a fresh process resumes
            var again = reconciler(restarted);
            assertEquals(2, again.reconcile(2));
            assertEquals(1, again.reconcile(2));
            assertEquals(0, again.reconcile(2));
        }
        for (UUID c : commands) assertEquals(List.of("DELIVERY_UNKNOWN"), facts(c));
    }

    @Test
    void aDeviceThatReceivedTheCommandMayStillAcknowledgeAndTheFactsStayTruthful() {
        UUID command = legacy("ACCEPTED_FOR_DISPATCH", 3600);
        assertEquals(1, reconciler(kit).reconcile(10));
        // the late ACK itself is recorded by the ACK handler (CommandAckFactsIntegrationTest); here the reconciler must not undo or redo anything
        kit.jdbc.update("UPDATE action_history SET ack_status = 'ACCEPTED', ack_received_at = now() WHERE request_id = ?", command);
        assertEquals(0, reconciler(kit).reconcile(10));
        assertEquals(List.of("DELIVERY_UNKNOWN"), facts(command));
        assertEquals(0, kit.jdbc.queryForObject("SELECT count(*) FROM command_outbox", Integer.class));
    }
}
