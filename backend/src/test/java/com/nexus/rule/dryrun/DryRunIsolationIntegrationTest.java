package com.nexus.rule.dryrun;

import com.nexus.rule.domain.*;
import com.nexus.rule.trigger.TypedTriggerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scenario 64: the simulation clock, repeat, cooldown and hysteresis state of a dry-run are request-local. A hypothetical edge or cooldown claim
 * is never remembered by the next request, simulated time never moves a production window, and a real trigger afterwards behaves exactly as if
 * no dry-run had happened.
 */
class DryRunIsolationIntegrationTest extends TypedTriggerSupport {
    @Autowired DryRunService service;
    @Autowired RuntimeStateReader reader;
    private static final List<TriggerSubscription> TELEMETRY = List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY));

    private DryRunResponse supplied(UUID device, UUID rule, Instant time, double value, DryRunRequest.Supplied seeds) {
        var twin = new DryRunRequest.TwinValue(device, "temperature", "NUMBER", com.fasterxml.jackson.databind.node.DoubleNode.valueOf(value), "CELSIUS", time, time, null);
        var state = new DryRunRequest.Supplied(List.of(twin), List.of(new DryRunRequest.Connectivity(device, "ONLINE")),
            seeds == null ? null : seeds.cooldowns(), seeds == null ? null : seeds.edgeLatches(), seeds == null ? null : seeds.hysteresisLatches(), null);
        return service.run(new DryRunRequest("SUPPLIED_STATE", new DryRunRequest.Scope(device), List.of(new DryRunRequest.VersionSelector(rule, null, null, null)),
            new DryRunRequest.TriggerSpec("ACCEPTED_TELEMETRY", device, null, null, null), time, state));
    }
    private static String outcome(DryRunResponse r) { return r.versions().get(0).outcome(); }
    private static DryRunRequest.Supplied seeds(List<DryRunRequest.Cooldown> cooldowns, List<DryRunRequest.EdgeLatch> edges, List<DryRunRequest.HysteresisLatch> latches) {
        return new DryRunRequest.Supplied(null, null, cooldowns, edges, latches, null);
    }

    @Test void aHypotheticalEdgeClaimIsNeverRememberedAndASeededLatchIsHonoured() {
        UUID device = device(), target = device();
        UUID rule = typedRule(device, "edge", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(target, true)), RepeatPolicy.EDGE_TRUE, null);
        var first = supplied(device, rule, now, 25, null);
        var second = supplied(device, rule, now, 25, null);
        assertEquals("MATCHED", outcome(first));
        assertEquals("MATCHED", outcome(second), "the first request's hypothetical edge claim did not leak into the second");
        assertTrue(first.hypotheticalEffects().stream().anyMatch(e -> e.kind().equals("EDGE_LATCH_CLAIMED")));
        var latched = supplied(device, rule, now, 25, seeds(null, List.of(new DryRunRequest.EdgeLatch(rule, true)), null));
        assertEquals("EDGE_SUPPRESSED", outcome(latched), "an explicitly seeded latch is honoured");
        assertTrue(latched.versions().get(0).repeat().edgeLatched());
        var rearmed = supplied(device, rule, now, 1, seeds(null, List.of(new DryRunRequest.EdgeLatch(rule, true)), null));
        assertEquals("NOT_MATCHED", outcome(rearmed));
        assertTrue(rearmed.hypotheticalEffects().stream().anyMatch(e -> e.kind().equals("EDGE_REARMED")), "definite false evidence would rearm the edge");
        assertEquals(0, count("SELECT count(*) FROM rule_repeat_state WHERE rule_id=?", rule), "no production latch row was created or touched");
    }

    @Test void cooldownWindowsFollowTheSuppliedClockAtTheExactBoundaryAndNeverTheProductionClock() {
        UUID device = device(), target = device();
        UUID rule = UUID.randomUUID();
        lifecycle.legacySave(new Rule(rule, "cooled", true, 1, List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()), temperatureAbove("10")),
            List.of(ledCommand(target, true)), Map.of(), 60, null, new RuleRuntime(TELEMETRY, null, null)), null);
        Instant last = now;
        assertEquals("SKIPPED_IN_COOLDOWN", outcome(supplied(device, rule, last.plusSeconds(59), 25, seeds(List.of(new DryRunRequest.Cooldown(rule, last)), null, null))), "59 s into a 60 s window");
        assertEquals("MATCHED", outcome(supplied(device, rule, last.plusSeconds(60), 25, seeds(List.of(new DryRunRequest.Cooldown(rule, last)), null, null))), "the window ends at last + 60 s");
        assertEquals("MATCHED", outcome(supplied(device, rule, last.plusSeconds(61), 25, null)), "no seed: this rule never matched, and the previous request left nothing behind");
        assertEquals("MATCHED", outcome(supplied(device, rule, last.plusSeconds(61), 25, null)));
        // Simulated time far away in either direction moves no production window.
        supplied(device, rule, now.plusSeconds(86400L * 365), 25, null);
        supplied(device, rule, now.minusSeconds(86400L * 365), 25, null);
        assertTrue(reader.cooldownLast(rule, "").isEmpty(), "production never recorded a match for this rule");
        ingest(device, 25, now);                                            // the first REAL trigger still matches and claims the real cooldown at the real instant
        assertEquals(1, matches(rule));
        assertEquals(now, reader.cooldownLast(rule, "").orElseThrow());
        assertEquals("SKIPPED_IN_COOLDOWN", outcome(service.run(new DryRunRequest("CURRENT_STATE", new DryRunRequest.Scope(device),
            List.of(new DryRunRequest.VersionSelector(rule, null, null, null)), new DryRunRequest.TriggerSpec("ACCEPTED_TELEMETRY", device, null, null, null), null, null))),
            "CURRENT_STATE copies the real cooldown the trigger just claimed");
    }

    @Test void hysteresisLatchesAreSeededAndEvaluatedWithoutTouchingTheProductionLatch() {
        UUID device = device(), target = device();
        UUID rule = UUID.randomUUID();
        lifecycle.legacySave(new Rule(rule, "band", true, 1, List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()),
            new RuleCondition(RuleField.VALUE, RuleOperator.GT, "30", com.nexus.device.capability.CapabilityType.TEMPERATURE, null, null, "h1", null, new HysteresisSpec("25"))),
            List.of(ledCommand(target, true)), Map.of(), null, null, new RuleRuntime(TELEMETRY, null, null)), null);
        var inBandUnlatched = supplied(device, rule, now, 28, null);
        var inBandLatched = supplied(device, rule, now, 28, seeds(null, null, List.of(new DryRunRequest.HysteresisLatch(rule, "h1", true))));
        assertEquals("NOT_MATCHED", outcome(inBandUnlatched), "28 is inside the band but the latch is not set: it must first reach the ENTER threshold");
        assertEquals("MATCHED", outcome(inBandLatched), "a latched band holds until the EXIT threshold is crossed");
        assertEquals("HOLD_LATCHED", inBandLatched.versions().get(0).conditions().get(1).hysteresis().decision());
        var exits = supplied(device, rule, now, 20, seeds(null, null, List.of(new DryRunRequest.HysteresisLatch(rule, "h1", true))));
        assertEquals("NOT_MATCHED", outcome(exits));
        assertTrue(exits.hypotheticalEffects().stream().anyMatch(e -> e.kind().equals("HYSTERESIS_LATCH") && Boolean.FALSE.equals(e.to())));
        var enters = supplied(device, rule, now, 35, null);
        assertTrue(enters.hypotheticalEffects().stream().anyMatch(e -> e.kind().equals("HYSTERESIS_LATCH") && Boolean.TRUE.equals(e.to())));
        assertEquals(0, count("SELECT count(*) FROM rule_hysteresis_state WHERE rule_id=?", rule), "no production latch row was written");
        // The first real value inside the band behaves as if none of this had happened: it does not match.
        ingest(device, 28, now);
        assertEquals(0, matches(rule));
        ingest(device, 35, now.plusSeconds(1));
        assertEquals(1, matches(rule));
    }
}
