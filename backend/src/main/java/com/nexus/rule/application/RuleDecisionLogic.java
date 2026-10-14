package com.nexus.rule.application;

import com.nexus.rule.domain.RepeatPolicy;
import com.nexus.rule.domain.Rule;
import com.nexus.rule.domain.RuleEvaluationResult;
import com.nexus.rule.snapshot.RuleEvaluationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * The side-effect-free decision for ONE rule version against ONE frozen evaluation context, shared verbatim by the production matcher and
 * the dry-run engine so the two cannot drift. It evaluates the rule with the production {@link RuleEvaluator}, derives the hysteresis
 * latch transitions, applies the EDGE_TRUE and cooldown gates and says what the caller should do. It never reads or writes any store:
 * runtime inputs (cooling, edge latch) are supplied by the caller and every state change is RETURNED as an effect for the caller to
 * apply (production) or to report as hypothetical (dry-run).
 */
public final class RuleDecisionLogic {
    private RuleDecisionLogic() {}

    public enum Outcome {
        /** Inside the cooldown window and the rule keeps no latch state: not evaluated at all (unchanged production behavior). */
        SKIPPED_IN_COOLDOWN,
        SCOPE_MISSING,
        CONTRACT_DIAGNOSTICS,
        /** Evaluated TRUE, but the EDGE_TRUE latch is still held from the last edge. */
        EDGE_SUPPRESSED,
        /** Evaluated TRUE, but the cooldown window suppresses the match. */
        COOLDOWN_SUPPRESSED,
        NOT_MATCHED,
        MATCHED
    }

    /** A definite hysteresis ENTER/EXIT transition to apply to the rule's latch for one condition and device scope. */
    public record HysteresisEffect(String conditionId, UUID scopeDeviceId, boolean latchedAfter) {}

    public record Decision(Outcome outcome, RuleEvaluationResult result, List<HysteresisEffect> hysteresis, boolean edgeRearm,
            List<Map<String, Object>> diagnostics) {
        public Decision {
            hysteresis = hysteresis == null ? List.of() : List.copyOf(hysteresis);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
        public boolean evaluated() { return result != null; }
        public boolean matched() { return outcome == Outcome.MATCHED; }
    }

    /** A rule that keeps latch state (EDGE_TRUE, hysteresis) is still evaluated inside its cooldown so its latches follow the truth. */
    public static boolean stateful(Rule rule) {
        return rule.repeat() == RepeatPolicy.EDGE_TRUE
            || (rule.conditions() != null && rule.conditions().stream().anyMatch(c -> c != null && c.hysteresis() != null));
    }

    /**
     * @param cooling   whether the rule's cooldown window currently suppresses a match (the caller's source of truth)
     * @param edgeLatch whether the EDGE_TRUE latch is currently held; consulted only for a TRUE EDGE_TRUE evaluation with a pinned scope
     */
    public static Decision decide(RuleEvaluator evaluator, Rule rule, RuleEvaluationContext context, boolean cooling, BooleanSupplier edgeLatch) {
        var captured = context.captured();
        boolean stateful = stateful(rule);
        if (cooling && !stateful) return new Decision(Outcome.SKIPPED_IN_COOLDOWN, null, List.of(), false, List.of());
        if (captured.scopeMissing()) return new Decision(Outcome.SCOPE_MISSING, null, List.of(), false, List.of());
        if (!captured.diagnostics().isEmpty()) return new Decision(Outcome.CONTRACT_DIAGNOSTICS, null, List.of(), false, captured.diagnostics());

        RuleEvaluationResult result = evaluator.evaluate(rule, context.trigger().legacyEvent(), context);
        var identity = rule.executionIdentity();
        UUID scope = captured.scopeDeviceId();

        // Hysteresis latches follow DEFINITE ENTER/EXIT transitions only. Short-circuited conditions have no evidence, so they change nothing.
        var effects = new ArrayList<HysteresisEffect>();
        if (scope != null && identity != null) {
            for (var conditionEvidence : result.evidence()) {
                var band = conditionEvidence.hysteresis();
                if (band != null && band.transition() && band.conditionId() != null) effects.add(new HysteresisEffect(band.conditionId(), scope, band.latchedAfter()));
            }
        }
        boolean rearm = false;
        if (rule.repeat() == RepeatPolicy.EDGE_TRUE && scope != null && identity != null) {
            if (result.matched() && edgeLatch.getAsBoolean()) return new Decision(Outcome.EDGE_SUPPRESSED, result, effects, false, List.of());
            // Only definite false evidence rearms the edge; UNKNOWN, stale or invalid evidence never manufactures one.
            rearm = !result.matched() && definitelyFalse(result);
        }
        if (result.matched() && cooling) return new Decision(Outcome.COOLDOWN_SUPPRESSED, result, effects, rearm, List.of());
        return new Decision(result.matched() ? Outcome.MATCHED : Outcome.NOT_MATCHED, result, effects, rearm, List.of());
    }

    public static boolean definitelyFalse(RuleEvaluationResult result) {
        if (result.evidence().isEmpty()) return false;
        // The failing condition is the FIRST non-met entry (hysteresis tracking evidence may follow it).
        var failing = result.evidence().stream().filter(e -> !e.conditionMet()).findFirst().orElse(null);
        if (failing == null) return false;
        if (failing.eligible() != null) return failing.eligible();
        return !"MISSING".equals(String.valueOf(failing.actualValue()));
    }
}
