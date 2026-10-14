package com.nexus.rule.trigger;

import com.nexus.event.domain.DomainEvent;
import com.nexus.event.domain.EventType;
import com.nexus.rule.application.RuleMatcher;
import com.nexus.rule.domain.*;
import com.nexus.rule.persistence.RuleCooldownStore;
import com.nexus.rule.persistence.RuleMatchPersister;
import com.nexus.rule.persistence.RuleMatchSuppressedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * C14 durable cooldown and EDGE_TRUE authority (scenarios 55/56). The authority is PostgreSQL, not a JVM map or monitor: these tests
 * disable the in-memory cache and the per-rule JVM lock (which is exactly what a second backend instance does not share) and prove
 * the database still admits exactly one match.
 */
class RuleDurableCooldownIntegrationTest extends TypedTriggerSupport {
    @Autowired RuleMatcher matcher;
    @Autowired RuleMatchPersister persisterBean;
    @Autowired com.nexus.rule.persistence.RuleVersionStore versionStore;
    @Autowired com.nexus.event.persistence.DomainEventPersister events;
    @SpyBean RuleMatchPersister persister;
    @SpyBean RuleCooldownStore cooldownStore;

    private final AtomicReference<Runnable> beforePersist = new AtomicReference<>();
    private final ExecutorService other = Executors.newCachedThreadPool();
    private Object originalLocks;

    @BeforeEach void independentInstancesAndHooks() {
        originalLocks = ReflectionTestUtils.getField(matcher, "ruleLocks");
        ReflectionTestUtils.setField(matcher, "ruleLocks", new ConcurrentHashMap<UUID, Object>() {
            @Override public Object computeIfAbsent(UUID key, Function<? super UUID, ?> fn) { return new Object(); }   // no shared JVM monitor
        });
        ((ConcurrentHashMap<?, ?>) ReflectionTestUtils.getField(matcher, "lastMatchedAt")).clear();                 // no shared memory
        doAnswer(invocation -> { Runnable hook = beforePersist.getAndSet(null); if (hook != null) hook.run(); return invocation.callRealMethod(); })
            .when(persister).persist(any());
    }

    @AfterEach void restore() {
        ReflectionTestUtils.setField(matcher, "ruleLocks", originalLocks);
        ((ConcurrentHashMap<?, ?>) ReflectionTestUtils.getField(matcher, "lastMatchedAt")).clear();
        reset(persister); reset(cooldownStore);
        other.shutdownNow();
    }

    private void commitElsewhere(Runnable action) {
        try { CompletableFuture.runAsync(action, other).get(60, TimeUnit.SECONDS); }
        catch (Exception e) { throw new IllegalStateException("the competing instance did not finish", e); }
    }

    private UUID platformRule(String name, Integer cooldown, RepeatPolicy repeat, CooldownScope scope) {
        UUID id = UUID.randomUUID();
        lifecycle.legacySave(new Rule(id, name, true, 1, List.of(temperatureAbove("10")), List.of(alert()), Map.of(), cooldown, null,
            new RuleRuntime(List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY)), repeat, null, scope)), null);
        return id;
    }

    private Map<String, Object> cooldownRow(UUID rule, String scopeKey) {
        var rows = sql.queryForList("SELECT last_matched_at,last_match_id FROM rule_cooldown_state WHERE rule_id=? AND scope_key=?", rule, scopeKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    // ------------------------------------------------------------------ the database serializes competing matches

    @Test void aSecondInstanceMatchingAfterTheFirstCommittedIsSuppressedByTheDatabaseNotByMemory() {
        UUID d1 = device(), d2 = device();
        UUID rule = platformRule("global cooldown", 60, null, null);
        Instant t0 = now;
        ingest(d1, 25, t0);                                                    // instance A matches
        assertEquals(1, matches(rule));
        assertNotNull(cooldownRow(rule, "").get("last_matched_at"));
        ((ConcurrentHashMap<?, ?>) ReflectionTestUtils.getField(matcher, "lastMatchedAt")).clear();   // instance B has never seen A's match
        now = t0.plusSeconds(10);
        ingest(d2, 26, now);                                                   // a different device, same logical rule, inside the window
        assertEquals(1, matches(rule), "the global cooldown is one authority per logical rule, held in the database");
        verify(persister, times(2)).persist(any());                             // B really attempted to persist and was refused by the gate
        now = t0.plusSeconds(60);                                              // exact boundary: now == last + cooldown is NOT suppressed
        ingest(d2, 27, now);
        assertEquals(2, matches(rule));
        assertEquals(now, ((java.sql.Timestamp) cooldownRow(rule, "").get("last_matched_at")).toInstant());
    }

    @Test void twoTransactionsRacingForTheSameFirstMatchProduceExactlyOne() throws Exception {
        UUID d1 = device(), d2 = device();
        UUID rule = platformRule("first match race", 60, null, null);
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new AtomicReference<Thread>();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();                       // this transaction now holds the (rule, scope) row
            if (first.compareAndSet(null, Thread.currentThread())) {
                holding.countDown();
                assertTrue(release.await(60, TimeUnit.SECONDS));
            }
            return result;
        }).when(cooldownStore).lockAndRead(any(), any());

        var a = other.submit(() -> ingest(d1, 25, now));
        assertTrue(holding.await(30, TimeUnit.SECONDS));
        var b = other.submit(() -> ingest(d2, 26, now));
        long deadline = System.currentTimeMillis() + 20_000;
        boolean blocked = false;
        while (System.currentTimeMillis() < deadline && !blocked) {
            blocked = count("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE '%rule_cooldown_state%'") > 0;
            if (!blocked) Thread.sleep(25);
        }
        assertTrue(blocked, "the competing transaction waits on the cooldown row instead of reading around it");
        release.countDown();
        a.get(30, TimeUnit.SECONDS); b.get(30, TimeUnit.SECONDS);

        assertEquals(1, matches(rule), "exactly one of the two competing matches was admitted");
        assertEquals(sql.queryForObject("SELECT match_id FROM rule_matches WHERE rule_id=?", UUID.class, rule), cooldownRow(rule, "").get("last_match_id"));
    }

    @Test void twoTransactionsRacingAfterTheWindowExpiredAlsoProduceExactlyOne() throws Exception {
        UUID d1 = device(), d2 = device();
        UUID rule = platformRule("expired window race", 60, null, null);
        Instant t0 = now;
        ingest(d1, 25, t0);
        assertEquals(1, matches(rule));
        ((ConcurrentHashMap<?, ?>) ReflectionTestUtils.getField(matcher, "lastMatchedAt")).clear();
        now = t0.plusSeconds(61);                                              // both competitors are past the window the row records
        var holding = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new AtomicReference<Thread>();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (first.compareAndSet(null, Thread.currentThread())) { holding.countDown(); assertTrue(release.await(60, TimeUnit.SECONDS)); }
            return result;
        }).when(cooldownStore).lockAndRead(any(), any());
        var a = other.submit(() -> ingest(d1, 26, now));
        assertTrue(holding.await(30, TimeUnit.SECONDS));
        var b = other.submit(() -> ingest(d2, 27, now));
        long deadline = System.currentTimeMillis() + 20_000;
        boolean blocked = false;
        while (System.currentTimeMillis() < deadline && !blocked) {
            blocked = count("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE '%rule_cooldown_state%FOR UPDATE%'") > 0;
            if (!blocked) Thread.sleep(25);
        }
        assertTrue(blocked, "the second transaction waits on the existing cooldown row (FOR UPDATE), it does not read the old value");
        release.countDown();
        a.get(30, TimeUnit.SECONDS); b.get(30, TimeUnit.SECONDS);
        assertEquals(2, matches(rule), "the first match plus exactly one of the two racing matches");
    }

    @Test void aFailedMatchPersistenceNeverAdvancesTheCooldown() {
        UUID d1 = device();
        UUID rule = platformRule("failed persist", 60, null, null);
        doThrow(new IllegalStateException("injected persistence failure after the claim")).doCallRealMethod().when(cooldownStore).advance(any(), any(), any(), any());
        ingest(d1, 25, now);
        assertEquals(0, matches(rule));
        var row = cooldownRow(rule, "");
        assertTrue(row == null || row.get("last_matched_at") == null, "the failed match rolled its cooldown claim back");
        now = now.plusSeconds(1);
        ingest(d1, 26, now);                                                   // no phantom cooldown: the next observation matches
        assertEquals(1, matches(rule));
        assertNotNull(cooldownRow(rule, "").get("last_matched_at"));
    }

    // ------------------------------------------------------------------ scope

    @Test void aPerScopeOptInKeepsOneCooldownPerResolvedDeviceWhileGlobalStaysOnePerRule() {
        UUID d1 = device(), d2 = device();
        UUID global = platformRule("global", 60, null, null);
        UUID scoped = platformRule("scoped", 60, null, CooldownScope.PER_SCOPE);
        Instant t0 = now;
        ingest(d1, 25, t0);
        now = t0.plusSeconds(5);
        ingest(d2, 26, now);                                                   // other device, inside both windows
        now = t0.plusSeconds(10);
        ingest(d1, 27, now);                                                   // same device again, inside both windows
        assertEquals(1, matches(global), "an imported-style rule keeps one global cooldown");
        assertEquals(2, matches(scoped), "the opted-in rule keeps one cooldown per resolved device");
        assertNotNull(cooldownRow(scoped, d1.toString()));
        assertNotNull(cooldownRow(scoped, d2.toString()));
        assertNull(cooldownRow(scoped, ""));
        assertNotNull(cooldownRow(global, ""));
        assertNull(cooldownRow(global, d1.toString()));
    }

    // ------------------------------------------------------------------ EDGE_TRUE across instances

    @Test void aDuplicateEdgeAcrossInstancesIsAdmittedOnlyOnce() {
        UUID device = device();
        UUID rule = platformRule("edge", null, RepeatPolicy.EDGE_TRUE, null);
        beforePersist.set(() -> {      // instance A is about to persist its edge; instance B completes its own first
            now = now.plusSeconds(1);
            commitElsewhere(() -> ingest(device, 27, now));
        });
        ingest(device, 25, now);                                               // A: evaluated true before B's edge existed

        assertEquals(1, matches(rule), "one edge, one match, even though both instances saw a true observation");
        assertEquals(1, count("SELECT count(*) FROM rule_repeat_state WHERE rule_id=? AND latched", rule));
        verify(persister, times(2)).persist(any());                             // both really reached the durable gate
    }

    @Test void aStaleFalseObservationFromASlowInstanceCannotRearmAConsumedEdge() {
        UUID device = device();
        UUID rule = platformRule("edge rearm", null, RepeatPolicy.EDGE_TRUE, null);
        Instant t0 = now;
        ingest(device, 25, t0);                                                // consumes the edge at t0
        assertEquals(1, matches(rule));
        var store = new com.nexus.rule.persistence.RuleRepeatStateStore(sql, clock);
        long generation = sql.queryForObject("SELECT generation FROM rule_repeat_state WHERE rule_id=?", Long.class, rule);
        store.rearm(rule, device, generation, t0.minusSeconds(30));            // a definitely-false observation older than the consumed edge
        assertTrue(store.latched(rule, device, generation), "stale evidence from another instance does not rearm");
        store.rearm(rule, device, generation, t0.plusSeconds(30));             // a genuinely newer false observation does
        assertFalse(store.latched(rule, device, generation));
    }

    // ------------------------------------------------------------------ history and policy changes

    @Test void aVersionReplacementNeverErasesPersistedCooldownAuthority() {
        UUID d1 = device();
        UUID rule = platformRule("policy change", 60, null, null);
        ingest(d1, 25, now);
        assertEquals(1, matches(rule));
        var current = versionStore.get(rule).orElseThrow();
        var definition = versionStore.version(rule, current.activeVersionId());
        var draft = lifecycle.draft(new Rule(rule, "successor", false, 1, definition.conditions(), definition.actions(), Map.of(), 60, null, definition.runtime()), current.rowRevision());
        lifecycle.transition(rule, "activate", draft.rowRevision());
        ((ConcurrentHashMap<?, ?>) ReflectionTestUtils.getField(matcher, "lastMatchedAt")).clear();
        now = now.plusSeconds(5);
        ingest(d1, 26, now);
        assertEquals(1, matches(rule), "the logical rule's cooldown survives the new version");
        lifecycle.transition(rule, "pause", null);
        lifecycle.transition(rule, "resume", null);
        ((ConcurrentHashMap<?, ?>) ReflectionTestUtils.getField(matcher, "lastMatchedAt")).clear();
        now = now.plusSeconds(5);
        ingest(d1, 27, now);
        assertEquals(1, matches(rule), "pause and resume do not reset it either");
    }

    @Test void thePopulatedHistoryUpgradeSeedsTheLatestEligibleMatchAsTheGlobalAuthority() {
        // V32 backfills from rule_matches; the same statement run against current history yields what the migration produced.
        UUID d1 = device();
        UUID rule = platformRule("history", 60, null, null);
        ingest(d1, 25, now);
        UUID matchId = sql.queryForObject("SELECT match_id FROM rule_matches WHERE rule_id=?", UUID.class, rule);
        sql.update("DELETE FROM rule_cooldown_state WHERE rule_id=?", rule);
        sql.update("""
            INSERT INTO rule_cooldown_state(rule_id, scope_key, last_matched_at, last_match_id, updated_at)
            SELECT DISTINCT ON (rule_id) rule_id, '', matched_at, match_id, now() FROM rule_matches WHERE cooldown_eligible AND rule_id=?
            ORDER BY rule_id, matched_at DESC, match_id DESC""", rule);
        assertEquals(matchId, cooldownRow(rule, "").get("last_match_id"));
        ((ConcurrentHashMap<?, ?>) ReflectionTestUtils.getField(matcher, "lastMatchedAt")).clear();
        now = now.plusSeconds(5);
        ingest(d1, 26, now);
        assertEquals(1, matches(rule), "the seeded authority suppresses exactly like live history");
    }
}
