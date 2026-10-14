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

    static final String VERSION_UNKNOWN =
            "The exact rule version evaluated is not recorded for this match (it predates version attribution).";

    static final String NO_ARBITRATION =
            "No persisted arbitration record exists for this match, so NEXUS cannot state which of the rule's actions were selected, "
            + "suppressed or authorized. Action history rows alone do not establish a selection.";

    static final String PHYSICAL_UNKNOWN =
            "Whether the device physically acted on the command is not established by NEXUS software evidence.";

    static final String NO_ACK =
            "No device acknowledgement is recorded for this command.";

    static final String NO_VERIFICATION =
            "No qualified reported readback or verification result is recorded for this command, so convergence is not established. "
            + "Neither publication nor an acknowledgement establishes it.";

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

        addTriggerClaim(claims, context);

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
            Object quality = condition.get("quality");
            if (quality != null) {
                Object reason = condition.get("qualityReason");
                claims.add(new Claim(ClaimTier.FACT,
                        "The observation used had quality " + quality + (reason == null ? "" : " (" + reason + ")") + " when it was evaluated.",
                        "evaluationEvidence.evaluatedConditions[" + i + "].quality", quality, List.of()));
            }
        }

        if (context.deviceId() == null) {
            claims.add(new Claim(ClaimTier.UNKNOWN, DEVICE_UNKNOWN, null, null, List.of()));
        }

        claims.add(new Claim(ClaimTier.UNKNOWN, CAUSAL_UNCERTAINTY, null, null, List.of()));

        addVersionClaim(claims, context);
        addArbitrationClaims(claims, context);

        if (!context.actions().isEmpty()) {
            List<ActionSummary> actions = context.actions();
            for (int i = 0; i < actions.size(); i++) {
                ActionSummary action = actions.get(i);
                claims.add(new Claim(ClaimTier.FACT,
                        "NEXUS recorded a " + action.actionType() + " action with status " + action.status() + ".",
                        "actions[" + i + "].status", action.status(), List.of()));
            }
        }
        // Platform-owned and type-aware: a command is a real publication path, the alert and webhook executors are stubs.
        String actionNote = ExecutionTruthfulness.noteFor(ExecutionTruthfulness.typesOf(context));

        String summary = buildSummary(context, ruleName);

        return new RuleMatchExplanation(context.matchId(), summary, claims, actionNote, "DETERMINISTIC_FALLBACK");
    }

    /** The trigger family the match was evaluated for, exactly as recorded in the persisted frozen context (absent for older matches: then nothing is said). */
    @SuppressWarnings("unchecked")
    private static void addTriggerClaim(List<Claim> claims, RuleMatchExplanationContext context) {
        Object frozen = context.evaluationEvidence().get("frozenContext");
        if (!(frozen instanceof Map<?, ?> frozenMap)) return;
        Object trigger = ((Map<String, Object>) frozenMap).get("trigger");
        if (!(trigger instanceof Map<?, ?> triggerMap)) return;
        Object type = ((Map<String, Object>) triggerMap).get("type");
        if (type != null) {
            claims.add(new Claim(ClaimTier.FACT, "The match was evaluated for a " + type + " trigger.",
                    "evaluationEvidence.frozenContext.trigger.type", type, List.of()));
        }
    }

    private static void addVersionClaim(List<Claim> claims, RuleMatchExplanationContext context) {
        VersionAttribution version = context.version();
        if (version == null || version.revision() == null) {
            claims.add(new Claim(ClaimTier.UNKNOWN, VERSION_UNKNOWN, null, null, List.of()));
            return;
        }
        claims.add(new Claim(ClaimTier.FACT,
                "The rule was evaluated at revision " + version.revision()
                        + (version.executionEpoch() == null ? "" : " (execution epoch " + version.executionEpoch() + ")") + ".",
                "version.revision", version.revision(), List.of()));
    }

    /**
     * Only PERSISTED arbitration is described. Selection, suppression, the winner, the acceptance outcome, publication and acknowledgement are
     * each their own claim from their own recorded field, so none is ever implied by another; a missing record is stated as unknown.
     */
    private static void addArbitrationClaims(List<Claim> claims, RuleMatchExplanationContext context) {
        List<CandidateSummary> candidates = context.candidates();
        if (candidates.isEmpty()) {
            claims.add(new Claim(ClaimTier.UNKNOWN, NO_ARBITRATION, null, null, List.of()));
            return;
        }
        boolean acceptedCommand = false;
        for (int i = 0; i < candidates.size(); i++) {
            CandidateSummary c = candidates.get(i);
            String ref = "candidates[" + i + "]";
            claims.add(new Claim(ClaimTier.FACT,
                    "Action " + c.actionType() + " #" + c.ordinal() + " was " + c.disposition() + " by arbitration (" + c.reason() + ").",
                    ref + ".disposition", c.disposition(), List.of()));
            if (c.winnerActionType() != null) {
                claims.add(new Claim(ClaimTier.FACT, "Arbitration preferred a " + c.winnerActionType() + " action over it.",
                        ref + ".winnerActionType", c.winnerActionType(), List.of()));
            }
            if (c.outcome() != null) {
                claims.add(new Claim(ClaimTier.FACT, outcomeText(c), ref + ".outcome", c.outcome(), List.of()));
            } else if ("SELECTED".equals(c.disposition())) {
                claims.add(new Claim(ClaimTier.UNKNOWN, "NEXUS recorded no execution outcome for the selected " + c.actionType() + " action #" + c.ordinal()
                        + ", so it cannot say whether it was carried out.", null, null, List.of()));
            }
            if ("SEND_COMMAND".equals(c.actionType()) && "ACCEPTED".equals(c.outcome())) {
                acceptedCommand = true;
                if ("SUCCESS".equals(c.historyStatus()) && c.publishedAt() != null) {
                    claims.add(new Claim(ClaimTier.FACT, "The command was published to the MQTT broker (action history status SUCCESS).",
                            ref + ".historyStatus", c.historyStatus(), List.of()));
                } else if (c.historyStatus() != null) {
                    claims.add(new Claim(ClaimTier.FACT, "The command was accepted but its action history status is " + c.historyStatus() + "; publication is not established.",
                            ref + ".historyStatus", c.historyStatus(), List.of()));
                }
                if (c.ackStatus() != null) {
                    claims.add(new Claim(ClaimTier.FACT, "A device acknowledgement with status " + c.ackStatus() + " was recorded.", ref + ".ackStatus", c.ackStatus(), List.of()));
                } else {
                    claims.add(new Claim(ClaimTier.UNKNOWN, NO_ACK, null, null, List.of()));
                }
                addVerificationClaim(claims, c, ref);
            }
        }
        if (acceptedCommand) claims.add(new Claim(ClaimTier.UNKNOWN, PHYSICAL_UNKNOWN, null, null, List.of()));
    }

    /** The persisted P2.4 verification of an accepted command. It is never inferred from publication or acknowledgement; absent evidence is UNKNOWN. */
    private static void addVerificationClaim(List<Claim> claims, CandidateSummary c, String ref) {
        String status = c.verificationStatus();
        if (status == null) {
            claims.add(new Claim(ClaimTier.UNKNOWN, NO_VERIFICATION, null, null, List.of()));
            return;
        }
        String reason = c.verificationReason() == null || c.verificationReason().isBlank() ? "" : " (" + c.verificationReason() + ")";
        String text = switch (status) {
            case "CONVERGED" -> "A qualified reported readback matched the desired state (verification status CONVERGED)" + (c.readbackEvidence() == null ? "" : "; readback evidence " + c.readbackEvidence()) + ".";
            case "DIVERGED" -> "The reported readback did not match the desired state (verification status DIVERGED)" + reason + ".";
            case "TIMED_OUT" -> "No matching reported readback arrived before the verification deadline (verification status TIMED_OUT)" + reason + ".";
            case "PENDING" -> "Verification is pending: no qualified reported readback has yet matched the desired state (verification status PENDING).";
            case "UNVERIFIED" -> "No readback verification applies to this command (verification status UNVERIFIED); convergence is not established.";
            default -> "The recorded verification status of this command is " + status + ".";
        };
        claims.add(new Claim(ClaimTier.FACT, text, ref + ".verificationStatus", status, List.of()));
    }

    private static String outcomeText(CandidateSummary c) {
        String reason = c.outcomeReason() == null || c.outcomeReason().isBlank() ? "" : ": " + c.outcomeReason();
        return switch (c.outcome()) {
            case "ACCEPTED" -> "Guarded acceptance accepted the command and recorded a desired-state change. This alone is not evidence of publication, acknowledgement or physical actuation.";
            case "EXECUTED" -> "The executor reported success for this action; see the execution note for what that does and does not establish.";
            case "EXECUTION_FAILED" -> "The executor reported a failure" + reason + ".";
            case "UNSUPPORTED" -> "No executor supported this action" + reason + ".";
            case "IGNORED" -> "The executor ignored this action" + reason + ".";
            case "DENIED" -> "Guarded acceptance denied this action; nothing was written.";
            default -> "The recorded outcome of this action is " + c.outcome() + ".";
        };
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
