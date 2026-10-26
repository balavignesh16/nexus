package com.nexus.intelligence.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.nexus.device.connectivity.DeviceConnectivityStatus;
import com.nexus.intelligence.evidence.EvidenceBundle;
import com.nexus.intelligence.evidence.Presence;
import com.nexus.intelligence.explain.Explanation;
import com.nexus.intelligence.explain.ExplanationVerifier;
import com.nexus.intelligence.explain.RuleNonMatchExplainer;
import com.nexus.rule.ai.Claim;
import com.nexus.rule.ai.ClaimTier;
import com.nexus.rule.ai.UnsupportedAssertionGuard;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleCondition;
import com.nexus.rule.domain.RuleField;
import com.nexus.rule.domain.RuleOperator;
import com.nexus.rule.domain.RuleRuntime;
import com.nexus.rule.domain.TriggerSubscription;
import com.nexus.rule.domain.TriggerType;
import com.nexus.rule.trigger.TypedTriggerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P6-020..P6-022, P6-034, P6-079..P6-081: why a rule did NOT match, against a real PostgreSQL and the real dry-run engine. The explainer must name the
 * reason the facts support (a value below the threshold, a missing, stale or offline input, a paused rule, an unsubscribed trigger, a cooldown), abstain when
 * the evidence cannot be established, attribute no cause, cite only evidence that resolves, and write nothing.
 */
class NonMatchEvaluationIntegrationTest extends TypedTriggerSupport {
    @Autowired RuleNonMatchExplainer explainer;

    private static final List<TriggerSubscription> TELEMETRY = List.of(new TriggerSubscription(TriggerType.ACCEPTED_TELEMETRY));

    private UUID rule(UUID device, UUID target, Integer cooldown) {
        UUID id = UUID.randomUUID();
        lifecycle.legacySave(new Rule(id, "eval rule", true, 1, List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()), temperatureAbove("10")),
                List.of(ledCommand(target, true)), Map.of(), cooldown, null, new RuleRuntime(TELEMETRY, null, null)), null);
        return id;
    }

    private Map<String, Integer> counts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String t : new String[] {"rule_matches", "action_history", "command_outbox", "digital_twins", "telemetry_history", "rules", "rule_versions", "rule_trigger_facts", "rule_timers", "domain_events"})
            m.put(t, count("SELECT COUNT(*) FROM " + t));
        return m;
    }

    @Test
    void p6_080_p6_081_nonMatchReasonsAreNamedFromTheEvaluatedFactsAndMissingEvidenceMakesTheExplainerAbstain() {
        JsonNode corpus = Eval.corpus("nonmatch.json");
        Eval.Suite suite = new Eval.Suite("nonmatch");
        int scenarios = 0, reasonOk = 0, refs = 0, refsResolved = 0, facts = 0, factsSupported = 0, abstainCases = 0, abstainOk = 0, causeChecks = 0, causeViolations = 0, readOnlyBreaches = 0, verifierProblems = 0;

        for (JsonNode s : corpus.get("scenarios")) {
            scenarios++;
            String id = s.get("id").asText(), kind = s.get("kind").asText();
            now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            UUID device = device(), target = device();
            UUID rule;
            Optional<RuleNonMatchExplainer.Result> result;
            switch (kind) {
                case "BELOW", "ABOVE" -> { rule = rule(device, target, null); ingest(device, s.get("value").asDouble(), now); }
                case "NO_VALUE", "UNKNOWN_DEVICE", "WRONG_TRIGGER" -> rule = rule(device, target, null);
                case "STALE" -> { rule = rule(device, target, null); ingest(device, s.get("value").asDouble(), now); now = now.plusSeconds(3 * 3600); }
                case "OFFLINE" -> { rule = rule(device, target, null); ingest(device, s.get("value").asDouble(), now); connectivity.applyObservedStatus(device, DeviceConnectivityStatus.OFFLINE); }
                case "PAUSED" -> { rule = rule(device, target, null); ingest(device, s.get("value").asDouble(), now); lifecycle.transition(rule, "pause", null); }
                case "COOLDOWN" -> {
                    rule = rule(device, target, 600);
                    ingest(device, s.get("value").asDouble(), now);
                    final UUID cooling = rule;
                    awaitCondition("the match to be recorded", () -> matches(cooling) >= 1);
                }
                case "LEGACY_NO_TRIGGER" -> {
                    rule = UUID.randomUUID();
                    lifecycle.legacySave(new Rule(rule, "legacy rule", true, 1, List.of(new RuleCondition(RuleField.DEVICE_ID, RuleOperator.EQ, device.toString()), temperatureAbove("10")), List.of(alert()), Map.of(), null), null);
                }
                default -> throw new IllegalArgumentException(kind);
            }
            Map<String, Integer> before = counts();
            result = switch (kind) {
                case "UNKNOWN_DEVICE" -> explainer.explain(rule, UUID.randomUUID(), null);
                case "WRONG_TRIGGER" -> explainer.explain(rule, device, TriggerType.CONNECTIVITY_CHANGED);
                default -> explainer.explain(rule, device, null);
            };
            if (!counts().equals(before)) { readOnlyBreaches++; suite.fail("nonmatch.read_only", id, "a table changed while explaining"); }
            Explanation e = result.orElseThrow().explanation();
            EvidenceBundle bundle = result.get().bundle();

            // independent grounding oracle
            for (Claim c : e.claims()) {
                List<String> cited = new ArrayList<>();
                if (c.tier() == ClaimTier.FACT && c.evidenceRef() != null) cited.add(c.evidenceRef());
                cited.addAll(c.supportingEvidenceRefs());
                for (String r : cited) { refs++; if (bundle.resolve(r).isPresent()) refsResolved++; else suite.fail("nonmatch.citation_resolution", id, "unresolvable " + r); }
                if (c.tier() == ClaimTier.FACT) {
                    facts++;
                    var item = bundle.resolve(c.evidenceRef());
                    if (item.isPresent() && item.get().presence() != Presence.NOT_RECORDED && String.valueOf(item.get().value()).equals(String.valueOf(c.assertedValue()))) factsSupported++;
                    else suite.fail("nonmatch.claim_support", id, "FACT not supported by its evidence: " + c.text());
                }
                causeChecks++;
                var violation = UnsupportedAssertionGuard.violation(c.text());
                if (violation != null) { causeViolations++; suite.fail("nonmatch.cause_or_outcome_asserted", id, violation + ": " + c.text()); }
            }
            verifierProblems += ExplanationVerifier.verify(bundle, e).size();

            if (s.path("abstain").asBoolean(false)) {
                abstainCases++;
                if (e.abstained() && e.claims().stream().noneMatch(c -> c.tier() == ClaimTier.FACT) && e.abstentionReason() != null && !e.abstentionReason().isBlank()) abstainOk++;
                else suite.fail("nonmatch.abstention_missing_evidence", id, "the explainer did not abstain: " + e.claims());
                reasonOk++;
                continue;
            }
            boolean ok = !e.abstained();
            for (JsonNode r : s.get("require")) {
                ClaimTier tier = ClaimTier.valueOf(r.get("tier").asText());
                String fragment = r.get("fragment").asText();
                if (e.claims().stream().noneMatch(c -> c.tier() == tier && c.text().contains(fragment))) { ok = false; suite.fail("nonmatch.correct_reason", id, "missing " + tier + " statement containing '" + fragment + "'"); }
            }
            for (JsonNode f : s.get("forbid")) if (e.claims().stream().anyMatch(c -> c.text().contains(f.asText()))) { ok = false; suite.fail("nonmatch.correct_reason", id, "forbidden statement '" + f.asText() + "'"); }
            if (s.has("summaryContains") && !e.summary().contains(s.get("summaryContains").asText())) { ok = false; suite.fail("nonmatch.correct_reason", id, "summary lacks '" + s.get("summaryContains").asText() + "'"); }
            if (ok) reasonOk++;
        }

        suite.add(Eval.Measure.atLeast("nonmatch.correct_reason", "P6-080 correct reason", "scenarios whose explanation names the reason the facts support and makes no forbidden statement (contract: at least 0.98; deterministic fixtures expected 1.0)", reasonOk, scenarios, 0.98, 10, true, "contract threshold"));
        suite.add(Eval.Measure.atLeast("nonmatch.citation_resolution", "P6-079 grounding", "cited references that resolve to the evidence bundle (contract: 100 percent)", refsResolved, refs, 1.0, 20, true, "contract threshold"));
        suite.add(Eval.Measure.atLeast("nonmatch.claim_support", "P6-079 grounding", "FACT claims whose cited item holds the asserted value (contract: at least 0.98)", factsSupported, facts, 0.98, 20, true, "contract threshold"));
        suite.add(Eval.Measure.atLeast("nonmatch.abstention_missing_evidence", "P6-081 abstention", "scenarios with evidence that cannot be established (unknown device, no trigger to evaluate) that abstain with a reason and no FACT (contract: 100 percent)", abstainOk, abstainCases, 1.0, 2, true, "contract threshold"));
        suite.add(Eval.Measure.zero("nonmatch.cause_or_outcome_asserted", "P6-077 no fabricated cause or outcome", "claims asserting actuation, receipt, approval, a fault or a cause", causeViolations, causeChecks, null));
        suite.add(Eval.Measure.zero("nonmatch.verifier_problems", "P6-079 grounding", "problems reported by ExplanationVerifier", verifierProblems, scenarios, null));
        suite.add(Eval.Measure.zero("nonmatch.read_only", "P6-065 no side effects", "scenarios where a table changed while the explanation was produced", readOnlyBreaches, scenarios, null));
        suite.run("scenarios", scenarios);
        try {
            suite.writeAndGate();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        assertThat(scenarios).isEqualTo(12);
    }
}
