package com.nexus.rule.trigger;

import com.nexus.action.application.ActionExecutionEngine;
import com.nexus.action.domain.ActionContext;
import com.nexus.action.domain.ActionExecutionRequest;
import com.nexus.action.domain.ActionType;
import com.nexus.rule.application.RuleEvaluationSnapshotService;
import com.nexus.rule.application.RuleExecutionAuthorization;
import com.nexus.rule.application.RuleExecutionSuppressedException;
import com.nexus.rule.application.RuleMatcher;
import com.nexus.rule.domain.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * A trigger fact whose SOURCE (latest accepted observation, reported transition, connectivity generation) was replaced must never
 * decide or accept anything. The newer source commit is injected at exact points with barriers - never with sleeps:
 * <ul>
 * <li>after the recovery eligibility pre-check but before the evaluation snapshot (recovery and inline entry points);</li>
 * <li>after the snapshot and the persisted plan but before command acceptance;</li>
 * <li>after a legitimate acceptance (history must be preserved, never rewritten).</li>
 * </ul>
 * The newer source commits on another thread (the routing of the older fact runs in its source transaction's afterCommit phase, where
 * a nested transaction would join the finished one). Its own fact routes inline and acts on its own; the older fact must add nothing.
 */
class RuleSourceCurrentnessRaceIntegrationTest extends TypedTriggerSupport {
    @Autowired RuleFactRecoveryScanner recovery;
    @Autowired RuleEvaluationSnapshotService snapshots;
    @Autowired RuleExecutionAuthorization authorization;
    @Autowired PlatformTransactionManager transactions;
    @Autowired com.nexus.rule.persistence.RuleVersionStore versionStore;
    @SpyBean RuleMatcher matcher;
    @SpyBean ActionExecutionEngine engine;

    private static final List<TriggerSubscription> TELEMETRY = List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY));
    private static final List<TriggerSubscription> CONNECTIVITY = List.of(new TriggerSubscription(TriggerType.CONNECTIVITY_CHANGED));
    private final AtomicReference<Runnable> beforeMatching = new AtomicReference<>();
    private final AtomicReference<Runnable> beforeDispatch = new AtomicReference<>();
    private final AtomicReference<Runnable> afterDispatch = new AtomicReference<>();
    private final AtomicBoolean outage = new AtomicBoolean();
    private final ExecutorService other = Executors.newCachedThreadPool();

    @org.junit.jupiter.api.BeforeEach void installBarriers() {
        doAnswer(invocation -> { Runnable hook = beforeMatching.getAndSet(null); if (hook != null) hook.run(); return invocation.callRealMethod(); })
            .when(matcher).processFact(any(), any());
        doAnswer(invocation -> {
            Runnable before = beforeDispatch.getAndSet(null); if (before != null) before.run();
            Object result = invocation.callRealMethod();
            Runnable after = afterDispatch.getAndSet(null); if (after != null) after.run();
            return result;
        }).when(engine).process(any(), any(), any());
    }

    @AfterEach void restore() { snapshots.setReadProbe(null); reset(matcher); reset(engine); other.shutdownNow(); }

    private void databaseOutage(boolean on) {
        outage.set(on);
        snapshots.setReadProbe(on ? phase -> { if (outage.get()) throw new IllegalStateException("injected snapshot outage"); } : null);
    }

    /** Runs the action on another thread (a fresh transaction context) and waits for it. */
    private void commitElsewhere(Runnable action) {
        try { CompletableFuture.runAsync(action, other).get(60, TimeUnit.SECONDS); }
        catch (Exception e) { throw new IllegalStateException("the newer source did not commit", e); }
    }

    private UUID factOf(UUID device, String type, String order) { return sql.queryForObject("SELECT fact_id FROM rule_trigger_facts WHERE trigger_type=? AND device_id=? ORDER BY recorded_at " + order + ", fact_id LIMIT 1", UUID.class, type, device); }
    private String status(UUID fact) { return sql.queryForObject("SELECT routing_status FROM rule_trigger_facts WHERE fact_id=?", String.class, fact); }
    private String note(UUID fact) { return sql.queryForObject("SELECT routing_error FROM rule_trigger_facts WHERE fact_id=?", String.class, fact); }
    private int plansOf(UUID fact) { return count("SELECT count(*) FROM rule_action_plans WHERE fact_id=?", fact); }
    private int matchesOf(UUID fact) { return count("SELECT count(*) FROM rule_matches WHERE trigger_fact_id=?", fact); }
    private int commands(UUID device) { return count("SELECT count(*) FROM action_history WHERE device_id=?", device); }
    private int desiredChanges(UUID device) { return count("SELECT count(*) FROM twin_state_transitions WHERE device_id=? AND transition_type='DESIRED_STATE_CHANGED'", device); }
    private int acceptedFor(UUID fact) {
        return count("SELECT count(*) FROM action_history h JOIN rule_action_candidates c ON c.request_id=h.request_id JOIN rule_action_plans p ON p.plan_id=c.plan_id WHERE p.fact_id=?", fact);
    }

    // ------------------------------------------------------------------ 1. superseded before the snapshot

    @Test void aNewerObservationCommittedAfterTheRecoveryPreCheckAndBeforeTheSnapshotMakesTheOldFactDecideNothing() {
        UUID device = device();
        UUID rule = typedRule(device, "recovery race", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(device, true)));
        databaseOutage(true);
        ingest(device, 25, now.minusSeconds(5));                                       // F1: committed, routing failed
        databaseOutage(false);
        UUID f1 = factOf(device, "ACCEPTED_TELEMETRY", "ASC");
        assertEquals("FAILED", status(f1));
        now = now.plusSeconds(40);
        beforeMatching.set(() -> { now = now.plusSeconds(1); commitElsewhere(() -> ingest(device, 27, now)); });   // F2 commits after the pre-check

        assertEquals(1, recovery.recover(10));

        UUID f2 = factOf(device, "ACCEPTED_TELEMETRY", "DESC");
        assertNotEquals(f1, f2);
        assertEquals("EXPIRED", status(f1));
        assertTrue(note(f1).startsWith("EXPIRED:SUPERSEDED_BY_NEWER_OBSERVATION"), note(f1));
        assertEquals(0, matchesOf(f1), "no attributed RuleMatch for the superseded fact");
        assertEquals(0, plansOf(f1), "no ActionPlan for the superseded fact");
        assertEquals(1, matchesOf(f2));
        assertEquals(1, acceptedFor(f2), "the newer observation acted on its own");
        assertEquals(1, commands(device), "the old fact added no second command");
        assertEquals(1, desiredChanges(device));
        verify(executor, times(1)).publishPrepared(any(), any());
    }

    @Test void theSameOrderingThroughInlineRoutingAlsoDecidesNothingForTheOldFact() {
        UUID device = device();
        UUID rule = typedRule(device, "inline race", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(device, true)));
        beforeMatching.set(() -> { now = now.plusSeconds(1); commitElsewhere(() -> ingest(device, 27, now)); });     // F2 commits before F1's snapshot

        ingest(device, 25, now);                                                       // F1: routed inline right after its own commit

        UUID f1 = factOf(device, "ACCEPTED_TELEMETRY", "ASC"), f2 = factOf(device, "ACCEPTED_TELEMETRY", "DESC");
        assertNotEquals(f1, f2);
        assertEquals("EXPIRED", status(f1));
        assertTrue(note(f1).startsWith("EXPIRED:SUPERSEDED_BY_NEWER_OBSERVATION"), note(f1));
        assertEquals(0, matchesOf(f1));
        assertEquals(0, plansOf(f1));
        assertEquals(1, commands(device));
        assertEquals(1, acceptedFor(f2));
        assertEquals("ROUTED", status(f2));
        verify(executor, times(1)).publishPrepared(any(), any());
    }

    // ------------------------------------------------------------------ 2. superseded between the plan and the acceptance

    @Test void aNewerObservationCommittedAfterTheSnapshotAndPlanButBeforeAcceptanceCannotAcceptTheOldCommand() {
        UUID device = device();
        UUID rule = typedRule(device, "acceptance race", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(device, true)));
        beforeDispatch.set(() -> { now = now.plusSeconds(1); commitElsewhere(() -> ingest(device, 27, now)); });     // F2 commits after F1's plan exists

        ingest(device, 25, now);                                                       // F1

        UUID f1 = factOf(device, "ACCEPTED_TELEMETRY", "ASC"), f2 = factOf(device, "ACCEPTED_TELEMETRY", "DESC");
        assertNotEquals(f1, f2);
        assertEquals(1, matchesOf(f1), "the decision was made while F1 was current: it is history and stays");
        assertEquals(1, plansOf(f1));
        assertEquals("SELECTED", sql.queryForObject("SELECT c.disposition FROM rule_action_candidates c JOIN rule_action_plans p ON p.plan_id=c.plan_id WHERE p.fact_id=?", String.class, f1));
        assertEquals(0, acceptedFor(f1), "but its command was never accepted");
        assertEquals(1, acceptedFor(f2));
        assertEquals(1, commands(device), "exactly one accepted command: the newer observation's");
        assertEquals(1, desiredChanges(device), "no desired-state mutation from the superseded fact");
        verify(executor, times(1)).publishPrepared(any(), any());
    }

    @Test void theAcceptanceTimeRecheckAlsoGuardsTheRecoveryEntryPoint() {
        UUID device = device();
        UUID rule = typedRule(device, "recovery acceptance race", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(device, true)));
        databaseOutage(true);
        ingest(device, 25, now.minusSeconds(5));
        databaseOutage(false);
        UUID f1 = factOf(device, "ACCEPTED_TELEMETRY", "ASC");
        now = now.plusSeconds(40);
        beforeDispatch.set(() -> { now = now.plusSeconds(1); commitElsewhere(() -> ingest(device, 27, now)); });

        recovery.recover(10);

        UUID f2 = factOf(device, "ACCEPTED_TELEMETRY", "DESC");
        assertNotEquals(f1, f2);
        assertEquals(1, matchesOf(f1));
        assertEquals(0, acceptedFor(f1));
        assertEquals(1, commands(device));
        verify(executor, times(1)).publishPrepared(any(), any());
    }

    @Test void anAcceptanceThatLinearizedBeforeTheNewerObservationKeepsItsHistory() {
        UUID device = device();
        UUID rule = typedRule(device, "linearized first", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(device, true)));
        var historyBefore = new AtomicReference<Map<String, Object>>();
        afterDispatch.set(() -> {      // the command is accepted; only now does the newer observation commit
            historyBefore.set(sql.queryForMap("SELECT request_id,status,\"timestamp\",message FROM action_history WHERE device_id=?", device));
            now = now.plusSeconds(1);
            commitElsewhere(() -> ingest(device, 27, now));
        });

        ingest(device, 25, now);

        UUID f1 = factOf(device, "ACCEPTED_TELEMETRY", "ASC"), f2 = factOf(device, "ACCEPTED_TELEMETRY", "DESC");
        assertNotNull(historyBefore.get(), "the hook ran after the first acceptance");
        assertNotEquals(f1, f2, "facts: " + count("SELECT count(*) FROM rule_trigger_facts WHERE device_id=?", device));
        assertNotNull(historyBefore.get(), "the first acceptance had linearized before the newer observation committed");
        assertEquals(1, acceptedFor(f1), "the earlier acceptance is history and is not rewritten");
        var after = sql.queryForMap("SELECT request_id,status,\"timestamp\",message FROM action_history WHERE request_id=?", historyBefore.get().get("request_id"));
        assertEquals(historyBefore.get().get("status"), after.get("status"));
        assertEquals(historyBefore.get().get("timestamp"), after.get("timestamp"));
        assertEquals(1, acceptedFor(f2), "the newer observation then acts on its own");
        assertEquals(2, commands(device));
    }

    // ------------------------------------------------------------------ 4. connectivity generation

    @Test void aConnectivityGenerationChangedBeforeTheSnapshotMakesTheOldTransitionDecideNothing() {
        UUID device = device();
        UUID rule = typedRule(device, "connectivity snapshot race", 1, CONNECTIVITY, List.of(), List.of(alert()));
        clearInvocations(alerts);
        beforeMatching.set(() -> { now = now.plusSeconds(1); commitElsewhere(() -> connectivity.applyObservedStatus(device, com.nexus.device.connectivity.DeviceConnectivityStatus.ONLINE)); });

        connectivity.applyObservedStatus(device, com.nexus.device.connectivity.DeviceConnectivityStatus.OFFLINE);       // F1 = generation g+1, then ONLINE = g+2

        UUID f1 = factOf(device, "CONNECTIVITY_CHANGED", "ASC"), f2 = factOf(device, "CONNECTIVITY_CHANGED", "DESC");
        assertNotEquals(f1, f2);
        assertEquals("EXPIRED", status(f1));
        assertTrue(note(f1).startsWith("EXPIRED:SUPERSEDED_BY_NEWER_GENERATION"), note(f1));
        assertEquals(0, matchesOf(f1));
        assertEquals(0, plansOf(f1));
        assertEquals(1, matchesOf(f2));
        verify(alerts, times(1)).execute(any(), any());
    }

    @Test void aConnectivityGenerationChangedBeforeAcceptanceCannotAcceptTheOldCommand() {
        UUID device = device();
        UUID rule = typedRule(device, "connectivity acceptance race", 1, CONNECTIVITY, List.of(), List.of(ledCommand(device, true)));
        beforeDispatch.set(() -> { now = now.plusSeconds(1); commitElsewhere(() -> connectivity.applyObservedStatus(device, com.nexus.device.connectivity.DeviceConnectivityStatus.ONLINE)); });

        connectivity.applyObservedStatus(device, com.nexus.device.connectivity.DeviceConnectivityStatus.OFFLINE);

        UUID f1 = factOf(device, "CONNECTIVITY_CHANGED", "ASC"), f2 = factOf(device, "CONNECTIVITY_CHANGED", "DESC");
        assertNotEquals(f1, f2);
        assertEquals(1, matchesOf(f1), "decided while its generation was current");
        assertEquals(0, acceptedFor(f1), "but not accepted once superseded");
        assertEquals(1, acceptedFor(f2));
        assertEquals(1, commands(device));
        verify(executor, times(1)).publishPrepared(any(), any());
    }

    @Test void aReportedTransitionSupersededBeforeTheSnapshotDecidesNothing() {
        UUID device = device();
        UUID rule = typedRule(device, "transition race", 1, List.of(new TriggerSubscription(TriggerType.TWIN_STATE_CHANGED)), List.of(), List.of(alert()));
        clearInvocations(alerts);
        beforeMatching.set(() -> { now = now.plusSeconds(1); commitElsewhere(() -> ingest(device, 27, now)); });     // a later semantic change

        ingest(device, 25, now);                                                       // F1 = the initial reported transition

        UUID f1 = factOf(device, "TWIN_STATE_CHANGED", "ASC"), f2 = factOf(device, "TWIN_STATE_CHANGED", "DESC");
        assertNotEquals(f1, f2);
        assertEquals("EXPIRED", status(f1));
        assertTrue(note(f1).startsWith("EXPIRED:SUPERSEDED_BY_NEWER_TRANSITION"), note(f1));
        assertEquals(0, matchesOf(f1));
        assertEquals(0, plansOf(f1));
        assertEquals(1, matchesOf(f2));
        verify(alerts, times(1)).execute(any(), any());
    }

    // ------------------------------------------------------------------ 5. still current

    @Test void aStillCurrentFactDecidesAndAcceptsNormally() {
        UUID device = device();
        UUID rule = typedRule(device, "current", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(device, true)));
        ingest(device, 25, now);
        UUID f1 = factOf(device, "ACCEPTED_TELEMETRY", "ASC");
        assertEquals("ROUTED", status(f1));
        assertNull(note(f1));
        assertEquals(1, matchesOf(f1));
        assertEquals(1, acceptedFor(f1));
        assertEquals(1, commands(device));
        verify(executor, times(1)).publishPrepared(any(), any());
    }

    // ------------------------------------------------------------------ the lock protocol itself

    private record Planned(UUID device, UUID matchId, UUID requestId, Rule pinned) {}

    private Planned plannedCommand() {
        UUID device = device();
        UUID rule = typedRule(device, "locked check", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(device, true)));
        doNothing().when(engine).process(any(), any(), any());
        try { ingest(device, 25, now.minusSeconds(5)); } finally { installBarriers(); }
        UUID matchId = sql.queryForObject("SELECT match_id FROM rule_matches WHERE rule_id=?", UUID.class, rule);
        UUID requestId = sql.queryForObject("SELECT request_id FROM rule_action_candidates WHERE match_id=?", UUID.class, matchId);
        var owner = sql.queryForMap("SELECT rule_version_id,rule_revision,execution_epoch FROM rule_matches WHERE match_id=?", matchId);
        var definition = versionStore.version(rule, (UUID) owner.get("rule_version_id"));
        Rule pinned = new Rule(rule, definition.name(), true, 1, definition.conditions(), definition.actions(), Map.of(), null,
            new RuleExecutionIdentity((UUID) owner.get("rule_version_id"), ((Number) owner.get("rule_revision")).longValue(), ((Number) owner.get("execution_epoch")).longValue()), definition.runtime());
        return new Planned(device, matchId, requestId, pinned);
    }

    private ActionExecutionRequest exact(Planned p) {
        return new ActionExecutionRequest(p.requestId(), p.matchId(), ActionType.SEND_COMMAND, new LinkedHashMap<>(ledCommand(p.device(), true).parameters()));
    }

    private boolean waitingOn(String fragment) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (count("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE ?", "%" + fragment + "%") > 0) return true;
            Thread.sleep(25);
        }
        return false;
    }

    @Test void acceptanceWaitsForAnInFlightNewerObservationAndThenDeniesItsStaleCommand() throws Exception {
        Planned p = plannedCommand();
        assertEquals(0, commands(p.device()));
        try (var writer = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword())) {
            writer.setAutoCommit(false);
            try (var st = writer.createStatement()) {      // a telemetry transaction mid-flight: it has updated the Twin row and not yet committed
                st.executeUpdate("UPDATE digital_twins SET received_at = received_at + interval '1 second' WHERE device_id='" + p.device() + "'");
            }
            Future<?> accepting = other.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(
                tx -> authorization.check(exact(p), new ActionContext(null, p.pinned()))));
            assertTrue(waitingOn("FROM digital_twins WHERE device_id=% FOR SHARE"), "the acceptance-time check is queued behind the newer observation instead of reading around it");
            assertFalse(accepting.isDone());
            writer.commit();
            var failure = assertThrows(ExecutionException.class, () -> accepting.get(30, TimeUnit.SECONDS));
            assertInstanceOf(RuleExecutionSuppressedException.class, failure.getCause());
            assertEquals("SOURCE_SUPERSEDED: SUPERSEDED_BY_NEWER_OBSERVATION", failure.getCause().getMessage());
        }
        assertEquals(0, commands(p.device()));
        assertEquals(0, desiredChanges(p.device()));
        verify(executor, never()).publishPrepared(any(), any());
    }

    @Test void aNewerObservationWaitsForAnAcceptanceThatHoldsTheSourceRowAndTheAcceptanceStaysValid() throws Exception {
        Planned p = plannedCommand();
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Future<?> accepting = other.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            authorization.check(exact(p), new ActionContext(null, p.pinned()));     // passes: the source is current, the Twin row is now held FOR SHARE
            holding.countDown();
            try { assertTrue(release.await(60, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new IllegalStateException(e); }
        }));
        assertTrue(holding.await(30, TimeUnit.SECONDS));
        try (var writer = DriverManager.getConnection(db.getJdbcUrl(), db.getUsername(), db.getPassword())) {
            writer.setAutoCommit(false);
            try (var st = writer.createStatement()) {
                st.execute("SET lock_timeout='400ms'");
                assertThrows(SQLException.class, () -> st.executeUpdate("UPDATE digital_twins SET received_at = received_at + interval '1 second' WHERE device_id='" + p.device() + "'"),
                    "a newer observation cannot slip in while an acceptance has verified the source");
            }
            writer.rollback();
            release.countDown();
            accepting.get(30, TimeUnit.SECONDS);
            try (var st = writer.createStatement()) {
                st.executeUpdate("UPDATE digital_twins SET received_at = received_at + interval '1 second' WHERE device_id='" + p.device() + "'");   // proceeds once the acceptance is over
            }
            writer.commit();
        }
    }
}
