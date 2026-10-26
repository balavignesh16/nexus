package com.nexus.rule.dryrun;

import com.nexus.rule.domain.*;
import com.nexus.rule.trigger.TypedTriggerSupport;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B2: a dry-run needs exactly ONE database connection, and it is one REPEATABLE READ read-only transaction for the whole request. The pool is
 * deliberately tiny (two connections, a three second wait): under the original nested pattern (an outer read-only transaction holding a
 * connection while an inner REQUIRES_NEW snapshot asks for a second one) two simultaneous requests exhaust the pool and starve. The pool is NOT
 * enlarged to hide that.
 */
@SpringBootTest(properties = {"spring.datasource.hikari.maximum-pool-size=2", "spring.datasource.hikari.minimum-idle=2", "spring.datasource.hikari.connection-timeout=3000"})
class DryRunConnectionPoolIntegrationTest extends TypedTriggerSupport {
    @Autowired DryRunService service;
    @Autowired HikariDataSource pool;
    @Autowired PlatformTransactionManager transactions;
    private static final List<TriggerSubscription> TELEMETRY = List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY));

    private DryRunRequest current(UUID device, UUID rule) {
        return new DryRunRequest("CURRENT_STATE", new DryRunRequest.Scope(device), List.of(new DryRunRequest.VersionSelector(rule, null, null, null)),
            new DryRunRequest.TriggerSpec("ACCEPTED_TELEMETRY", device, null, null, null), null, null);
    }
    /** Diagnostic only (part of the failure message): which application threads are inside which of our classes. */
    private static String applicationThreads() {
        StringBuilder out = new StringBuilder();
        Thread.getAllStackTraces().forEach((thread, stack) -> {
            StringBuilder frames = new StringBuilder();
            for (StackTraceElement e : stack) if (e.getClassName().startsWith("com.nexus") && frames.length() < 700) frames.append(e.getClassName().substring(e.getClassName().lastIndexOf('.') + 1)).append('.').append(e.getMethodName()).append(' ');
            if (frames.length() > 0) out.append("\n  ").append(thread.getName()).append(": ").append(frames);
        });
        return out.toString();
    }
    /** A leaked connection never comes back; one held briefly by the fixture's asynchronous ingest does. */
    private void assertNoConnectionLeak() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (pool.getHikariPoolMXBean().getActiveConnections() != 0 && System.nanoTime() < deadline) Thread.sleep(50);
        assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections(), "every connection is returned; threads of this application that were running: " + applicationThreads());
        assertEquals(0, pool.getHikariPoolMXBean().getThreadsAwaitingConnection());
    }
    /**
     * With the outbox ON (P4-111) the fixture's own command is delivered asynchronously by the dispatcher, which changes the delivery rows and holds
     * a connection for a moment. The dry-run assertions are about what the DRY-RUN changes, so the fixture's delivery is allowed to settle first.
     * Nothing is weakened: with the outbox OFF there is nothing to wait for, and the fingerprint and leak assertions are unchanged.
     */
    private void awaitDeliveryQuiet() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline
                && sql.queryForObject("SELECT count(*) FROM command_outbox WHERE state IN ('QUEUED','IN_FLIGHT','RETRY_WAIT')", Integer.class) != 0) Thread.sleep(50);
    }
    private Map<String, String> fingerprint() {
        var out = new TreeMap<String, String>();
        for (String table : sql.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename", String.class))
            out.put(table, sql.queryForObject("SELECT count(*)::text || ':' || coalesce(md5(string_agg(t::text, '|' ORDER BY t::text)), '') FROM \"" + table + "\" t", String.class));
        return out;
    }

    @Test void simultaneousDryRunsOnATwoConnectionPoolCompleteWithoutStarvingAndLeaveNoConnectionBehind() throws Exception {
        UUID device = device(), target = device();
        UUID rule = typedRule(device, "pool", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(target, true)));
        ingest(device, 25, now);
        awaitDeliveryQuiet();
        var before = fingerprint();
        int callers = 8;
        var executor = Executors.newFixedThreadPool(callers);
        var start = new CountDownLatch(1);
        var results = new ArrayList<Future<DryRunResponse>>();
        for (int i = 0; i < callers; i++) results.add(executor.submit(() -> { start.await(); return service.run(current(device, rule)); }));
        start.countDown();
        int ok = 0;
        var failures = new ArrayList<String>();
        for (var f : results) {
            try { var r = f.get(60, TimeUnit.SECONDS); assertEquals("MATCHED", r.versions().get(0).outcome()); ok++; }
            catch (ExecutionException e) { failures.add(String.valueOf(e.getCause())); }
        }
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "no worker thread is left behind");
        assertEquals(callers, ok, "every simultaneous request completes on a two-connection pool: " + failures);
        assertNoConnectionLeak();
        assertEquals(before, fingerprint(), "concurrent dry-runs left every table unchanged");
    }

    @Test void failingAndRejectedRequestsReturnTheirConnectionAndLeaveNothingBehind() throws Exception {
        UUID device = device(), target = device();
        UUID rule = typedRule(device, "pool failure", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(target, true)));
        ingest(device, 25, now);
        awaitDeliveryQuiet();
        var before = fingerprint();
        var executor = Executors.newFixedThreadPool(6);
        var futures = new ArrayList<Future<String>>();
        for (int i = 0; i < 12; i++) {
            final int n = i;
            futures.add(executor.submit(() -> {
                try {
                    if (n % 3 == 0) service.run(current(device, UUID.randomUUID()));                                       // unknown rule: rejected after a read
                    else if (n % 3 == 1) service.run(new DryRunRequest("HISTORICAL_REPLAY", null, List.of(new DryRunRequest.VersionSelector(rule, null, null, null)),
                        new DryRunRequest.TriggerSpec("ACCEPTED_TELEMETRY", device, null, null, null), null, null));         // rejected before any read
                    else service.run(current(device, rule));
                    return "ok";
                } catch (DryRunRejection rejected) { return rejected.code(); }
            }));
        }
        var outcomes = new TreeSet<String>();
        for (var f : futures) outcomes.add(f.get(60, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(Set.of("ok", "VERSION_NOT_FOUND", "HISTORICAL_REPLAY_UNSUPPORTED"), outcomes);
        assertNoConnectionLeak();
        assertEquals(before, fingerprint());
    }

    @Test void aPoolWhollyHeldElsewhereMakesTheRequestFailCleanlyAndSideEffectFree() throws Exception {
        UUID device = device(), target = device();
        UUID rule = typedRule(device, "pool held", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(target, true)));
        ingest(device, 25, now);
        awaitDeliveryQuiet();
        var before = fingerprint();
        // Hold BOTH pool connections from outside, then ask for a dry-run: it times out (a timed-out request), and recovers afterwards.
        var holders = new ArrayList<java.sql.Connection>();
        try {
            holders.add(pool.getConnection()); holders.add(pool.getConnection());
            assertThrows(RuntimeException.class, () -> service.run(current(device, rule)));
        } finally { for (var c : holders) c.close(); }
        assertNoConnectionLeak();
        assertEquals("MATCHED", service.run(current(device, rule)).versions().get(0).outcome(), "the service recovers as soon as connections are free");
        assertEquals(before, fingerprint());
    }

    @Test void concurrentCommittedChangesNeverProduceAMixedSnapshot() throws Exception {
        // A writer atomically flips (Twin value, connectivity, cooldown row) between two committed states. Every dry-run must show ONE of them,
        // never a value from one state with the connectivity or cooldown of the other.
        UUID device = device(), target = device();
        UUID rule = UUID.randomUUID();
        lifecycle.legacySave(new Rule(rule, "coherent", true, 1, List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()), temperatureAbove("10")),
            List.of(ledCommand(target, true)), Map.of(), 600, null, new RuleRuntime(TELEMETRY, RepeatPolicy.EDGE_TRUE, null)), null);
        ingest(device, 25, now);
        awaitDeliveryQuiet();
        UUID matchId = sql.queryForObject("SELECT match_id FROM rule_matches WHERE rule_id=?", UUID.class, rule);
        var template = new TransactionTemplate(transactions);
        Instant cooldownAt = now.minusSeconds(30);
        Runnable stateA = () -> template.executeWithoutResult(s -> {            // value 25, ONLINE, cooling
            sql.update("UPDATE digital_twins SET latest_value=25, numeric_value=25 WHERE device_id=?", device);
            sql.update("UPDATE device_connectivity SET status='ONLINE' WHERE device_id=?", device);
            sql.update("INSERT INTO rule_cooldown_state(rule_id,scope_key,last_matched_at,last_match_id,updated_at) VALUES(?,'',?,?,now()) ON CONFLICT (rule_id,scope_key) DO UPDATE SET last_matched_at=EXCLUDED.last_matched_at,last_match_id=EXCLUDED.last_match_id",
                rule, Timestamp.from(cooldownAt), matchId);
        });
        Runnable stateB = () -> template.executeWithoutResult(s -> {            // value 5, OFFLINE, not cooling
            sql.update("UPDATE digital_twins SET latest_value=5, numeric_value=5 WHERE device_id=?", device);
            sql.update("UPDATE device_connectivity SET status='OFFLINE' WHERE device_id=?", device);
            sql.update("DELETE FROM rule_cooldown_state WHERE rule_id=?", rule);
        });
        stateA.run();
        var stop = new AtomicBoolean();
        var writer = Executors.newSingleThreadExecutor();
        var flips = new java.util.concurrent.atomic.AtomicInteger();
        var writing = writer.submit(() -> { boolean a = false; while (!stop.get()) { (a ? stateA : stateB).run(); a = !a; flips.incrementAndGet(); } });
        var readers = Executors.newFixedThreadPool(2);
        var observed = new ConcurrentLinkedQueue<String>();
        var done = new ArrayList<Future<?>>();
        for (int r = 0; r < 2; r++) done.add(readers.submit(() -> {
            for (int i = 0; i < 25; i++) {
                var v = service.run(current(device, rule)).versions().get(0);
                var c = v.conditions().stream().filter(x -> x.field() == RuleField.VALUE).findFirst().orElseThrow();
                observed.add(c.actualValue() + "|" + c.connectivity() + "|" + (v.cooldown().lastMatchedAt() != null));
            }
        }));
        for (var f : done) f.get(120, TimeUnit.SECONDS);
        stop.set(true);
        writing.get(30, TimeUnit.SECONDS);
        readers.shutdown(); writer.shutdown();
        assertTrue(flips.get() > 5, "the writer really flipped state while the dry-runs ran: " + flips.get());
        var distinct = new TreeSet<>(observed);
        for (String state : distinct)
            assertTrue(state.equals("25.0|ONLINE|true") || state.equals("5.0|OFFLINE|false"), "a mixed snapshot was observed: " + state + " in " + distinct);
        assertNoConnectionLeak();
    }
}
