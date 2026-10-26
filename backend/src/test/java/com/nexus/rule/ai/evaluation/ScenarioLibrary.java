package com.nexus.rule.ai.evaluation;

import com.nexus.rule.ai.ActionSummary;
import com.nexus.rule.ai.DomainEventSummary;
import com.nexus.rule.ai.RuleMatchExplanationContext;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Tier 4 Phase 2D evaluation scenario fixtures (test-only, no production
 * code). Every scenario builds a RuleMatchExplanationContext exactly the
 * shape RuleMatchExplanationContextBuilder would produce - no second
 * domain model - and every EvaluationScenario.ExpectedOutcome field that
 * CAN be derived from that same context (device-unknown, action-note,
 * matchId) is derived, never independently retyped, so ground truth cannot
 * drift out of sync with the fixture it describes.
 *
 * All identifiers are deterministic (UUID.nameUUIDFromBytes over a fixed
 * seed string), never UUID.randomUUID() - the whole scenario set must
 * reproduce identically on every run, with no network, no database, no
 * wall-clock dependency, and no live AI provider.
 */
public final class ScenarioLibrary {

    private static final Instant FIXED_TIME = Instant.parse("2026-01-01T00:00:00Z");
    private static final String PLACEHOLDER_MATCH_ID = "00000000-0000-0000-0000-000000000000";

    private ScenarioLibrary() {
    }

    // ---------- record ----------

    /**
     * One Phase 2D evaluation case. expected is derived from context
     * wherever the fact is knowable from context alone; expectedGeneratedBy
     * is supplied explicitly since it also depends on aiEnabled and the
     * chosen fake AI response, which context alone cannot determine.
     */
    public record EvaluationScenario(
            String id,
            String description,
            RuleMatchExplanationContext context,
            boolean aiEnabled,
            Optional<String> fakeAiRawResponse,
            ExpectedOutcome expected
    ) {
        public record ExpectedOutcome(
                UUID expectedMatchId,
                String expectedGeneratedBy,
                boolean expectMandatoryDeviceUnknownClaim,
                boolean expectActionNoteEnforced
        ) {
            public static ExpectedOutcome derivedFrom(RuleMatchExplanationContext context, String expectedGeneratedBy) {
                return new ExpectedOutcome(
                        context.matchId(),
                        expectedGeneratedBy,
                        context.deviceId() == null,
                        !context.actions().isEmpty());
            }
        }
    }

    // ---------- deterministic id helpers ----------

    private static UUID uuid(String seed) {
        return UUID.nameUUIDFromBytes(("nexus-phase2d-" + seed).getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> orderedMap(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    // ---------- fixture builders ----------

    private static Map<String, Object> ruleSnapshot(String seed, String ruleName, String expectedValue) {
        return orderedMap(
                "ruleId", uuid(seed + "-rule").toString(),
                "name", ruleName,
                "enabled", true,
                "priority", 1,
                "conditions", List.of(orderedMap("field", "VALUE", "operator", "GT", "expectedValue", expectedValue)),
                "actions", List.of(orderedMap("actionType", "CREATE_ALERT", "parameters", Map.of())),
                "metadata", Map.of());
    }

    private static Map<String, Object> singleConditionEvidence(String field, String operator, String expectedValue, Object actualValue) {
        return orderedMap(
                "outcome", "matched",
                "evaluatedConditions", List.of(
                        orderedMap("field", field, "operator", operator, "expectedValue", expectedValue,
                                "actualValue", actualValue, "conditionMet", true)));
    }

    private static Map<String, Object> multiConditionEvidence(List<Map<String, Object>> conditions) {
        return orderedMap("outcome", "matched", "evaluatedConditions", conditions);
    }

    private static Map<String, Object> condition(String field, String operator, String expectedValue, Object actualValue) {
        return orderedMap("field", field, "operator", operator, "expectedValue", expectedValue,
                "actualValue", actualValue, "conditionMet", true);
    }

    private static RuleMatchExplanationContext context(
            String seed, String ruleName, String expectedValue, Map<String, Object> evidence,
            UUID deviceId, DomainEventSummary domainEvent, List<ActionSummary> actions) {
        UUID matchId = uuid(seed + "-match");
        UUID ruleId = uuid(seed + "-rule");
        UUID eventId = uuid(seed + "-event");
        return new RuleMatchExplanationContext(
                matchId, ruleId, ruleSnapshot(seed, ruleName, expectedValue), eventId,
                "corr-" + seed, evidence, FIXED_TIME, "All conditions met",
                deviceId, domainEvent, actions);
    }

    private static DomainEventSummary domainEvent(String seed, UUID deviceId) {
        return new DomainEventSummary(uuid(seed + "-event"), deviceId, "TWIN_UPDATED", FIXED_TIME, "test", "INFO");
    }

    private static ActionSummary action(String seed, String actionType, String status) {
        return new ActionSummary(uuid(seed + "-request"), actionType, status, FIXED_TIME, "recorded (stub)");
    }

    private static String factClaimJson(String text, String evidenceRef, String assertedValueJsonLiteral) {
        return "{\"tier\":\"FACT\",\"text\":\"" + text + "\",\"evidenceRef\":\"" + evidenceRef + "\","
                + "\"assertedValue\":" + assertedValueJsonLiteral + ",\"supportingEvidenceRefs\":[]}";
    }

    private static String inferenceClaimJson(String text, List<String> supportingRefs) {
        String refs = String.join(",", supportingRefs.stream().map(r -> "\"" + r + "\"").toList());
        // The "assertedValue" JSON key must be ABSENT entirely for a non-FACT
        // claim - AiCandidateParser rejects it if the key is merely PRESENT,
        // even with a null value (see AiCandidateParser.hasAssertedValueKey).
        return "{\"tier\":\"INFERENCE\",\"text\":\"" + text + "\",\"evidenceRef\":null,"
                + "\"supportingEvidenceRefs\":[" + refs + "]}";
    }

    private static String candidateEnvelope(String... claimsJson) {
        return "{\"matchId\":\"" + PLACEHOLDER_MATCH_ID + "\",\"summary\":\"s\",\"claims\":["
                + String.join(",", claimsJson) + "]}";
    }

    // ---------- scenarios ----------

    public static List<EvaluationScenario> allScenarios() {
        List<EvaluationScenario> scenarios = new ArrayList<>();

        // 1. simple numeric VALUE > threshold
        {
            String seed = "01-numeric-gt-threshold";
            UUID deviceId = uuid(seed + "-device");
            RuleMatchExplanationContext ctx = context(seed, "Numeric GT Rule", "30.0",
                    singleConditionEvidence("VALUE", "GT", "30.0", 34.7),
                    deviceId, domainEvent(seed, deviceId), List.of(action(seed, "CREATE_ALERT", "SUCCESS")));
            String raw = candidateEnvelope(factClaimJson("Observed value was 34.7.",
                    "evaluationEvidence.evaluatedConditions[0].actualValue", "34.7"));
            scenarios.add(new EvaluationScenario(seed, "Simple numeric VALUE > threshold, correct AI FACT claim",
                    ctx, true, Optional.of(raw), EvaluationScenario.ExpectedOutcome.derivedFrom(ctx, "AI")));
        }

        // 2. numeric equality
        {
            String seed = "02-numeric-equality";
            UUID deviceId = uuid(seed + "-device");
            RuleMatchExplanationContext ctx = context(seed, "Numeric EQ Rule", "42",
                    singleConditionEvidence("VALUE", "EQ", "42", 42),
                    deviceId, domainEvent(seed, deviceId), List.of());
            String raw = candidateEnvelope(factClaimJson("Observed value was 42.",
                    "evaluationEvidence.evaluatedConditions[0].actualValue", "42"));
            scenarios.add(new EvaluationScenario(seed, "Numeric equality condition, correct AI FACT claim",
                    ctx, true, Optional.of(raw), EvaluationScenario.ExpectedOutcome.derivedFrom(ctx, "AI")));
        }

        // 3. multiple conditions
        {
            String seed = "03-multiple-conditions";
            UUID deviceId = uuid(seed + "-device");
            RuleMatchExplanationContext ctx = context(seed, "Multi Condition Rule", "30.0",
                    multiConditionEvidence(List.of(
                            condition("VALUE", "GT", "30.0", 34.7),
                            condition("STATUS", "EQ", "ACTIVE", "ACTIVE"))),
                    deviceId, domainEvent(seed, deviceId), List.of());
            String raw = candidateEnvelope(
                    factClaimJson("Observed value was 34.7.", "evaluationEvidence.evaluatedConditions[0].actualValue", "34.7"),
                    factClaimJson("Status was ACTIVE.", "evaluationEvidence.evaluatedConditions[1].actualValue", "\"ACTIVE\""));
            scenarios.add(new EvaluationScenario(seed, "Multiple evaluated conditions, both AI FACT claims correct",
                    ctx, true, Optional.of(raw), EvaluationScenario.ExpectedOutcome.derivedFrom(ctx, "AI")));
        }

        // 4. boolean condition
        {
            String seed = "04-boolean-condition";
            UUID deviceId = uuid(seed + "-device");
            RuleMatchExplanationContext ctx = context(seed, "Boolean Condition Rule", "true",
                    singleConditionEvidence("ALARM_ACTIVE", "EQ", "true", true),
                    deviceId, domainEvent(seed, deviceId), List.of());
            String raw = candidateEnvelope(factClaimJson("The alarm was active.",
                    "evaluationEvidence.evaluatedConditions[0].actualValue", "true"));
            scenarios.add(new EvaluationScenario(seed, "Boolean condition, correct AI FACT claim",
                    ctx, true, Optional.of(raw), EvaluationScenario.ExpectedOutcome.derivedFrom(ctx, "AI")));
        }

        // 5. missing device identity (deterministic path)
        {
            String seed = "05-missing-device-identity";
            RuleMatchExplanationContext ctx = context(seed, "Missing Device Rule", "30.0",
                    singleConditionEvidence("VALUE", "GT", "30.0", 34.7),
                    null, null, List.of());
            scenarios.add(new EvaluationScenario(seed, "Missing device identity, deterministic fallback carries mandatory DEVICE_UNKNOWN claim",
                    ctx, false, Optional.empty(), EvaluationScenario.ExpectedOutcome.derivedFrom(ctx, "DETERMINISTIC_FALLBACK")));
        }

        // 6. missing DomainEvent (AI-success path, proving mandatory claim still appended)
        {
            String seed = "06-missing-domain-event";
            RuleMatchExplanationContext ctx = context(seed, "Missing Event Rule", "30.0",
                    singleConditionEvidence("VALUE", "GT", "30.0", 34.7),
                    null, null, List.of());
            String raw = candidateEnvelope(factClaimJson("Observed value was 34.7.",
                    "evaluationEvidence.evaluatedConditions[0].actualValue", "34.7"));
            scenarios.add(new EvaluationScenario(seed, "Missing DomainEvent, AI success still carries mandatory DEVICE_UNKNOWN claim",
                    ctx, true, Optional.of(raw), EvaluationScenario.ExpectedOutcome.derivedFrom(ctx, "AI")));
        }

        // 7. action exists with SUCCESS
        {
            String seed = "07-action-success";
            UUID deviceId = uuid(seed + "-device");
            RuleMatchExplanationContext ctx = context(seed, "Action Success Rule", "30.0",
                    singleConditionEvidence("VALUE", "GT", "30.0", 34.7),
                    deviceId, domainEvent(seed, deviceId), List.of(action(seed, "CREATE_ALERT", "SUCCESS")));
            String raw = candidateEnvelope(factClaimJson("Observed value was 34.7.",
                    "evaluationEvidence.evaluatedConditions[0].actualValue", "34.7"));
            scenarios.add(new EvaluationScenario(seed, "Action recorded SUCCESS, AI success, actionNote enforced",
                    ctx, true, Optional.of(raw), EvaluationScenario.ExpectedOutcome.derivedFrom(ctx, "AI")));
        }

        // 8. action exists with FAILURE (deterministic path)
        {
            String seed = "08-action-failure";
            UUID deviceId = uuid(seed + "-device");
            RuleMatchExplanationContext ctx = context(seed, "Action Failure Rule", "30.0",
                    singleConditionEvidence("VALUE", "GT", "30.0", 34.7),
                    deviceId, domainEvent(seed, deviceId), List.of(action(seed, "CREATE_ALERT", "FAILURE")));
            scenarios.add(new EvaluationScenario(seed, "Action recorded FAILURE, deterministic fallback, actionNote enforced",
                    ctx, false, Optional.empty(), EvaluationScenario.ExpectedOutcome.derivedFrom(ctx, "DETERMINISTIC_FALLBACK")));
        }

        // 9. no action
        {
            String seed = "09-no-action";
            UUID deviceId = uuid(seed + "-device");
            RuleMatchExplanationContext ctx = context(seed, "No Action Rule", "30.0",
                    singleConditionEvidence("VALUE", "GT", "30.0", 34.7),
                    deviceId, domainEvent(seed, deviceId), List.of());
            String raw = candidateEnvelope(factClaimJson("Observed value was 34.7.",
                    "evaluationEvidence.evaluatedConditions[0].actualValue", "34.7"));
            scenarios.add(new EvaluationScenario(seed, "No action recorded, AI success, actionNote absent",
                    ctx, true, Optional.of(raw), EvaluationScenario.ExpectedOutcome.derivedFrom(ctx, "AI")));
        }

        // 10. multiple evaluated conditions (deterministic renderer path)
        {
            String seed = "10-multiple-conditions-deterministic";
            UUID deviceId = uuid(seed + "-device");
            RuleMatchExplanationContext ctx = context(seed, "Multi Condition Deterministic Rule", "30.0",
                    multiConditionEvidence(List.of(
                            condition("VALUE", "GT", "30.0", 34.7),
                            condition("STATUS", "EQ", "ACTIVE", "ACTIVE"))),
                    deviceId, domainEvent(seed, deviceId), List.of());
            scenarios.add(new EvaluationScenario(seed, "Multiple evaluated conditions through the deterministic renderer",
                    ctx, false, Optional.empty(), EvaluationScenario.ExpectedOutcome.derivedFrom(ctx, "DETERMINISTIC_FALLBACK")));
        }

        // 11. null observed value
        {
            String seed = "11-null-observed-value";
            UUID deviceId = uuid(seed + "-device");
            RuleMatchExplanationContext ctx = context(seed, "Null Observed Value Rule", "30.0",
                    singleConditionEvidence("VALUE", "GT", "30.0", null),
                    deviceId, domainEvent(seed, deviceId), List.of());
            String raw = candidateEnvelope(factClaimJson("The observed value is not currently available.",
                    "evaluationEvidence.evaluatedConditions[0].actualValue", "null"));
            scenarios.add(new EvaluationScenario(seed, "Null observed value: evidenceRef resolves to Java null, assertedValue null matches",
                    ctx, true, Optional.of(raw), EvaluationScenario.ExpectedOutcome.derivedFrom(ctx, "AI")));
        }

        // 12. malformed provider JSON
        {
            String seed = "12-malformed-json";
            UUID deviceId = uuid(seed + "-device");
            RuleMatchExplanationContext ctx = context(seed, "Malformed JSON Rule", "30.0",
                    singleConditionEvidence("VALUE", "GT", "30.0", 34.7),
                    deviceId, domainEvent(seed, deviceId), List.of());
            scenarios.add(new EvaluationScenario(seed, "Malformed provider JSON falls back to deterministic",
                    ctx, true, Optional.of("{ not valid json ]]"), EvaluationScenario.ExpectedOutcome.derivedFrom(ctx, "DETERMINISTIC_FALLBACK")));
        }

        // 13. schema-invalid provider output
        {
            String seed = "13-schema-invalid-output";
            UUID deviceId = uuid(seed + "-device");
            RuleMatchExplanationContext ctx = context(seed, "Schema Invalid Rule", "30.0",
                    singleConditionEvidence("VALUE", "GT", "30.0", 34.7),
                    deviceId, domainEvent(seed, deviceId), List.of());
            String raw = "{\"matchId\":\"" + PLACEHOLDER_MATCH_ID + "\",\"summary\":\"s\"}"; // missing claims[]
            scenarios.add(new EvaluationScenario(seed, "Schema-invalid provider output (missing claims array) falls back",
                    ctx, true, Optional.of(raw), EvaluationScenario.ExpectedOutcome.derivedFrom(ctx, "DETERMINISTIC_FALLBACK")));
        }

        // 14. missing assertedValue key
        {
            String seed = "14-missing-assertedValue";
            UUID deviceId = uuid(seed + "-device");
            RuleMatchExplanationContext ctx = context(seed, "Missing AssertedValue Rule", "30.0",
                    singleConditionEvidence("VALUE", "GT", "30.0", 34.7),
                    deviceId, domainEvent(seed, deviceId), List.of());
            String claimMissingAssertedValue = "{\"tier\":\"FACT\",\"text\":\"Observed value was 34.7.\","
                    + "\"evidenceRef\":\"evaluationEvidence.evaluatedConditions[0].actualValue\","
                    + "\"supportingEvidenceRefs\":[]}"; // no "assertedValue" key at all
            scenarios.add(new EvaluationScenario(seed, "FACT claim missing the assertedValue key entirely is skipped by the parser; zero claims survive, falls back",
                    ctx, true, Optional.of(candidateEnvelope(claimMissingAssertedValue)),
                    EvaluationScenario.ExpectedOutcome.derivedFrom(ctx, "DETERMINISTIC_FALLBACK")));
        }

        // 15. wrong assertedValue
        {
            String seed = "15-wrong-assertedValue";
            UUID deviceId = uuid(seed + "-device");
            RuleMatchExplanationContext ctx = context(seed, "Wrong AssertedValue Rule", "30.0",
                    singleConditionEvidence("VALUE", "GT", "30.0", 34.7),
                    deviceId, domainEvent(seed, deviceId), List.of());
            String raw = candidateEnvelope(factClaimJson("Observed value was 40.2.",
                    "evaluationEvidence.evaluatedConditions[0].actualValue", "40.2"));
            scenarios.add(new EvaluationScenario(seed, "Wrong assertedValue (40.2 vs real 34.7) rejected by GroundingChecker, falls back",
                    ctx, true, Optional.of(raw), EvaluationScenario.ExpectedOutcome.derivedFrom(ctx, "DETERMINISTIC_FALLBACK")));
        }

        // 16. nonexistent evidenceRef - physical-outcome path (Level 1 structural physical-action safety)
        {
            String seed = "16-nonexistent-evidenceref-physical-outcome";
            UUID deviceId = uuid(seed + "-device");
            RuleMatchExplanationContext ctx = context(seed, "Physical Outcome Path Rule", "30.0",
                    singleConditionEvidence("VALUE", "GT", "30.0", 34.7),
                    deviceId, domainEvent(seed, deviceId), List.of(action(seed, "CREATE_ALERT", "SUCCESS")));
            String raw = candidateEnvelope(factClaimJson("The actuator physically moved.",
                    "actions[0].physicalOutcome", "\"MOVED\""));
            scenarios.add(new EvaluationScenario(seed,
                    "STRUCTURAL PHYSICAL-ACTION SAFETY TARGET: evidenceRef requires a nonexistent physicalOutcome field - "
                            + "no such field exists anywhere in AiExplanationContext/RuleMatchExplanationContext, so the "
                            + "claim cannot resolve and is rejected regardless of its free text. This measures evidence-path "
                            + "rejection only, not detection of physical-world language.",
                    ctx, true, Optional.of(raw), EvaluationScenario.ExpectedOutcome.derivedFrom(ctx, "DETERMINISTIC_FALLBACK")));
        }

        // 17. all claims invalid
        {
            String seed = "17-all-claims-invalid";
            UUID deviceId = uuid(seed + "-device");
            RuleMatchExplanationContext ctx = context(seed, "All Claims Invalid Rule", "30.0",
                    singleConditionEvidence("VALUE", "GT", "30.0", 34.7),
                    deviceId, domainEvent(seed, deviceId), List.of());
            String raw = candidateEnvelope(
                    factClaimJson("Observed value was 999.9.", "evaluationEvidence.evaluatedConditions[0].actualValue", "999.9"),
                    factClaimJson("Something happened.", "evaluationEvidence.nonexistentField", "\"x\""));
            scenarios.add(new EvaluationScenario(seed, "Every proposed claim invalid (wrong value + nonexistent ref), all rejected, falls back",
                    ctx, true, Optional.of(raw), EvaluationScenario.ExpectedOutcome.derivedFrom(ctx, "DETERMINISTIC_FALLBACK")));
        }

        // 18. provider unavailable
        {
            String seed = "18-provider-unavailable";
            UUID deviceId = uuid(seed + "-device");
            RuleMatchExplanationContext ctx = context(seed, "Provider Unavailable Rule", "30.0",
                    singleConditionEvidence("VALUE", "GT", "30.0", 34.7),
                    deviceId, domainEvent(seed, deviceId), List.of());
            scenarios.add(new EvaluationScenario(seed, "Provider returns no usable response (Optional.empty), falls back",
                    ctx, true, Optional.empty(), EvaluationScenario.ExpectedOutcome.derivedFrom(ctx, "DETERMINISTIC_FALLBACK")));
        }

        // 19. AI disabled
        {
            String seed = "19-ai-disabled";
            UUID deviceId = uuid(seed + "-device");
            RuleMatchExplanationContext ctx = context(seed, "AI Disabled Rule", "30.0",
                    singleConditionEvidence("VALUE", "GT", "30.0", 34.7),
                    deviceId, domainEvent(seed, deviceId), List.of());
            scenarios.add(new EvaluationScenario(seed, "AI disabled by configuration, deterministic fallback",
                    ctx, false, Optional.empty(), EvaluationScenario.ExpectedOutcome.derivedFrom(ctx, "DETERMINISTIC_FALLBACK")));
        }

        // 20. historical rule snapshot
        {
            String seed = "20-historical-rule-snapshot";
            UUID deviceId = uuid(seed + "-device");
            RuleMatchExplanationContext ctx = context(seed, "Historical Snapshot Rule A", "17.5",
                    singleConditionEvidence("VALUE", "GT", "17.5", 22.1),
                    deviceId, domainEvent(seed, deviceId), List.of());
            scenarios.add(new EvaluationScenario(seed,
                    "Historical rule snapshot ('Historical Snapshot Rule A') must be reflected in the explanation - "
                            + "RuleMatchExplanationService has no RuleRegistry dependency, so there is no live-rule path to accidentally use",
                    ctx, false, Optional.empty(), EvaluationScenario.ExpectedOutcome.derivedFrom(ctx, "DETERMINISTIC_FALLBACK")));
        }

        // 21. supported INFERENCE (semantic rubric candidate)
        {
            String seed = "21-supported-inference";
            UUID deviceId = uuid(seed + "-device");
            RuleMatchExplanationContext ctx = context(seed, "Supported Inference Rule", "30.0",
                    singleConditionEvidence("VALUE", "GT", "30.0", 34.7),
                    deviceId, domainEvent(seed, deviceId), List.of());
            String raw = candidateEnvelope(inferenceClaimJson(
                    "The observed value exceeded the configured threshold for this condition.",
                    List.of("evaluationEvidence.evaluatedConditions[0].actualValue",
                            "evaluationEvidence.evaluatedConditions[0].expectedValue")));
            scenarios.add(new EvaluationScenario(seed,
                    "MODEL INFERENCE IS NOT SHOWN (P6.6): the INFERENCE claim is supported by two valid facts and is structurally accepted, but model-written "
                            + "inference prose cannot be verified, so it is dropped; a candidate that selects no grounded FACT contributes nothing and the "
                            + "platform's deterministic explanation is used",
                    ctx, true, Optional.of(raw), EvaluationScenario.ExpectedOutcome.derivedFrom(ctx, "DETERMINISTIC_FALLBACK")));
        }

        // 22. structurally-valid action status paired with physical-implication prose (semantic rubric candidate)
        {
            String seed = "22-status-physical-implication";
            UUID deviceId = uuid(seed + "-device");
            RuleMatchExplanationContext ctx = context(seed, "Status Physical Implication Rule", "30.0",
                    singleConditionEvidence("VALUE", "GT", "30.0", 34.7),
                    deviceId, domainEvent(seed, deviceId), List.of(action(seed, "CREATE_ALERT", "SUCCESS")));
            String raw = candidateEnvelope(factClaimJson("The motor turned on.", "actions[0].status", "\"SUCCESS\""));
            scenarios.add(new EvaluationScenario(seed,
                    "PHYSICAL-ACTUATION PROSE (safety class, closed in P6.5): evidenceRef 'actions[0].status' structurally "
                            + "resolves and assertedValue 'SUCCESS' matches, but the prose 'The motor turned on.' converts a software "
                            + "execution status into a physical outcome that no recorded evidence establishes. Before P6.5 this scenario "
                            + "was EXPECTED to survive Level 1; the deterministic UnsupportedAssertionGuard now refuses the claim, no AI claim "
                            + "survives, and the platform falls back to the deterministic explanation. The guard is a closed word list, not "
                            + "a semantic verifier: paraphrases outside it still need the human rubric (semantic-rubric.md).",
                    ctx, true, Optional.of(raw), EvaluationScenario.ExpectedOutcome.derivedFrom(ctx, "DETERMINISTIC_FALLBACK")));
        }

        return List.copyOf(scenarios);
    }
}
