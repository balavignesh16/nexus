package com.nexus.rule.dryrun;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.device.binding.DeviceCapabilityBinding;
import com.nexus.device.binding.DeviceCapabilityBindingRepository;
import com.nexus.device.capability.definition.CapabilityDefinitionRepository;
import com.nexus.rule.application.RuleEvaluationSnapshotService;
import com.nexus.rule.application.RuleExclusionGroupService;
import com.nexus.rule.domain.*;
import com.nexus.rule.snapshot.RuleEvaluationSnapshot;
import com.nexus.rule.trigger.TriggerObservation;
import com.nexus.rule.trigger.TypedTriggerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Scenarios 59, 64 and 65: for IDENTICAL frozen inputs the dry-run and the production path decide the same thing. The frozen inputs are
 * literal: the very {@link RuleEvaluationSnapshot} object the production matcher evaluated is captured and re-evaluated by the dry-run engine,
 * with the production cooldown / EDGE_TRUE state read BEFORE the production trigger ran. Predicate outcomes, evidence, candidates, dispositions,
 * reasons, winners and the state changes production actually made are then compared (generated ids normalized to rule + ordinal).
 */
class DryRunEquivalenceIntegrationTest extends TypedTriggerSupport {
    @Autowired DryRunEngine engine;
    @Autowired RuntimeStateReader reader;
    @Autowired RuleExclusionGroupService exclusion;
    @Autowired ObjectMapper json;
    @Autowired DeviceCapabilityBindingRepository bindings;
    @Autowired CapabilityDefinitionRepository definitions;
    @SpyBean RuleEvaluationSnapshotService snapshotService;
    private static final List<TriggerSubscription> TELEMETRY = List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY));

    private record Captured(TriggerObservation trigger, RuleEvaluationSnapshot snapshot) {}
    private final List<Captured> captured = new CopyOnWriteArrayList<>();

    private void capture() {
        captured.clear();
        doAnswer(inv -> {
            var snapshot = (RuleEvaluationSnapshot) inv.callRealMethod();
            captured.add(new Captured(inv.getArgument(0), snapshot));
            return snapshot;
        }).when(snapshotService).acquire(any(), any());
    }

    /**
     * The production runtime gates exactly as they were BEFORE the trigger: cooldown, EDGE_TRUE latch and the generation it was set under are all
     * read here, once, and the returned inputs never touch the database again (so nothing the production trigger changes can leak into them).
     */
    private DryRunEngine.RuntimeInputs preState(Collection<UUID> rules, UUID device) {
        var cooldown = new HashMap<String, Instant>();
        var edge = new HashMap<UUID, Boolean>();
        for (UUID rule : rules) {
            reader.cooldownLast(rule, "").ifPresent(t -> cooldown.put(rule + "|", t));
            long epoch = sql.queryForObject("SELECT execution_epoch FROM rules WHERE rule_id=?", Long.class, rule);
            edge.put(rule, sql.queryForList("SELECT latched,generation FROM rule_repeat_state WHERE rule_id=? AND scope_device_id=?", rule, device).stream()
                .anyMatch(r -> Boolean.TRUE.equals(r.get("latched")) && ((Number) r.get("generation")).longValue() == epoch));
        }
        return new DryRunEngine.RuntimeInputs() {
            public Optional<Instant> lastMatched(Rule rule, String scopeKey) { return Optional.ofNullable(cooldown.get(rule.ruleId() + "|" + scopeKey)); }
            public boolean edgeLatched(Rule rule, UUID scopeDevice) { return edge.getOrDefault(rule.ruleId(), false); }
        };
    }

    private List<DryRunResponse> dryRunOfCaptured(Instant time, DryRunEngine.RuntimeInputs runtime) {
        var out = new ArrayList<DryRunResponse>();
        for (var c : captured) {
            var entries = new ArrayList<DryRunEngine.Entry>();
            int n = 0;
            for (var rule : c.snapshot().rules()) entries.add(new DryRunEngine.Entry("sim-v" + ++n, "STORED_ACTIVE", rule.rule(), c.trigger(), true));
            out.add(engine.evaluate("CURRENT_STATE", time, "BACKEND_CLOCK", c.trigger().deviceId(), entries, c.snapshot(), runtime, List.of()));
        }
        return out;
    }

    // ------------------------------------------------------------------ production side, normalized

    private Map<String, String> productionCandidates(Captured c) {
        var rows = c.trigger().typed()
            ? sql.queryForList("""
                SELECT k.rule_id,k.action_ordinal,k.disposition,k.reason,w.rule_id AS winner_rule,w.action_ordinal AS winner_ordinal
                FROM rule_action_candidates k JOIN rule_action_plans p USING(plan_id) LEFT JOIN rule_action_candidates w ON w.candidate_id=k.winner_candidate_id
                WHERE p.fact_id=?""", c.trigger().factId())
            : sql.queryForList("""
                SELECT k.rule_id,k.action_ordinal,k.disposition,k.reason,w.rule_id AS winner_rule,w.action_ordinal AS winner_ordinal
                FROM rule_action_candidates k JOIN rule_action_plans p USING(plan_id) LEFT JOIN rule_action_candidates w ON w.candidate_id=k.winner_candidate_id
                WHERE p.event_id=?""", c.trigger().sourceId());
        var out = new TreeMap<String, String>();
        for (var r : rows)
            out.put(r.get("rule_id") + "#" + r.get("action_ordinal"), r.get("disposition") + "/" + r.get("reason") + "->" + (r.get("winner_rule") == null ? "-" : r.get("winner_rule") + "#" + r.get("winner_ordinal")));
        return out;
    }

    private Set<UUID> productionMatched(Captured c) {
        return new TreeSet<>(c.trigger().typed()
            ? sql.queryForList("SELECT rule_id FROM rule_matches WHERE trigger_fact_id=?", UUID.class, c.trigger().factId())
            : sql.queryForList("SELECT rule_id FROM rule_matches WHERE event_id=? AND trigger_fact_id IS NULL", UUID.class, c.trigger().sourceId()));
    }

    private JsonNode productionEvidence(Captured c, UUID rule) throws Exception {
        String text = c.trigger().typed()
            ? sql.queryForObject("SELECT evaluation_evidence::text FROM rule_matches WHERE trigger_fact_id=? AND rule_id=?", String.class, c.trigger().factId(), rule)
            : sql.queryForObject("SELECT evaluation_evidence::text FROM rule_matches WHERE event_id=? AND trigger_fact_id IS NULL AND rule_id=?", String.class, c.trigger().sourceId(), rule);
        return json.readTree(text).get("evaluatedConditions");
    }

    /** Canonical text of a JSON value: object keys sorted, numbers without trailing zeros, so jsonb key order and 35 vs 35.0 never matter. */
    private static String canon(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return "null";
        if (node.isNumber()) return node.decimalValue().stripTrailingZeros().toPlainString();
        if (node.isObject()) {
            var keys = new TreeSet<String>();
            node.fieldNames().forEachRemaining(keys::add);
            var out = new StringBuilder("{");
            for (String key : keys) out.append(key).append('=').append(canon(node.get(key))).append(',');
            return out.append('}').toString();
        }
        if (node.isArray()) { var out = new StringBuilder("["); node.forEach(x -> out.append(canon(x)).append(',')); return out.append(']').toString(); }
        return node.asText();
    }
    private String canon(Object value) { return canon(json.valueToTree(value)); }

    private static String tuple(String field, boolean met, String actual, Object quality, Object key, Object unit, Object band, Object connectivity, Object threshold) {
        return String.join("|", field, String.valueOf(met), actual, String.valueOf(quality), String.valueOf(key), String.valueOf(unit),
            String.valueOf(band), String.valueOf(connectivity), String.valueOf(threshold));
    }

    private Map<String, String> dryCandidates(DryRunResponse dry) {
        var byId = new HashMap<String, DryRunResponse.Candidate>();
        dry.candidates().forEach(c -> byId.put(c.simulatedId(), c));
        var out = new TreeMap<String, String>();
        for (var c : dry.candidates()) {
            var winner = c.winnerId() == null ? null : byId.get(c.winnerId());
            out.put(c.ruleId() + "#" + c.ordinal(), c.disposition() + "/" + c.reason() + "->" + (winner == null ? "-" : winner.ruleId() + "#" + winner.ordinal()));
        }
        return out;
    }

    /** The whole comparison for one captured production evaluation. */
    private void assertEquivalent(Captured c, DryRunResponse dry, String what) throws Exception {
        var dryMatched = new TreeSet<UUID>();
        for (var v : dry.versions()) if (v.matched()) dryMatched.add(v.ruleId());
        assertEquals(productionMatched(c), dryMatched, what + ": the same rules matched");
        assertEquals(productionCandidates(c), dryCandidates(dry), what + ": candidates, dispositions, reasons and winners");
        for (var v : dry.versions()) {
            if (!v.matched()) continue;
            var persisted = productionEvidence(c, v.ruleId());
            var expected = new ArrayList<String>();
            for (JsonNode e : persisted) expected.add(tuple(e.get("field").asText(), e.get("conditionMet").asBoolean(), canon(e.get("actualValue")),
                e.path("quality").asText("null"), e.path("capabilityKey").asText("null"), e.path("unit").asText("null"),
                e.path("hysteresis").path("decision").asText("null"), e.path("connectivity").asText("null"), e.path("freshnessThresholdSeconds").asText("null")));
            var actual = new ArrayList<String>();
            for (var e : v.conditions()) actual.add(tuple(e.field().name(), e.conditionMet(), canon(e.actualValue()), e.stateQuality() == null ? "null" : e.stateQuality(),
                e.capabilityKey() == null ? "null" : e.capabilityKey(), e.unit() == null ? "null" : e.unit(), e.hysteresis() == null ? "null" : e.hysteresis().decision(),
                e.connectivity() == null ? "null" : e.connectivity(), e.freshnessThresholdSeconds() == null ? "null" : e.freshnessThresholdSeconds()));
            assertEquals(expected, actual, what + ": evaluated condition evidence of " + v.name());
        }
    }

    // ------------------------------------------------------------------ the scenarios

    @Test void productionAndDryRunDecideIdenticallyForTheSameFrozenInputsAcrossRepeatCooldownHysteresisAndArbitration() throws Exception {
        UUID device = device(), target = device(), other = device(), x1 = device(), x2 = device();
        UUID edge = typedRule(device, "edge", 1, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(target, true)), RepeatPolicy.EDGE_TRUE, null);
        UUID conflictOn = typedRule(device, "conflict on", 2, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(other, true)));
        UUID conflictOff = typedRule(device, "conflict off", 2, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(other, false)));    // equal priority: fail closed
        UUID coalescedA = typedRule(device, "coalesce a", 3, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(device, true)));
        UUID coalescedB = typedRule(device, "coalesce b", 4, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(device, true)));
        UUID cooled = UUID.randomUUID();
        lifecycle.legacySave(new Rule(cooled, "cooled", true, 5, List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()), temperatureAbove("10")),
            List.of(alert()), Map.of(), 600, null, new RuleRuntime(TELEMETRY, null, null)), null);
        UUID hysteresis = UUID.randomUUID();
        lifecycle.legacySave(new Rule(hysteresis, "hysteresis", true, 6, List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()),
            new RuleCondition(RuleField.VALUE, RuleOperator.GT, "30", com.nexus.device.capability.CapabilityType.TEMPERATURE, null, null, "h1", null, new HysteresisSpec("25"))),
            List.of(alert()), Map.of(), null, null, new RuleRuntime(TELEMETRY, null, null)), null);
        UUID legacy = UUID.randomUUID();
        lifecycle.legacySave(new Rule(legacy, "legacy adapter", true, 7, List.of(new RuleCondition(RuleField.EVENT_TYPE, RuleOperator.EQ, "TWIN_UPDATED"),
            new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()), temperatureAbove("10")), List.of(alert()), Map.of(), null), null);
        var exclA = typedRule(device, "exclusion a", 8, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(x1, false)));
        var exclB = typedRule(device, "exclusion b", 8, TELEMETRY, List.of(temperatureAbove("10")), List.of(ledCommand(x2, false)));
        var g1 = exclusion.create("eq g1 " + UUID.randomUUID());
        var g2 = exclusion.create("eq g2 " + UUID.randomUUID());
        exclusion.setMember(g1.groupId(), exclA, 1); exclusion.setMember(g1.groupId(), edge, 2);              // overlapping groups sharing 'edge'
        exclusion.setMember(g2.groupId(), exclB, 1); exclusion.setMember(g2.groupId(), exclA, 2);
        var all = List.of(edge, conflictOn, conflictOff, coalescedA, coalescedB, cooled, hysteresis, legacy, exclA, exclB);

        // Round 1: everything fires for the first time.  Round 2: edge latched, cooldown running, hysteresis latched.  Round 3: the value leaves the
        // hysteresis band (the edge stays latched).  Round 4: it falls below every threshold - a DEFINITE false rearms the edge.  Round 5: it fires again.
        double[] values = {35, 35, 20, 5, 35};
        int[] offsets = {0, 10, 20, 30, 40};
        for (int round = 0; round < values.length; round++) {
            now = now.plusSeconds(offsets[round] - (round == 0 ? 0 : offsets[round - 1]));
            var runtime = preState(all, device);
            capture();
            ingest(device, values[round], now);
            assertFalse(captured.isEmpty(), "the production matcher acquired its frozen snapshot");
            var dryRuns = dryRunOfCaptured(now, runtime);
            for (int i = 0; i < captured.size(); i++) assertEquivalent(captured.get(i), dryRuns.get(i), "round " + (round + 1) + " (" + captured.get(i).trigger().type() + ")");
            if (round == 0) {
                var typed = dryRuns.stream().filter(d -> d.versions().stream().anyMatch(v -> v.name().equals("edge"))).findFirst().orElseThrow();
                assertTrue(typed.candidates().stream().anyMatch(cand -> cand.disposition().startsWith("SUPPRESSED_EXCLUSION")), "the overlapping exclusion groups really suppressed something");
                assertTrue(typed.candidates().stream().anyMatch(cand -> cand.disposition().equals("SUPPRESSED_CONFLICT")), "the equal-priority conflict really failed closed");
                assertTrue(typed.candidates().stream().anyMatch(cand -> cand.disposition().equals("COALESCED")), "identical commands really coalesced");
            }
            if (round == 1) {
                var typed = dryRuns.stream().filter(d -> d.versions().stream().anyMatch(v -> v.name().equals("edge"))).findFirst().orElseThrow();
                var outcomes = new HashMap<String, String>();
                typed.versions().forEach(v -> outcomes.put(v.name(), v.outcome()));
                assertEquals("EDGE_SUPPRESSED", outcomes.get("edge"), "the dry-run saw the edge latch production set in round 1");
                assertEquals("SKIPPED_IN_COOLDOWN", outcomes.get("cooled"));
            }
            if (round == 3) {
                var effects = dryRuns.stream().flatMap(d -> d.hypotheticalEffects().stream()).toList();
                assertTrue(effects.stream().anyMatch(e -> e.kind().equals("EDGE_REARMED") && e.ruleId().equals(edge)), "the dry-run reports the edge rearm production performs: " + effects);
                assertFalse(sql.queryForObject("SELECT latched FROM rule_repeat_state WHERE rule_id=? AND scope_device_id=?", Boolean.class, edge, device), "production really rearmed the edge");
            }
            if (round == 4) {
                var typed = dryRuns.stream().filter(d -> d.versions().stream().anyMatch(v -> v.name().equals("edge"))).findFirst().orElseThrow();
                assertEquals("MATCHED", typed.versions().stream().filter(v -> v.name().equals("edge")).findFirst().orElseThrow().outcome(), "after the rearm the edge fires again");
                assertTrue(typed.hypotheticalEffects().stream().anyMatch(e -> e.kind().equals("EDGE_LATCH_CLAIMED") && e.ruleId().equals(edge)));
                assertEquals(2, matches(edge), "production fired the edge rule again");
            }
            // The state changes production really made are exactly the hypothetical effects the dry-run reported.
            for (var dry : dryRuns) for (var effect : dry.hypotheticalEffects()) {
                switch (effect.kind()) {
                    case "HYSTERESIS_LATCH" -> assertEquals(effect.to(), sql.queryForObject("SELECT latched FROM rule_hysteresis_state WHERE rule_id=? AND condition_id='h1'", Boolean.class, effect.ruleId()),
                        "round " + (round + 1) + ": production latch after the trigger");
                    case "EDGE_LATCH_CLAIMED" -> assertTrue(sql.queryForObject("SELECT latched FROM rule_repeat_state WHERE rule_id=? AND scope_device_id=?", Boolean.class, effect.ruleId(), device));
                    case "EDGE_REARMED" -> assertFalse(sql.queryForObject("SELECT latched FROM rule_repeat_state WHERE rule_id=? AND scope_device_id=?", Boolean.class, effect.ruleId(), device));
                    case "COOLDOWN_ADVANCED" -> assertEquals(now, reader.cooldownLast(effect.ruleId(), "").orElseThrow(), "production cooldown advanced to the same instant");
                    default -> fail("unexpected effect " + effect);
                }
            }
        }
    }

    @Test void relayChannelsAndTheirConflictsDecideIdenticallyLiveAndDry() throws Exception {
        UUID trigger = device(), relayDevice = device(), binding = UUID.randomUUID();
        bindings.save(new DeviceCapabilityBinding(binding, relayDevice, definitions.findByKey("relay").orElseThrow(), null, true, "BOOLEAN", Map.of("channels", List.of(1, 2)),
            Map.of("commandCapability", "RELAY"), Instant.now()));
        java.util.function.BiFunction<Integer, Boolean, RuleAction> channel = (n, on) -> new RuleAction("SEND_COMMAND", Map.of("deviceId", relayDevice.toString(), "capabilityKey", "relay",
            "command", "SET_STATE", "value", Map.of("channel", n, "state", on)));
        UUID c1On = UUID.randomUUID(), c1Off = UUID.randomUUID(), c2 = UUID.randomUUID(), c2Lower = UUID.randomUUID();
        for (var spec : List.of(new Object[]{c1On, 1, channel.apply(1, true)}, new Object[]{c1Off, 1, channel.apply(1, false)}, new Object[]{c2, 1, channel.apply(2, true)},
                new Object[]{c2Lower, 2, channel.apply(2, false)}))
            lifecycle.legacySave(new Rule((UUID) spec[0], "relay " + spec[0], true, (Integer) spec[1], List.of(temperatureAbove("10")), List.of((RuleAction) spec[2]), Map.of(), null, null,
                new RuleRuntime(TELEMETRY, null, null)), null);
        capture();
        ingest(trigger, 40, now);
        var dryRuns = dryRunOfCaptured(now, preState(List.of(c1On, c1Off, c2, c2Lower), trigger));
        for (int i = 0; i < captured.size(); i++) assertEquivalent(captured.get(i), dryRuns.get(i), "relay (" + captured.get(i).trigger().type() + ")");
        var typed = dryRuns.stream().filter(d -> d.candidates().stream().anyMatch(c -> c.targetKey() != null && c.targetKey().contains("channel"))).findFirst().orElseThrow();
        var byRule = new HashMap<UUID, String>();
        typed.candidates().forEach(c -> byRule.put(c.ruleId(), c.disposition()));
        assertEquals("SUPPRESSED_CONFLICT", byRule.get(c1On), "equal priority on channel 1 fails closed for that channel");
        assertEquals("SUPPRESSED_CONFLICT", byRule.get(c1Off));
        assertEquals("SELECTED", byRule.get(c2), "channel 2 is an independent target and its higher priority wins");
        assertEquals("SUPPRESSED_PRIORITY", byRule.get(c2Lower));
    }

    @Autowired com.nexus.rule.timer.RuleTimerService timers;

    @Test void aTemporalDueMatchDecidesIdenticallyLiveAndDryFromTheSamePersistedTemporalEvidence() throws Exception {
        UUID device = device();
        UUID rule = temporalRule(device, "equivalence duration", new TemporalSpec(TemporalOperator.FOR_DURATION, 60L, null, null, null, null), true, RuleOperator.GT, "10", List.of(alert()));
        Instant t0 = now;
        ingest(device, 25, t0);                                   // production arms the window from telemetry
        assertEquals("ARMED", phase(rule));
        now = t0.plusSeconds(60);
        capture();
        assertEquals(1, timers.processDue(10), "production completes the window and routes the TEMPORAL_DUE fact");
        var due = captured.stream().filter(c -> c.trigger().type() == TriggerType.TEMPORAL_DUE).toList();
        assertEquals(1, due.size(), "the production matcher evaluated exactly one TEMPORAL_DUE snapshot");
        assertEquals("SATISFIED", phase(rule));
        var pair = due.getFirst();
        // The SAME persisted temporal runtime rows production just used are inside that snapshot: nothing is reconstructed for the dry-run.
        assertFalse(pair.snapshot().temporal().isEmpty());
        var dry = engine.evaluate("CURRENT_STATE", now, "BACKEND_CLOCK", device,
            List.of(new DryRunEngine.Entry("sim-v1", "STORED_ACTIVE", pair.snapshot().rules().getFirst().rule(), pair.trigger(), true)), pair.snapshot(), preState(List.of(rule), device), List.of());
        assertEquivalent(pair, dry, "temporal FOR_DURATION");
        assertEquals("MATCHED", dry.versions().getFirst().outcome());
        var temporal = dry.versions().getFirst().conditions().stream().filter(c -> c.conditionMet() && String.valueOf(c.actualValue()).contains("SATISFIED")).findFirst();
        assertTrue(temporal.isPresent(), "the temporal condition was decided from the persisted SATISFIED occurrence: " + dry.versions().getFirst().conditions());

        // A trigger that does NOT address the persisted occurrence decides the same way live and dry: not met (a different sequence).
        var staleAttributes = new LinkedHashMap<>(pair.trigger().attributes());
        staleAttributes.put("sequence", 999L);
        var stale = new TriggerObservation(pair.trigger().type(), pair.trigger().sourceId(), pair.trigger().factId(), pair.trigger().legacyEvent(), pair.trigger().deviceId(),
            pair.trigger().occurredAt(), pair.trigger().recordedAt(), pair.trigger().sourceRef(), staleAttributes, pair.trigger().correlationId(), null, 0, List.of());
        var staleDry = engine.evaluate("CURRENT_STATE", now, "BACKEND_CLOCK", device,
            List.of(new DryRunEngine.Entry("sim-v1", "STORED_ACTIVE", pair.snapshot().rules().getFirst().rule(), stale, true)), pair.snapshot(), preState(List.of(rule), device), List.of());
        assertEquals("NOT_MATCHED", staleDry.versions().getFirst().outcome());
        assertTrue(staleDry.candidates().isEmpty());
    }
}
