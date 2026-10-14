package com.nexus.rule.trigger;

import com.nexus.rule.domain.*;
import com.nexus.rule.timer.RuleTimerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M1: one global row-lock order (rules row -> timer row -> temporal state row) for lifecycle changes, timer claims and temporal
 * reconciliation. The test parks a lifecycle transaction that holds the rules row, starts the due worker, and proves with a third
 * connection that the worker is queued on the rules row WITHOUT holding the timer row. With the previous order (timer first) the
 * worker held the timer and waited for the rules row while the lifecycle transaction waited for the timer: a deadlock.
 */
class RuleTimerLifecycleLockOrderIntegrationTest extends TypedTriggerSupport {
    @Autowired RuleTimerService timers;
    @Autowired PlatformTransactionManager transactions;

    @Test void aPauseHoldingTheRuleRowAndADueWorkerNeverDeadlockAndTheWorkerHoldsNoTimerLockWhileWaiting() throws Exception {
        UUID device = device();
        Instant at = now.plusSeconds(10);
        UUID rule = typedRule(device, "pause versus due", 1, List.of(new TriggerSubscription(TriggerType.SCHEDULE)), List.of(), List.of(alert()), null,
            new ScheduleSpec(ScheduleSpec.Kind.ONE_SHOT, at, null, null));
        UUID timer = sql.queryForObject("SELECT timer_id FROM rule_timers WHERE rule_id=? AND status='PENDING'", UUID.class, rule);
        now = at;                                                                 // the occurrence is due

        var pool = Executors.newFixedThreadPool(2);
        var holding = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        // The lifecycle side exactly as RuleLifecycleService orders it: lock the rules row, change ownership, reconcile timers.
        Future<?> lifecycle = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
            sql.queryForList("SELECT rule_id FROM rules WHERE rule_id=? FOR UPDATE", rule);
            holding.countDown();
            try { assertTrue(proceed.await(60, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new IllegalStateException(e); }
            sql.update("UPDATE rules SET lifecycle='PAUSED',enabled=false,execution_epoch=execution_epoch+1,row_revision=row_revision+1 WHERE rule_id=?", rule);
            timers.reconcile(rule);
            return null;
        }));
        assertTrue(holding.await(30, TimeUnit.SECONDS));
        Future<Integer> worker = pool.submit(() -> timers.processDue(5));

        long deadline = System.currentTimeMillis() + 20_000;
        boolean queued = false;
        while (System.currentTimeMillis() < deadline && !queued) {
            queued = count("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE '%FROM rules WHERE rule_id=%FOR SHARE%'") > 0;
            if (!queued) Thread.sleep(50);
        }
        assertTrue(queued, "the due worker is waiting on the rules row");
        try (var third = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword())) {
            third.setAutoCommit(false);
            try (var st = third.createStatement()) {
                st.execute("SET lock_timeout='1s'");
                // NOWAIT succeeds only if nobody holds the timer row: the worker acquired the rules row first and holds no timer lock yet.
                var rows = st.executeQuery("SELECT timer_id FROM rule_timers WHERE timer_id='" + timer + "' FOR UPDATE NOWAIT");
                assertTrue(rows.next());
            } catch (SQLException heldByWorker) {
                fail("The worker holds the timer row while waiting for the rules row (lock-order inversion): " + heldByWorker.getMessage());
            } finally { third.rollback(); }
        }

        proceed.countDown();
        lifecycle.get(60, TimeUnit.SECONDS);                                      // no deadlock victim on either side
        worker.get(60, TimeUnit.SECONDS);
        pool.shutdown();
        assertEquals("INVALIDATED", sql.queryForObject("SELECT status FROM rule_timers WHERE timer_id=?", String.class, timer));
        assertEquals("RULE_NOT_ACTIVE", sql.queryForObject("SELECT invalidation_reason FROM rule_timers WHERE timer_id=?", String.class, timer));
        assertEquals(0, matches(rule), "the paused rule's occurrence never decides");
        assertEquals(0, count("SELECT count(*) FROM rule_trigger_facts WHERE trigger_type='SCHEDULE' AND attributes->>'ruleId'=?", rule.toString()));
    }
}
