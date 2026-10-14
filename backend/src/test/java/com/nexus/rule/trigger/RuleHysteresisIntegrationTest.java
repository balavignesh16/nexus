package com.nexus.rule.trigger;

import com.nexus.device.capability.CapabilityType;
import com.nexus.device.connectivity.DeviceConnectivityStatus;
import com.nexus.rule.application.RuleCapabilityValidator;
import com.nexus.rule.domain.*;
import com.nexus.rule.persistence.RuleHysteresisStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C14 numeric hysteresis (scenario 58): explicit same-unit enter/exit thresholds, exact boundaries, no flapping inside the band,
 * UNKNOWN / non-GOOD evidence neither sets nor clears the latch, generations reset on lifecycle change without a synthetic
 * transition, and an older observation can never overwrite a newer one.
 */
class RuleHysteresisIntegrationTest extends TypedTriggerSupport {
    @Autowired RuleCapabilityValidator validator;
    @Autowired RuleHysteresisStore store;
    private static final List<TriggerSubscription> TELEMETRY = List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY));

    private static RuleCondition band(RuleOperator operator, String enter, String exit) {
        return new RuleCondition(RuleField.VALUE, operator, enter, CapabilityType.TEMPERATURE, null, null, "h1", null, new HysteresisSpec(exit));
    }
    private UUID hysteresisRule(UUID device, RuleCondition condition) {
        return typedRule(device, "hysteresis", 1, TELEMETRY, List.of(condition), List.of(alert()));
    }
    private void observe(UUID device, double value) { now = now.plusSeconds(1); ingest(device, value, now); }
    private boolean latched(UUID rule, UUID device) {
        long epoch = sql.queryForObject("SELECT execution_epoch FROM rules WHERE rule_id=?", Long.class, rule);
        return store.latched(rule, "h1", device, epoch);
    }
    private Set<String> codes(Rule rule) { var set = new TreeSet<String>(); validator.inspect(rule).forEach(e -> set.add(e.get("code").toString())); return set; }
    private Rule ruleWith(UUID device, RuleCondition... conditions) {
        var all = new ArrayList<RuleCondition>(); all.add(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString())); all.addAll(List.of(conditions));
        return new Rule(UUID.randomUUID(), "validated", true, 1, all, List.of(alert()), Map.of(), null, null, new RuleRuntime(TELEMETRY, null, null));
    }
    private int hysteresisEvidence(UUID rule, String decision) {
        return count("SELECT count(*) FROM rule_matches WHERE rule_id=? AND evaluation_evidence::text LIKE ?", rule, "%\"decision\": \"" + decision + "\"%");
    }

    // ------------------------------------------------------------------ behavior

    @Test void enterAndExitAreExactAndOscillationInsideTheBandDoesNotFlap() {
        UUID device = device();
        UUID rule = hysteresisRule(device, band(RuleOperator.GT, "30", "25"));
        observe(device, 29);  assertEquals(0, matches(rule), "below enter");
        observe(device, 30);  assertEquals(0, matches(rule), "GT: equality with the enter threshold does not enter");
        assertFalse(latched(rule, device));
        observe(device, 31);  assertEquals(1, matches(rule), "above enter");
        assertTrue(latched(rule, device));
        observe(device, 28);  assertEquals(2, matches(rule), "inside the band while latched stays true");
        observe(device, 26);  assertEquals(3, matches(rule));
        observe(device, 25);  assertEquals(3, matches(rule), "GT: equality with the exit threshold exits");
        assertFalse(latched(rule, device));
        observe(device, 28);  assertEquals(3, matches(rule), "inside the band while unlatched stays false: no flapping");
        observe(device, 26);  assertEquals(3, matches(rule));
        observe(device, 31);  assertEquals(4, matches(rule), "re-entering needs the enter threshold again");
        assertTrue(hysteresisEvidence(rule, "ENTER") >= 1);
        assertTrue(hysteresisEvidence(rule, "HOLD_LATCHED") >= 1, "the persisted match evidence says why it held");
    }

    @Test void lessThanDirectionUsesAnExitAboveTheEnter() {
        UUID device = device();
        UUID rule = hysteresisRule(device, band(RuleOperator.LT, "10", "15"));
        observe(device, 12);  assertEquals(0, matches(rule));
        observe(device, 9);   assertEquals(1, matches(rule));
        observe(device, 14);  assertEquals(2, matches(rule), "latched: still below the exit threshold");
        observe(device, 15);  assertEquals(2, matches(rule), "LT: equality with the exit threshold exits");
        assertFalse(latched(rule, device));
    }

    @Test void unknownOrNonGoodEvidenceNeitherSetsNorClearsTheLatch() {
        UUID device = device();
        UUID rule = hysteresisRule(device, band(RuleOperator.GT, "30", "25"));
        observe(device, 31);
        assertTrue(latched(rule, device));
        connectivity.applyObservedStatus(device, DeviceConnectivityStatus.OFFLINE);
        observe(device, 20);                                       // would EXIT if it were trusted, but the device is not GOOD
        assertTrue(latched(rule, device), "evidence that is not GOOD cannot clear the latch");
        assertEquals(1, matches(rule));
        connectivity.applyObservedStatus(device, DeviceConnectivityStatus.ONLINE);
        observe(device, 28);                                       // the latch survived, so inside the band it is still true
        assertEquals(2, matches(rule));

        UUID other = device();
        UUID unlatched = hysteresisRule(other, band(RuleOperator.GT, "30", "25"));
        connectivity.applyObservedStatus(other, DeviceConnectivityStatus.OFFLINE);
        observe(other, 40);                                        // would ENTER if it were trusted
        assertFalse(latched(unlatched, other), "and it cannot set one");
        assertEquals(0, matches(unlatched));
    }

    @Test void pauseResumeStartsAFreshGenerationWithoutASyntheticTransition() {
        UUID device = device();
        UUID rule = hysteresisRule(device, band(RuleOperator.GT, "30", "25"));
        observe(device, 31);
        assertTrue(latched(rule, device));
        lifecycle.transition(rule, "pause", null);
        lifecycle.transition(rule, "resume", null);
        assertFalse(latched(rule, device), "a new execution epoch reads every latch as unlatched");
        int before = matches(rule);
        observe(device, 28);                                       // inside the band: no entry is invented from the old latch
        assertEquals(before, matches(rule));
        assertEquals(1, count("SELECT count(*) FROM rule_hysteresis_state WHERE rule_id=? AND latched", rule), "the stale row is untouched until a definite transition");
        observe(device, 31);
        assertEquals(before + 1, matches(rule));
        assertTrue(latched(rule, device));
    }

    @Test void anOlderObservationNeverOverwritesANewerLatchAcrossInstances() {
        UUID rule = UUID.randomUUID(), scope = UUID.randomUUID();
        Instant t = Instant.parse("2026-10-14T10:00:00Z");
        store.apply(rule, "h1", scope, 3, true, t.plusSeconds(20));            // newer ENTER
        store.apply(rule, "h1", scope, 3, false, t.plusSeconds(10));           // slower instance: an older EXIT arrives late
        assertTrue(store.latched(rule, "h1", scope, 3));
        store.apply(rule, "h1", scope, 3, false, t.plusSeconds(30));           // a genuinely newer EXIT
        assertFalse(store.latched(rule, "h1", scope, 3));
        store.apply(rule, "h1", scope, 4, true, t);                            // a later generation always replaces the older generation
        assertTrue(store.latched(rule, "h1", scope, 4));
        assertFalse(store.latched(rule, "h1", scope, 3), "other generations read as unlatched");
    }

    @Test void hysteresisInteractsWithDurableCooldownWithoutChangingTheLatch() {
        UUID device = device();
        UUID rule = UUID.randomUUID();
        lifecycle.legacySave(new Rule(rule, "hysteresis and cooldown", true, 1, List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()),
            band(RuleOperator.GT, "30", "25")), List.of(alert()), Map.of(), 60, null, new RuleRuntime(TELEMETRY, null, null)), null);
        observe(device, 31);
        assertEquals(1, matches(rule));
        observe(device, 28);                                       // inside the cooldown window: no second match, but the latch keeps tracking the truth
        assertEquals(1, matches(rule));
        assertTrue(latched(rule, device));
        observe(device, 24);
        assertFalse(latched(rule, device), "EXIT is recorded even though the match itself was suppressed");
    }

    // ------------------------------------------------------------------ continuity under AND short-circuit evaluation (F3)

    private void motion(UUID device, double value) {
        now = now.plusSeconds(1);
        telemetry.processTelemetry(new com.nexus.telemetry.api.dto.TelemetryRequest(device, now, "MOTION", (Object) (value != 0), "BOOLEAN", null));
    }
    private UUID deviceWithMotion() {
        UUID device = device();
        sql.update("INSERT INTO device_capabilities(id,device_id,capability_type,direction,unit,metadata) VALUES(?,?,'MOTION','READ','BOOLEAN','{}')", UUID.randomUUID(), device);
        return device;
    }
    private static RuleCondition motionDetected() { return new RuleCondition(RuleField.VALUE, RuleOperator.EQ, "true", CapabilityType.MOTION); }
    private UUID motionAndBand(UUID device, boolean hysteresisFirst) {
        var ordinary = motionDetected();
        var band = band(RuleOperator.GT, "30", "25");
        return typedRule(device, "motion and band", 1, TELEMETRY, hysteresisFirst ? List.of(band, ordinary) : List.of(ordinary, band), List.of(alert()));
    }

    private void exitWhileAnEarlierConditionIsFalseThenReturnInsideTheBand(boolean hysteresisFirst) {
        UUID device = deviceWithMotion();
        UUID rule = motionAndBand(device, hysteresisFirst);
        motion(device, 1);                                         // the ordinary condition is TRUE
        observe(device, 31);                                       // 1. the band ENTERS and the rule matches
        assertEquals(1, matches(rule));
        assertTrue(latched(rule, device));
        motion(device, 0);                                         // 2. the ordinary condition turns FALSE
        assertEquals(1, matches(rule));
        observe(device, 20);                                       // 3. the value crosses the EXIT threshold while the rule is FALSE
        assertFalse(latched(rule, device), "the latch followed its own qualified evidence although an earlier condition was false");
        observe(device, 28);                                       // 4. back INSIDE the band without crossing the entry threshold
        assertFalse(latched(rule, device));
        assertEquals(1, matches(rule));
        motion(device, 1);                                         // 5. the ordinary condition is TRUE again
        assertEquals(1, matches(rule), "6. no match: the band has not genuinely re-entered");
        observe(device, 28);
        assertEquals(1, matches(rule), "still inside the band and unlatched");
        observe(device, 31);                                       // genuine re-entry
        assertEquals(2, matches(rule));
        assertTrue(latched(rule, device));
    }

    @Test void aLatchFollowsItsEvidenceEvenWhileAnEarlierConditionIsFalse() { exitWhileAnEarlierConditionIsFalseThenReturnInsideTheBand(false); }

    @Test void theSameContinuityHoldsWhenTheBandIsListedBeforeTheOrdinaryCondition() { exitWhileAnEarlierConditionIsFalseThenReturnInsideTheBand(true); }

    @Test void anEntryObservedWhileAnEarlierConditionIsFalseIsRememberedAndTheRuleStaysFalseUntilTheOtherConditionHolds() {
        UUID device = deviceWithMotion();
        UUID rule = motionAndBand(device, false);
        motion(device, 0);                                         // ordinary condition FALSE
        observe(device, 31);
        assertEquals(0, matches(rule), "an AND rule with a false condition never matches");
        assertTrue(latched(rule, device), "but the band did enter");
        motion(device, 1);
        assertEquals(1, matches(rule), "the band is still entered and inside its band, so the rule is now TRUE");
    }

    @Test void shortCircuitTrackingDoesNotEvaluateOtherLaterConditionsAndKeepsTheFailingConditionFirst() {
        UUID device = deviceWithMotion();
        UUID rule = motionAndBand(device, false);
        motion(device, 0);
        observe(device, 31);
        assertEquals(0, matches(rule));
        assertEquals(1, count("SELECT count(*) FROM rule_hysteresis_state WHERE rule_id=? AND latched", rule), "only the latch evidence was gathered");
    }

    @Test void nonGoodEvidenceWhileAnEarlierConditionIsFalseStillCannotMoveTheLatch() {
        UUID device = deviceWithMotion();
        UUID rule = motionAndBand(device, false);
        motion(device, 1);
        observe(device, 31);
        assertTrue(latched(rule, device));
        motion(device, 0);
        connectivity.applyObservedStatus(device, DeviceConnectivityStatus.OFFLINE);
        observe(device, 10);                                       // would EXIT, but the device is not GOOD
        assertTrue(latched(rule, device), "non-GOOD evidence neither exits nor enters, even in the tracking evaluation");
    }

    // ------------------------------------------------------------------ validation

    @Test void onlyAnExplicitOrderedSameUnitNumericBandIsAccepted() {
        UUID d = device();
        assertEquals(Set.of(), codes(ruleWith(d, band(RuleOperator.GT, "30", "25"))));
        assertEquals(Set.of(), codes(ruleWith(d, band(RuleOperator.LTE, "10", "15"))));
        assertTrue(codes(ruleWith(d, band(RuleOperator.GT, "30", "30"))).contains("INVALID_HYSTERESIS"), "exit equal to enter is not a band");
        assertTrue(codes(ruleWith(d, band(RuleOperator.GT, "30", "35"))).contains("INVALID_HYSTERESIS"), "GT needs the exit below the enter");
        assertTrue(codes(ruleWith(d, band(RuleOperator.LT, "10", "5"))).contains("INVALID_HYSTERESIS"), "LT needs the exit above the enter");
        assertTrue(codes(ruleWith(d, band(RuleOperator.EQ, "30", "25"))).contains("INVALID_HYSTERESIS"), "equality has no direction");
        assertTrue(codes(ruleWith(d, band(RuleOperator.GT, "30", "x"))).contains("INVALID_HYSTERESIS"));
        assertTrue(codes(ruleWith(d, band(RuleOperator.GT, "30", " "))).contains("INVALID_HYSTERESIS"));
        assertTrue(codes(ruleWith(d, new RuleCondition(RuleField.VALUE, RuleOperator.GT, "30", CapabilityType.TEMPERATURE, null, null, null, null, new HysteresisSpec("25")))).contains("INVALID_HYSTERESIS"),
            "the latch needs a stable conditionId");
        assertTrue(codes(ruleWith(d, new RuleCondition(RuleField.VALUE, RuleOperator.GT, "30", CapabilityType.TEMPERATURE, null, "FAHRENHEIT", "h1", null, new HysteresisSpec("25")))).contains("UNIT_MISMATCH"),
            "no implicit conversion: the condition unit must be the installed effective unit");
        assertTrue(codes(ruleWith(d, new RuleCondition(RuleField.VALUE, RuleOperator.GT, "30", CapabilityType.TEMPERATURE, null, null, "h1",
            new TemporalSpec(TemporalOperator.FOR_DURATION, 60L, null, null, null, null), new HysteresisSpec("25")))).contains("INVALID_HYSTERESIS"), "hysteresis and temporal are separate mechanisms");
        var platformWide = new Rule(UUID.randomUUID(), "no scope", true, 1, List.of(band(RuleOperator.GT, "30", "25")), List.of(alert()), Map.of(), null, null, new RuleRuntime(TELEMETRY, null, null));
        assertTrue(codes(platformWide).contains("INVALID_HYSTERESIS"), "the unit contract can only be verified on an explicit device scope");
        assertTrue(codes(ruleWith(d, new RuleCondition(RuleField.VALUE, RuleOperator.EQ, "ONLINE", null, null, null, "h1", null, new HysteresisSpec("25")))).contains("INVALID_HYSTERESIS"),
            "no capability reference, nothing to compare");
    }
}
