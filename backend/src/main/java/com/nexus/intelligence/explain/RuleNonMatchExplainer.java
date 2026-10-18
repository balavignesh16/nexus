package com.nexus.intelligence.explain;

import com.nexus.intelligence.evidence.EvidenceBundle;
import com.nexus.intelligence.evidence.EvidenceItem;
import com.fasterxml.jackson.databind.JsonNode;
import com.nexus.rule.domain.ConditionEvidence;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.TriggerSubscription;
import com.nexus.rule.domain.TriggerType;
import com.nexus.rule.dryrun.DryRunRejection;
import com.nexus.rule.dryrun.DryRunRequest;
import com.nexus.rule.dryrun.DryRunResponse;
import com.nexus.rule.dryrun.DryRunService;
import com.nexus.rule.persistence.RuleVersionStore;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Explains why a stored rule does NOT match for a device RIGHT NOW. The answer is the deterministic dry-run engine's CURRENT_STATE evaluation of the
 * stored version against the persisted Twin state (one read-only repeatable-read transaction; nothing is matched, planned, dispatched or persisted), reported
 * per condition with the actual value, quality, freshness and connectivity. It is labelled hypothetical and stamped with the backend instant: NEXUS does not
 * store a history of non-matches, so this never claims that a particular past event failed to match. A condition whose input is missing, stale, offline or
 * of unknown quality is explained as such, not as a false comparison, and lifecycle, trigger, cooldown and latch reasons are named by their own facts.
 */
@Service
public class RuleNonMatchExplainer {
    public record Result(EvidenceBundle bundle, Explanation explanation) {}

    /** A counterfactual input: the caller assumes this one capability value (and an ONLINE device); everything else is the persisted rule only. */
    public record WhatIf(String capabilityKey, String valueType, JsonNode value, String unit) {}

    private final RuleVersionStore versions;
    private final DryRunService dryRun;
    private final Clock clock;

    public RuleNonMatchExplainer(RuleVersionStore versions, DryRunService dryRun, Clock clock) {
        this.versions = versions;
        this.dryRun = dryRun;
        this.clock = clock;
    }

    /** Empty when the rule does not exist. Otherwise an explanation, possibly an abstention that says why nothing can be said. */
    public Optional<Result> explain(UUID ruleId, UUID deviceId, TriggerType requested) {
        return evaluate(ruleId, deviceId, requested, null);
    }

    /**
     * "What if the value were X?": answered ONLY by the deterministic SUPPLIED_STATE dry-run with the single assumed input (observed and received now, device
     * ONLINE, no cooldown or latch). Every assumption is stated in the result, which is hypothetical; nothing is speculated beyond the engine's decision.
     */
    public Optional<Result> whatIf(UUID ruleId, UUID deviceId, TriggerType requested, WhatIf assumed) {
        if (assumed == null || assumed.capabilityKey() == null || assumed.valueType() == null || assumed.value() == null)
            return versions.get(ruleId).map(v -> abstain("rule " + ruleId, "a what-if needs a capability key, a value type and a value"));
        return evaluate(ruleId, deviceId, requested, assumed);
    }

    private Optional<Result> evaluate(UUID ruleId, UUID deviceId, TriggerType requested, WhatIf assumed) {
        Optional<RuleVersionStore.View> view = versions.get(ruleId);
        if (view.isEmpty()) return Optional.empty();
        String subject = "rule " + ruleId;
        if (deviceId == null) return Optional.of(abstain(subject, "a device is required: the evaluation is scoped to one device"));
        TriggerType trigger = requested != null ? requested : firstSubscribed(view.get().definition());
        if (trigger == null) return Optional.of(abstain(subject, "the rule has no explicit trigger subscription (a legacy event-driven rule); name the trigger to evaluate"));
        DryRunResponse response;
        try {
            var selectors = List.of(new DryRunRequest.VersionSelector(ruleId, null, null, null));
            var spec = new DryRunRequest.TriggerSpec(trigger.name(), deviceId, null, null, null);
            if (assumed == null) {
                response = dryRun.run(new DryRunRequest("CURRENT_STATE", new DryRunRequest.Scope(deviceId), selectors, spec, null, null));
            } else {
                Instant at = clock.instant();
                var supplied = new DryRunRequest.Supplied(
                        List.of(new DryRunRequest.TwinValue(deviceId, assumed.capabilityKey(), assumed.valueType(), assumed.value(), assumed.unit(), at, at, null)),
                        List.of(new DryRunRequest.Connectivity(deviceId, "ONLINE")), List.of(), List.of(), List.of(), List.of());
                response = dryRun.run(new DryRunRequest("SUPPLIED_STATE", new DryRunRequest.Scope(deviceId), selectors, spec, at, supplied));
            }
        } catch (DryRunRejection rejected) {
            return Optional.of(abstain(subject, "the evaluation was refused (" + rejected.code() + ")"));
        }
        if (response.versions().isEmpty()) return Optional.of(abstain(subject, "the evaluation returned no version result"));
        return Optional.of(build(subject, ruleId, trigger, response, response.versions().get(0), assumed));
    }

    private Result abstain(String subject, String reason) {
        return new Result(new EvidenceBundle(), Explanation.abstention(ExplanationKind.RULE_NON_MATCH, subject, reason, clock.instant()));
    }

    private static TriggerType firstSubscribed(Rule rule) {
        if (rule == null || rule.runtime() == null) return null;
        return rule.runtime().triggers().stream().map(TriggerSubscription::type).findFirst().orElse(null);
    }

    private Result build(String subject, UUID ruleId, TriggerType trigger, DryRunResponse r, DryRunResponse.VersionResult v, WhatIf assumed) {
        String p = "rule:" + ruleId + "/v" + v.revision() + "/";
        EvidenceBundle b = new EvidenceBundle();
        b.add(EvidenceItem.present(p + "evaluatedAt", "backend instant of the evaluation", r.simulatedAt().toString()));
        b.add(EvidenceItem.present(p + "trigger", "trigger evaluated", trigger.name()));
        if (assumed != null) b.add(EvidenceItem.present("assumed:" + assumed.capabilityKey(), "ASSUMED input supplied by the caller, not a persisted fact",
                assumed.value().asText() + (assumed.unit() == null ? "" : " " + assumed.unit())));
        b.add(EvidenceItem.present(p + "outcome", "decision outcome", v.outcome()));
        b.add(EvidenceItem.present(p + "matched", "would match now", v.matched()));
        b.add(EvidenceItem.present(p + "triggerEligible", "rule subscribes to the trigger", v.triggerEligible()));
        var own = v.ownership();
        if (own == null) {
            b.add(EvidenceItem.notRecorded(p + "ownership", "stored ownership"));
        } else {
            b.add(EvidenceItem.present(p + "lifecycle", "rule lifecycle", own.lifecycle()));
            b.add(EvidenceItem.present(p + "activeVersion", "this is the active version", own.activeVersion()));
            b.add(EvidenceItem.present(p + "reconciliation", "reconciliation state", own.reconciliation()));
            b.add(EvidenceItem.present(p + "executable", "executable in production", own.executableInProduction()));
        }
        var cd = v.cooldown();
        if (cd != null) {
            b.add(EvidenceItem.presentOrAbsent(p + "cooldown/windowSeconds", "cooldown window seconds", cd.windowSeconds()));
            b.add(EvidenceItem.present(p + "cooldown/cooling", "inside the cooldown window", cd.cooling()));
            b.add(EvidenceItem.presentOrAbsent(p + "cooldown/lastMatchedAt", "last match instant", cd.lastMatchedAt() == null ? null : cd.lastMatchedAt().toString()));
        }
        if (v.repeat() != null) {
            b.add(EvidenceItem.presentOrAbsent(p + "repeat/policy", "repeat policy", v.repeat().policy()));
            b.add(EvidenceItem.present(p + "repeat/edgeLatched", "edge latch held", v.repeat().edgeLatched()));
        }
        List<ConditionEvidence> conditions = v.conditions() == null ? List.of() : v.conditions();
        for (int i = 0; i < conditions.size(); i++) {
            ConditionEvidence c = conditions.get(i);
            String q = p + "cond/" + i + "/";
            b.add(EvidenceItem.present(q + "definition", "condition definition", c.field() + " " + c.operator() + " " + c.expectedValue()));
            b.add(EvidenceItem.present(q + "met", "condition satisfied", c.conditionMet()));
            Object actual = c.actualValue();
            if (actual == null || "MISSING".equals(String.valueOf(actual))) b.add(EvidenceItem.absent(q + "actual", "the persisted state holds no value"));
            else b.add(EvidenceItem.present(q + "actual", "actual value", String.valueOf(actual)));
            b.add(EvidenceItem.presentOrAbsent(q + "quality", "state quality", c.stateQuality()));
            b.add(EvidenceItem.presentOrAbsent(q + "qualityReason", "quality reason", c.qualityReason()));
            b.add(EvidenceItem.presentOrAbsent(q + "connectivity", "device connectivity", c.connectivity()));
            if (c.hysteresis() != null) {
                var h = c.hysteresis();
                b.add(EvidenceItem.present(q + "hysteresis", "hysteresis decision", "enter=" + h.enter() + "|exit=" + h.exit() + "|latchedBefore=" + h.latchedBefore()
                        + "|latchedAfter=" + h.latchedAfter() + "|decision=" + h.decision()));
            }
        }
        List<DryRunResponse.Diagnostic> diagnostics = r.diagnostics() == null ? List.of() : r.diagnostics();
        for (int i = 0; i < diagnostics.size(); i++) b.add(EvidenceItem.present(p + "diagnostic/" + i, "evaluation diagnostic", diagnostics.get(i).code()));

        ExplanationBuilder x = new ExplanationBuilder(b);
        if (assumed == null)
            x.fact("This is an evaluation of the CURRENT persisted state at " + r.simulatedAt() + " for trigger " + trigger + ", not a record of an earlier event.", p + "evaluatedAt");
        else {
            x.fact("This is a HYPOTHETICAL evaluation at " + r.simulatedAt() + " for trigger " + trigger + " with an assumed input; it is not the persisted state.", p + "evaluatedAt");
            x.fact("Assumed input (supplied by the caller, not persisted): " + assumed.capabilityKey() + " = " + assumed.value().asText() + ".", "assumed:" + assumed.capabilityKey());
        }
        x.fact("The decision outcome is " + v.outcome() + ".", p + "outcome");
        List<String> failing = new ArrayList<>();
        if (own != null && !own.executableInProduction()) {
            x.fact("The rule is not executable in production: lifecycle " + own.lifecycle() + ", active version " + own.activeVersion() + ", reconciliation " + own.reconciliation() + ".", p + "executable");
        }
        if (!v.triggerEligible()) x.fact("The rule does not subscribe to the trigger " + trigger + ", so its conditions were not evaluated.", p + "triggerEligible");
        switch (v.outcome()) {
            case "SKIPPED_IN_COOLDOWN", "COOLDOWN_SUPPRESSED" -> {
                if (cd != null) x.fact("The rule is inside its cooldown window (cooling " + cd.cooling() + ").", p + "cooldown/cooling");
            }
            case "EDGE_SUPPRESSED" -> x.fact("The conditions hold, but the edge latch from the last edge is still held.", p + "repeat/edgeLatched");
            case "SCOPE_MISSING", "CONTRACT_DIAGNOSTICS" -> {
                for (int i = 0; i < diagnostics.size(); i++) x.fact("The evaluation reported diagnostic " + diagnostics.get(i).code() + ".", p + "diagnostic/" + i);
            }
            default -> { }
        }
        for (int i = 0; i < conditions.size(); i++) {
            ConditionEvidence c = conditions.get(i);
            String q = p + "cond/" + i + "/";
            String def = c.field() + " " + c.operator() + " " + c.expectedValue();
            boolean missing = c.actualValue() == null || "MISSING".equals(String.valueOf(c.actualValue()));
            if (c.hysteresis() != null) {
                var h = c.hysteresis();
                x.fact("Condition " + i + " uses hysteresis (enter " + h.enter() + ", exit " + h.exit() + "): the latch was " + (h.latchedBefore() ? "held" : "not held")
                        + " before this evaluation, the decision is " + h.decision() + ".", q + "hysteresis");
            }
            if (c.conditionMet()) {
                x.fact("Condition " + i + " (" + def + ") is satisfied.", q + "met");
                continue;
            }
            failing.add(q + "met");
            if (missing) {
                x.fact("Condition " + i + " (" + def + ") is not satisfied: the persisted state holds no value for it.", q + "actual");
            } else if (c.stateQuality() != null && !"GOOD".equals(c.stateQuality())) {
                x.fact("Condition " + i + " (" + def + ") is not satisfied because its input quality is " + c.stateQuality() + (c.qualityReason() == null ? "" : " (" + c.qualityReason() + ")")
                        + ", not because of a comparison.", q + "quality");
            } else {
                x.fact("Condition " + i + " (" + def + ") is not satisfied: the actual value is " + c.actualValue() + ".", q + "actual");
            }
        }
        if (!failing.isEmpty()) x.inference("The rule does not match because " + failing.size() + " condition(s) are not satisfied.", failing.toArray(String[]::new));
        String summary = v.matched() ? "The rule WOULD match now for this device and trigger; this is not a non-match."
                : "The rule does not match now: outcome " + v.outcome() + ".";
        List<String> platform = new ArrayList<>(List.of(DryRunResponse.NOTICE,
                "NEXUS stores matches, not non-matches. This explanation evaluates the stored rule version and is labelled hypothetical."));
        if (assumed != null) platform.add("What-if assumptions: the one input named above, observed and received at the evaluation instant, the device ONLINE, no cooldown, no latch and no other changed state."
                + " The answer is the deterministic engine's decision under those assumptions, nothing more.");
        Explanation e = x.build(ExplanationKind.RULE_NON_MATCH, subject, summary, platform, true, r.simulatedAt());
        var problems = ExplanationVerifier.verify(b, e);
        if (!problems.isEmpty()) throw new IllegalStateException("the rule explanation failed its own grounding check: " + problems);
        return new Result(b, e);
    }
}
