package com.nexus.rule.trigger;

import com.nexus.rule.application.RuleEvaluationSnapshotService;
import com.nexus.rule.domain.*;
import com.nexus.rule.temporal.RuleTemporalRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * M-A: a trigger fact recovered long after it occurred is evaluated against CURRENT state, so it may only act while it still
 * describes that state and only through the rule versions/epochs that were active when it was recorded. Otherwise the fact is
 * durably EXPIRED (never matched, never planned, never dispatched). Occurrence time, recording time and evaluation time stay distinct
 * and temporal evidence is applied either way. Nothing here resends a command: M-B (a plan persisted before a crash) is unchanged.
 */
class RuleDelayedRecoveryEligibilityIntegrationTest extends TypedTriggerSupport {
    @Autowired RuleFactRecoveryScanner recovery;
    @Autowired RuleEvaluationSnapshotService snapshots;
    @Autowired com.nexus.rule.persistence.RuleVersionStore versionStore;
    @SpyBean RuleTemporalRuntime temporal;
    private static final List<TriggerSubscription> TELEMETRY = List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY));
    private final AtomicBoolean outage = new AtomicBoolean();

    @AfterEach void restore() { snapshots.setReadProbe(null); reset(planStore); reset(temporal); }

    private void databaseOutage(boolean on) {
        outage.set(on);
        snapshots.setReadProbe(on ? phase -> { if (outage.get()) throw new IllegalStateException("injected snapshot outage"); } : null);
    }
    private UUID onlyFact(UUID device, String type) { return sql.queryForObject("SELECT fact_id FROM rule_trigger_facts WHERE trigger_type=? AND device_id=?", UUID.class, type, device); }
    private String status(UUID fact) { return sql.queryForObject("SELECT routing_status FROM rule_trigger_facts WHERE fact_id=?", String.class, fact); }
    private String note(UUID fact) { return sql.queryForObject("SELECT routing_error FROM rule_trigger_facts WHERE fact_id=?", String.class, fact); }
    private int plansOf(UUID fact) { return count("SELECT count(*) FROM rule_action_plans WHERE fact_id=?", fact); }
    private int matchesOf(UUID fact) { return count("SELECT count(*) FROM rule_matches WHERE trigger_fact_id=?", fact); }
    private int commands(UUID device) { return count("SELECT count(*) FROM action_history WHERE device_id=?", device); }

    @Test void aFreshRecoverableFactStillMatchesOnceAndKeepsItsTruthfulIdentity() {
        UUID device = device();
        UUID rule = typedRule(device, "fresh", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(device, true)));
        databaseOutage(true);
        Instant deviceTime = ingest(device, 25, now.minusSeconds(5));
        databaseOutage(false);
        UUID fact = onlyFact(device, "ACCEPTED_TELEMETRY");
        assertEquals("FAILED", status(fact));
        now = now.plusSeconds(40);                                    // still the latest observation, still inside the freshness threshold
        assertEquals(1, recovery.recover(10));
        assertEquals("ROUTED", status(fact));
        assertNull(note(fact), "nothing was skipped or expired");
        assertEquals(1, matchesOf(fact));
        assertEquals(1, plansOf(fact));
        assertEquals(1, commands(device));
        verify(executor, times(1)).publishPrepared(any(), any());
        assertEquals(deviceTime, sql.queryForObject("SELECT occurred_at FROM rule_trigger_facts WHERE fact_id=?", java.sql.Timestamp.class, fact).toInstant(), "the occurrence time is never rewritten by recovery");
        assertEquals(0, recovery.recover(10));
        assertEquals(1, commands(device));
    }

    @Test void aSupersededFactExpiresAndAnOldEventNeverManufacturesCurrentIntent() {
        UUID device = device();
        UUID rule = typedRule(device, "superseded", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(device, true)));
        databaseOutage(true);
        ingest(device, 5, now.minusSeconds(5));                       // the OLD event: below the threshold, it never satisfied the rule
        databaseOutage(false);
        UUID oldFact = onlyFact(device, "ACCEPTED_TELEMETRY");
        assertEquals("FAILED", status(oldFact));
        now = now.plusSeconds(40);
        ingest(device, 30, now);                                      // the Twin moved on: the NEW fact satisfies the rule and acts once on its own
        assertEquals(1, matches(rule));
        assertEquals(1, commands(device));
        clearInvocations(alerts);
        clearInvocations(executor);

        now = now.plusSeconds(10);
        assertEquals(1, recovery.recover(10), "an expired fact is a completed disposition, not a failure");
        assertEquals("EXPIRED", status(oldFact));
        String audit = note(oldFact);
        assertTrue(audit.startsWith("EXPIRED:SUPERSEDED_BY_NEWER_OBSERVATION"), audit);
        assertTrue(audit.contains("occurredAt=") && audit.contains("recordedAt=") && audit.contains("evaluatedAt=" + now), "the three times stay distinct: " + audit);
        assertEquals(0, matchesOf(oldFact), "the old event was not evaluated against the newer state");
        assertEquals(0, plansOf(oldFact));
        assertEquals(1, matches(rule), "only the new fact produced a match");
        assertEquals(1, commands(device), "no second command");
        verify(executor, never()).publishPrepared(any(), any());
        assertEquals(0, recovery.recover(10), "EXPIRED is terminal");
        assertEquals("EXPIRED", status(oldFact));
    }

    @Test void aSignificantlyDelayedCurrentFactIsStillJudgedByTheFreshnessGateAndNeverDispatchesOnStaleState() {
        UUID device = device();
        UUID rule = typedRule(device, "stale", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(device, true)));
        databaseOutage(true);
        ingest(device, 25, now.minusSeconds(5));
        databaseOutage(false);
        UUID fact = onlyFact(device, "ACCEPTED_TELEMETRY");
        now = now.plusSeconds(3600);                                  // an hour later, no newer observation: the fact is "current" but the reading is stale
        assertEquals(1, recovery.recover(10));
        assertEquals("ROUTED", status(fact));
        assertEquals(0, matches(rule), "the P2.3 freshness gate refuses to act on a stale reading");
        assertEquals(0, commands(device));
        verify(executor, never()).publishPrepared(any(), any());
    }

    @Test void pauseAndResumeBetweenTheFactAndItsRecoveryNeverLetTheNewEpochActOnTheOldFact() {
        UUID device = device();
        UUID rule = typedRule(device, "paused and resumed", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(device, true)));
        databaseOutage(true);
        ingest(device, 25, now.minusSeconds(5));
        databaseOutage(false);
        UUID fact = onlyFact(device, "ACCEPTED_TELEMETRY");
        lifecycle.transition(rule, "pause", null);
        lifecycle.transition(rule, "resume", null);                   // a new execution epoch, active again before recovery
        now = now.plusSeconds(40);
        assertEquals(1, recovery.recover(10));
        assertEquals("ROUTED", status(fact));
        assertTrue(note(fact).contains("RECOVERY_RULES_NOT_ADMITTED") && note(fact).contains(rule.toString()), note(fact));
        assertEquals(0, matches(rule));
        assertEquals(0, commands(device));
        verify(executor, never()).publishPrepared(any(), any());
    }

    @Test void aVersionActivatedAfterTheFactCannotActOnItWhileFutureFactsStillWork() {
        UUID device = device();
        UUID rule = typedRule(device, "replaced", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(device, true)));
        databaseOutage(true);
        ingest(device, 25, now.minusSeconds(5));
        databaseOutage(false);
        UUID oldFact = onlyFact(device, "ACCEPTED_TELEMETRY");
        var view = versionStore.get(rule).orElseThrow();
        lifecycle.legacySave(new Rule(rule, "replacement", true, 1, view.definition().conditions(), List.of(alert()), Map.of(), null, null, view.definition().runtime()), null);
        clearInvocations(alerts);
        now = now.plusSeconds(40);
        assertEquals(1, recovery.recover(10));
        assertEquals(0, matches(rule), "the replacement version did not exist when the fact was recorded");
        verify(alerts, never()).execute(any(), any());
        verify(executor, never()).publishPrepared(any(), any());
        assertTrue(note(oldFact).contains("RECOVERY_RULES_NOT_ADMITTED"), note(oldFact));
        ingest(device, 26, now);                                      // the replacement works normally on a fact recorded after it was activated
        assertEquals(1, matches(rule));
        verify(alerts, times(1)).execute(any(), any());
    }

    @Test void aFactWithoutARecordedRuleAuthorityIsNeverMatchedByRecovery() {
        UUID device = device();
        UUID rule = typedRule(device, "legacy fact", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(device, true)));
        databaseOutage(true);
        ingest(device, 25, now.minusSeconds(5));
        databaseOutage(false);
        UUID fact = onlyFact(device, "ACCEPTED_TELEMETRY");
        sql.update("UPDATE rule_trigger_facts SET rule_authority=NULL WHERE fact_id=?", fact);   // as for a fact recorded before V31
        now = now.plusSeconds(40);
        assertEquals(1, recovery.recover(10));
        assertEquals("EXPIRED", status(fact));
        assertTrue(note(fact).startsWith("EXPIRED:RULE_AUTHORITY_NOT_RECORDED"), note(fact));
        assertEquals(0, matches(rule));
        assertEquals(0, commands(device));
    }

    @Test void aSupersededConnectivityGenerationExpiresWithoutAnActionPlan() {
        UUID device = device();
        UUID rule = typedRule(device, "connectivity", 1, List.of(new TriggerSubscription(TriggerType.CONNECTIVITY_CHANGED)), List.of(), List.of(alert()));
        databaseOutage(true);
        connectivity.applyObservedStatus(device, com.nexus.device.connectivity.DeviceConnectivityStatus.OFFLINE);
        databaseOutage(false);
        UUID offlineFact = onlyFact(device, "CONNECTIVITY_CHANGED");
        assertEquals("FAILED", status(offlineFact));
        now = now.plusSeconds(40);
        connectivity.applyObservedStatus(device, com.nexus.device.connectivity.DeviceConnectivityStatus.ONLINE);   // a newer generation, routed on its own
        int before = matches(rule);
        clearInvocations(alerts);
        now = now.plusSeconds(10);
        assertEquals(1, recovery.recover(10));
        assertEquals("EXPIRED", status(offlineFact));
        assertTrue(note(offlineFact).startsWith("EXPIRED:SUPERSEDED_BY_NEWER_GENERATION"), note(offlineFact));
        assertEquals(0, matchesOf(offlineFact));
        assertEquals(0, plansOf(offlineFact));
        assertEquals(before, matches(rule));
        verify(alerts, never()).execute(any(), any());
    }

    @Test void delayedTemporalEvidenceIsStillAppliedWhenTheFactItselfExpires() {
        UUID device = device();
        UUID rule = temporalRule(device, "duration", new TemporalSpec(TemporalOperator.FOR_DURATION, 600L, null, null, null, null), true, RuleOperator.GT, "10", List.of(alert()));
        doThrow(new IllegalStateException("injected observation failure")).doCallRealMethod().when(temporal).observe(any());
        ingest(device, 25, now.minusSeconds(5));
        UUID oldFact = onlyFact(device, "ACCEPTED_TELEMETRY");
        assertEquals("FAILED", status(oldFact));
        assertNull(sql.queryForObject("SELECT temporal_applied_at FROM rule_trigger_facts WHERE fact_id=?", java.sql.Timestamp.class, oldFact), "the evidence is committed but not applied");
        now = now.plusSeconds(40);
        ingest(device, 26, now);                                      // supersedes the old fact as the latest observation
        now = now.plusSeconds(10);
        assertEquals(1, recovery.recover(10));
        assertEquals("EXPIRED", status(oldFact), "the old fact may not match rules");
        assertNotNull(sql.queryForObject("SELECT temporal_applied_at FROM rule_trigger_facts WHERE fact_id=?", java.sql.Timestamp.class, oldFact), "but its recorded sample was applied to temporal state");
        assertTrue(count("SELECT count(*) FROM rule_temporal_evidence WHERE rule_id=? AND details->>'factId'=?", rule, oldFact.toString()) >= 1,
            "temporal evidence cites the recovered fact");
        assertEquals(0, matches(rule));
        assertEquals(0, plansOf(oldFact));
    }

    @Test void commandOutcomeManualAndVerificationFactsHaveNoCurrentStateCounterpartAndFailClosed() {
        var eligibility = new RuleRecoveryEligibility(null, null, null);
        for (TriggerType type : List.of(TriggerType.COMMAND_PUBLICATION_FAILED, TriggerType.COMMAND_ACK_REJECTED, TriggerType.COMMAND_ACK_TIMED_OUT,
                TriggerType.VERIFICATION_CHANGED, TriggerType.VERIFICATION_TIMED_OUT, TriggerType.MANUAL)) {
            var fact = new TriggerObservation(type, UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), java.time.Instant.EPOCH, java.time.Instant.EPOCH, Map.of(), Map.of(), "c", null, 0, List.of());
            assertEquals(Optional.of("NO_CURRENT_STATE_COUNTERPART"), eligibility.expiry(fact), type.name());
        }
        for (TriggerType type : List.of(TriggerType.SCHEDULE, TriggerType.TEMPORAL_DUE)) {
            var fact = new TriggerObservation(type, UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), java.time.Instant.EPOCH, java.time.Instant.EPOCH, Map.of(), Map.of(), "c", null, 0, List.of());
            assertTrue(eligibility.expiry(fact).isEmpty(), type + " carries its own durable authority");
        }
        assertFalse(RuleRecoveryEligibility.admits(Map.of(), UUID.randomUUID(), UUID.randomUUID(), 0), "an unrecorded rule is never admitted");
        UUID rule = UUID.randomUUID(), version = UUID.randomUUID();
        var recorded = Map.<String, Map<String, Object>>of(rule.toString(), Map.of("v", version.toString(), "e", 3));
        assertTrue(RuleRecoveryEligibility.admits(recorded, rule, version, 3));
        assertFalse(RuleRecoveryEligibility.admits(recorded, rule, version, 4), "a later epoch");
        assertFalse(RuleRecoveryEligibility.admits(recorded, rule, UUID.randomUUID(), 3), "a different version");
    }

}
