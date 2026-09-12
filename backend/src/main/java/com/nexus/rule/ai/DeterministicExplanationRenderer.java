package com.nexus.rule.ai;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phase 1's sole explanation producer (Tier 4 Phase 1): a pure,
 * deterministic template over RuleMatchExplanationContext. No model call,
 * no randomness, no current-time lookup, no repository access - every
 * claim is derived solely from the fields already present on the supplied
 * context object.
 */
@Component
public class DeterministicExplanationRenderer {

    static final String ACTION_DISCLAIMER =
            "NEXUS recorded the action(s) above as executed by its action pipeline. "
            + "All current action executors are stub implementations and do not perform real external effects; "
            + "this is not evidence that any physical action occurred.";

    static final String CAUSAL_UNCERTAINTY =
            "Whether this observation reflects a genuine physical condition versus a sensor or equipment issue "
            + "cannot be determined from available evidence.";

    static final String DEVICE_UNKNOWN =
            "The originating device could not be determined because the linked domain event is no longer available.";

    public RuleMatchExplanation render(RuleMatchExplanationContext context) {
        List<Claim> claims = new ArrayList<>();

        Object ruleName = context.ruleSnapshot().get("name");
        if (ruleName != null) {
            claims.add(new Claim(ClaimTier.FACT, "Rule '" + ruleName + "' matched.", "ruleSnapshot.name", ruleName, List.of()));
        }

        List<Map<String, Object>> evaluatedConditions = extractEvaluatedConditions(context.evaluationEvidence());
        for (int i = 0; i < evaluatedConditions.size(); i++) {
            Map<String, Object> condition = evaluatedConditions.get(i);
            Object actualValue = condition.get("actualValue");
            Object expectedValue = condition.get("expectedValue");
            Object field = condition.get("field");
            Object operator = condition.get("operator");

            if (actualValue != null) {
                claims.add(new Claim(ClaimTier.FACT,
                        "Observed value was " + actualValue + ".",
                        "evaluationEvidence.evaluatedConditions[" + i + "].actualValue", actualValue, List.of()));
            }
            if (field != null && operator != null && expectedValue != null) {
                claims.add(new Claim(ClaimTier.FACT,
                        "Condition required " + field + " " + operator + " " + expectedValue + ".",
                        "evaluationEvidence.evaluatedConditions[" + i + "].expectedValue", expectedValue, List.of()));
            }
        }

        if (context.deviceId() == null) {
            claims.add(new Claim(ClaimTier.UNKNOWN, DEVICE_UNKNOWN, null, null, List.of()));
        }

        claims.add(new Claim(ClaimTier.UNKNOWN, CAUSAL_UNCERTAINTY, null, null, List.of()));

        String actionNote = null;
        if (!context.actions().isEmpty()) {
            List<ActionSummary> actions = context.actions();
            for (int i = 0; i < actions.size(); i++) {
                ActionSummary action = actions.get(i);
                claims.add(new Claim(ClaimTier.FACT,
                        "NEXUS recorded a " + action.actionType() + " action with status " + action.status() + ".",
                        "actions[" + i + "].status", action.status(), List.of()));
            }
            actionNote = ACTION_DISCLAIMER;
        }

        String summary = buildSummary(context, ruleName);

        return new RuleMatchExplanation(context.matchId(), summary, claims, actionNote, "DETERMINISTIC_FALLBACK");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractEvaluatedConditions(Map<String, Object> evaluationEvidence) {
        Object raw = evaluationEvidence.get("evaluatedConditions");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add((Map<String, Object>) map);
            }
        }
        return result;
    }

    private String buildSummary(RuleMatchExplanationContext context, Object ruleName) {
        String name = ruleName != null ? ruleName.toString() : "an unnamed rule";
        return "Rule '" + name + "' matched at " + context.matchedAt() + " (match " + context.matchId() + "). "
                + "This explanation is deterministically generated from durable NEXUS evidence.";
    }
}
