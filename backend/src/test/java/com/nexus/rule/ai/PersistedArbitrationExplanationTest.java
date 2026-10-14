package com.nexus.rule.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scenario 67 (unit half): explanations are grounded in the PERSISTED version and arbitration facts and describe execution truthfully.
 * Publication, acknowledgement, acceptance and physical actuation are separate facts; a missing record is stated as unknown instead of being
 * filled in; nothing is recomputed from today's rule or Twin state.
 */
class PersistedArbitrationExplanationTest {
    private final DeterministicExplanationRenderer renderer = new DeterministicExplanationRenderer();
    private final GroundingChecker checker = new GroundingChecker(new ObjectMapper().registerModule(new JavaTimeModule()));
    private final AiExplanationContextMapper mapper = new AiExplanationContextMapper();
    private static final Instant T = Instant.parse("2026-10-14T10:00:00Z");

    private static CandidateSummary candidate(int ordinal, String type, String disposition, String reason, String winnerType, String outcome, String outcomeReason,
            String historyStatus, Instant published, String ack) {
        return new CandidateSummary(UUID.randomUUID(), UUID.randomUUID(), ordinal, type, "SEND_COMMAND".equals(type) ? "device:" + UUID.randomUUID() + ":led" : null, 1,
            disposition, reason, winnerType, null, outcome, outcomeReason, historyStatus, published, ack);
    }

    private static RuleMatchExplanationContext context(VersionAttribution version, List<ActionSummary> actions, List<CandidateSummary> candidates) {
        return new RuleMatchExplanationContext(UUID.randomUUID(), UUID.randomUUID(), Map.of("name", "High temperature", "ruleId", "r"), null, "corr",
            Map.of("outcome", "matched", "evaluatedConditions", List.of(Map.of("field", "VALUE", "operator", "GT", "expectedValue", "30", "actualValue", 34.7, "conditionMet", true))),
            T, "All conditions met", UUID.randomUUID(), null, actions, version, candidates);
    }
    private static ActionSummary history(String type, String status) { return new ActionSummary(UUID.randomUUID(), type, status, T, "m"); }
    private static String text(RuleMatchExplanation e) { return e.claims().stream().map(Claim::text).reduce("", (a, b) -> a + "\n" + b); }
    private static boolean tier(RuleMatchExplanation e, ClaimTier tier, String contains) { return e.claims().stream().anyMatch(c -> c.tier() == tier && c.text().contains(contains)); }

    // ------------------------------------------------------------------ the happy path, with each fact on its own

    @Test void anAcceptedPublishedCommandIsStatedAsAcceptedAndPublishedButNeverAsAcknowledgedOrActuated() {
        var accepted = candidate(0, "SEND_COMMAND", "SELECTED", "INDEPENDENT_TARGET", null, "ACCEPTED", null, "SUCCESS", T, null);
        var context = context(new VersionAttribution(UUID.randomUUID(), 3L, 5L), List.of(history("SEND_COMMAND", "SUCCESS")), List.of(accepted));
        var explanation = renderer.render(context);

        assertTrue(tier(explanation, ClaimTier.FACT, "The rule was evaluated at revision 3 (execution epoch 5)"));
        assertTrue(tier(explanation, ClaimTier.FACT, "Action SEND_COMMAND #0 was SELECTED by arbitration (INDEPENDENT_TARGET)"));
        assertTrue(tier(explanation, ClaimTier.FACT, "recorded a desired-state change"));
        assertTrue(tier(explanation, ClaimTier.FACT, "published to the MQTT broker"));
        assertTrue(tier(explanation, ClaimTier.UNKNOWN, DeterministicExplanationRenderer.NO_ACK), "no ACK exists, and none is invented");
        assertTrue(tier(explanation, ClaimTier.UNKNOWN, DeterministicExplanationRenderer.PHYSICAL_UNKNOWN));
        assertFalse(text(explanation).toLowerCase().contains("acknowledgement with status"), text(explanation));
        assertFalse(text(explanation).toLowerCase().contains("actuated"));
        // The platform-owned note describes a real publication path and does NOT call the command executor a stub.
        String note = explanation.actionNote();
        assertTrue(note.contains("published to the MQTT broker"));
        assertFalse(note.toLowerCase().contains("stub"), "the blanket 'all executors are stubs' sentence is gone for commands: " + note);
        assertTrue(note.toLowerCase().contains("not evidence"));
    }

    @Test void aRecordedAcknowledgementIsAFactOnlyWhenItIsRecorded() {
        var acked = candidate(0, "SEND_COMMAND", "SELECTED", "INDEPENDENT_TARGET", null, "ACCEPTED", null, "SUCCESS", T, "ACKNOWLEDGED");
        var explanation = renderer.render(context(null, List.of(history("SEND_COMMAND", "SUCCESS")), List.of(acked)));
        assertTrue(tier(explanation, ClaimTier.FACT, "A device acknowledgement with status ACKNOWLEDGED was recorded"));
        assertFalse(tier(explanation, ClaimTier.UNKNOWN, DeterministicExplanationRenderer.NO_ACK));
        assertTrue(tier(explanation, ClaimTier.UNKNOWN, DeterministicExplanationRenderer.PHYSICAL_UNKNOWN), "even an ACK is not proof of actuation");
    }

    @Test void anAcceptedCommandWhosePublicationFailedIsNotClaimedPublished() {
        var failed = candidate(0, "SEND_COMMAND", "SELECTED", "INDEPENDENT_TARGET", null, "ACCEPTED", null, "FAILURE", null, null);
        var explanation = renderer.render(context(null, List.of(history("SEND_COMMAND", "FAILURE")), List.of(failed)));
        assertTrue(tier(explanation, ClaimTier.FACT, "publication is not established"));
        assertFalse(text(explanation).contains("published to the MQTT broker (action history status SUCCESS)"));
    }

    // ------------------------------------------------------------------ failed, denied, unsupported and unknown executions

    @Test void aFailedExecutorIsNeverPresentedAsAcceptedOrExecuted() {
        var failed = candidate(0, "WEBHOOK", "SELECTED", "INDEPENDENT_TARGET", null, "EXECUTION_FAILED", "endpoint refused the call", "FAILURE", null, null);
        var explanation = renderer.render(context(null, List.of(history("WEBHOOK", "FAILURE")), List.of(failed)));
        assertTrue(tier(explanation, ClaimTier.FACT, "The executor reported a failure: endpoint refused the call"));
        String lower = text(explanation).toLowerCase();
        assertFalse(lower.contains("accepted the command") || lower.contains("reported success"), lower);
        assertTrue(explanation.actionNote().contains("stub"), "WEBHOOK is a stub executor and the note says so");
    }

    @Test void aSuccessfulStubExecutorIsOnlyExecutedInTheSenseTheNoteExplains() {
        var executed = candidate(0, "CREATE_ALERT", "SELECTED", "INDEPENDENT_TARGET", null, "EXECUTED", null, "SUCCESS", null, null);
        var explanation = renderer.render(context(null, List.of(history("CREATE_ALERT", "SUCCESS")), List.of(executed)));
        assertTrue(tier(explanation, ClaimTier.FACT, "The executor reported success"));
        assertTrue(explanation.actionNote().contains("establishes no external effect"));
        assertFalse(explanation.actionNote().contains("SEND_COMMAND:"), "no command wording for a rule with no command");
        assertFalse(tier(explanation, ClaimTier.UNKNOWN, DeterministicExplanationRenderer.NO_ACK), "an alert has no acknowledgement to be missing");
    }

    @Test void aDeniedOrUnsupportedOrUnknownOutcomeStaysExactlyThat() {
        var denied = candidate(0, "SEND_COMMAND", "SELECTED", "INDEPENDENT_TARGET", null, "DENIED", null, null, null, null);
        var unsupported = candidate(1, "WEBHOOK", "SELECTED", "INDEPENDENT_TARGET", null, "UNSUPPORTED", "No executor found", "UNSUPPORTED", null, null);
        var unknown = candidate(2, "CREATE_ALERT", "SELECTED", "INDEPENDENT_TARGET", null, null, null, null, null, null);
        var explanation = renderer.render(context(null, List.of(), List.of(denied, unsupported, unknown)));
        assertTrue(tier(explanation, ClaimTier.FACT, "Guarded acceptance denied this action; nothing was written"));
        assertTrue(tier(explanation, ClaimTier.FACT, "No executor supported this action: No executor found"));
        assertTrue(tier(explanation, ClaimTier.UNKNOWN, "recorded no execution outcome for the selected CREATE_ALERT action #2"));
        assertFalse(tier(explanation, ClaimTier.FACT, "published"), "a denied command was never published");
    }

    // ------------------------------------------------------------------ suppressed candidates and invented selection

    @Test void aSuppressedCandidateIsDescribedAsSuppressedWithItsWinnerAndNeverAsRun() {
        var loser = candidate(0, "SEND_COMMAND", "SUPPRESSED_PRIORITY", "LOWER_PRIORITY_CONFLICT", "SEND_COMMAND", null, null, null, null, null);
        var explanation = renderer.render(context(null, List.of(), List.of(loser)));
        assertTrue(tier(explanation, ClaimTier.FACT, "was SUPPRESSED_PRIORITY by arbitration (LOWER_PRIORITY_CONFLICT)"));
        assertTrue(tier(explanation, ClaimTier.FACT, "Arbitration preferred a SEND_COMMAND action over it"));
        assertFalse(text(explanation).contains("recorded no execution outcome"), "a suppressed action has no outcome to be missing");
        assertFalse(text(explanation).toLowerCase().contains("executor reported"));
    }

    @Test void withNoPersistedArbitrationNexusSaysItCannotStateTheSelectionAndDoesNotInferOneFromActionHistory() {
        var explanation = renderer.render(context(null, List.of(history("SEND_COMMAND", "SUCCESS")), List.of()));
        assertTrue(tier(explanation, ClaimTier.UNKNOWN, DeterministicExplanationRenderer.NO_ARBITRATION));
        assertTrue(tier(explanation, ClaimTier.UNKNOWN, DeterministicExplanationRenderer.VERSION_UNKNOWN), "a historical match without attribution: version unknown");
        assertFalse(text(explanation).contains("was SELECTED"), "no selection is invented from a history row");
        assertTrue(text(explanation).contains("NEXUS recorded a SEND_COMMAND action with status SUCCESS"), "the recorded status is still reported as exactly that");
        assertNotNull(explanation.actionNote());
    }

    @Test void aRuleWithNoActionsAtAllHasNoExecutionNote() {
        assertNull(renderer.render(context(null, List.of(), List.of())).actionNote());
        assertNull(ExecutionTruthfulness.noteFor(List.of()));
        assertNull(ExecutionTruthfulness.noteFor(null));
    }

    @Test void theNoteFollowsTheActionTypesInvolvedAndOwnsUnknownTypes() {
        String both = ExecutionTruthfulness.noteFor(List.of("SEND_COMMAND", "WEBHOOK"));
        assertTrue(both.contains("SEND_COMMAND:") && both.contains("stub"));
        assertTrue(ExecutionTruthfulness.noteFor(List.of("TELEPORT")).contains("only what the software executor reported"));
    }

    // ------------------------------------------------------------------ grounding of the deterministic and the AI path

    @Test void everyDeterministicClaimAboutVersionAndArbitrationPassesGrounding() {
        var accepted = candidate(0, "SEND_COMMAND", "SELECTED", "INDEPENDENT_TARGET", null, "ACCEPTED", null, "SUCCESS", T, "ACKNOWLEDGED");
        var suppressed = candidate(1, "WEBHOOK", "SUPPRESSED_EXCLUSION", "EXCLUSION_LOWER_RANK", "SEND_COMMAND", null, null, null, null, null);
        var context = context(new VersionAttribution(UUID.randomUUID(), 3L, 5L), List.of(history("SEND_COMMAND", "SUCCESS")), List.of(accepted, suppressed));
        var explanation = renderer.render(context);
        var result = checker.check(context, explanation);
        assertEquals(0, result.rejectedClaimCount(), result.rejectionReasons().toString());
        assertEquals(explanation.claims().size() + 1, result.validated().claims().size(), "only the platform-owned causal-uncertainty claim is appended");
        assertTrue(explanation.claims().stream().filter(c -> c.tier() == ClaimTier.FACT).count() >= 8);
    }

    @Test void anAiClaimAboutTheSelectionMustMatchThePersistedRecordOrItIsRejected() {
        var suppressed = candidate(0, "SEND_COMMAND", "SUPPRESSED_PRIORITY", "LOWER_PRIORITY_CONFLICT", "SEND_COMMAND", null, null, null, null, null);
        var context = context(null, List.of(), List.of(suppressed));
        var honest = new Claim(ClaimTier.FACT, "The command was suppressed.", "candidates[0].disposition", "SUPPRESSED_PRIORITY", List.of());
        var lie = new Claim(ClaimTier.FACT, "The command was selected and ran.", "candidates[0].disposition", "SELECTED", List.of());
        var invented = new Claim(ClaimTier.FACT, "A second command was selected.", "candidates[1].disposition", "SELECTED", List.of());
        var fakeAck = new Claim(ClaimTier.FACT, "The device acknowledged.", "candidates[0].ackStatus", "ACKNOWLEDGED", List.of());
        var result = checker.check(context, new RuleMatchExplanation(context.matchId(), "s", List.of(honest, lie, invented, fakeAck), "The system physically actuated the device.", "AI"));
        assertEquals(3, result.rejectedClaimCount(), "the contradicting, the non-existent and the unrecorded-ack claims are all rejected: " + result.rejectionReasons());
        assertTrue(result.validated().claims().contains(honest));
        assertFalse(result.validated().claims().contains(lie));
        assertNull(result.validated().actionNote(), "the note is platform-owned: nothing ran for a suppressed command, so the model note is dropped, not kept");
    }

    // ------------------------------------------------------------------ the data-minimization boundary

    @Test void theAiFacingContextCarriesDispositionsAndNumbersButNoIdentifierOrTarget() throws Exception {
        var accepted = candidate(0, "SEND_COMMAND", "SELECTED", "INDEPENDENT_TARGET", null, "ACCEPTED", null, "SUCCESS", T, "ACKNOWLEDGED");
        var versionId = UUID.randomUUID();
        var context = context(new VersionAttribution(versionId, 3L, 5L), List.of(history("SEND_COMMAND", "SUCCESS")), List.of(accepted));
        var ai = mapper.toAiContext(context);
        assertEquals(new AiVersionAttribution(3L, 5L), ai.version());
        var summary = ai.candidates().getFirst();
        assertEquals("SELECTED", summary.disposition());
        assertEquals("ACCEPTED", summary.acceptanceOutcome());
        assertTrue(summary.publishedToBroker());
        assertEquals("ACKNOWLEDGED", summary.deviceAckStatus());
        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(ai);
        for (String forbidden : List.of(versionId.toString(), accepted.candidateId().toString(), accepted.ruleId().toString(), accepted.targetKey(), context.deviceId().toString(), context.matchId().toString()))
            assertFalse(json.contains(forbidden), "AI payload leaks " + forbidden);
        assertTrue(new AiExplanationPromptBuilder(new ObjectMapper().registerModule(new JavaTimeModule())).systemInstruction().contains("acceptance does not imply publication"));
    }

    @Test void aContextWithoutPersistedArbitrationMapsToNoCandidatesNotInventedOnes() {
        var ai = mapper.toAiContext(context(null, List.of(history("CREATE_ALERT", "SUCCESS")), List.of()));
        assertNull(ai.version());
        assertTrue(ai.candidates().isEmpty());
        assertEquals(1, ai.actions().size());
    }

    // ------------------------------------------------------------------ persisted context provenance

    @Test void thePersistedTriggerAndObservationQualityAreGroundedFactsAndAbsentProvenanceIsSimplyNotClaimed() {
        var evidence = Map.<String, Object>of("outcome", "matched",
            "frozenContext", Map.of("trigger", Map.of("type", "ACCEPTED_TELEMETRY"), "isolation", "repeatable read"),
            "evaluatedConditions", List.of(Map.of("field", "VALUE", "operator", "GT", "expectedValue", "30", "actualValue", 34.7, "conditionMet", true,
                "quality", "GOOD", "qualityReason", "WITHIN_FRESHNESS_THRESHOLD")));
        var context = new RuleMatchExplanationContext(UUID.randomUUID(), UUID.randomUUID(), Map.of("name", "r"), null, null, evidence, T, "ok", UUID.randomUUID(), null, List.of(), null, List.of());
        var explanation = renderer.render(context);
        assertTrue(tier(explanation, ClaimTier.FACT, "The match was evaluated for a ACCEPTED_TELEMETRY trigger"));
        assertTrue(tier(explanation, ClaimTier.FACT, "The observation used had quality GOOD (WITHIN_FRESHNESS_THRESHOLD)"));
        assertEquals(0, checker.check(context, explanation).rejectedClaimCount());

        var historical = renderer.render(context(null, List.of(), List.of()));      // evidence without frozenContext or quality (a pre-frozen-context match)
        assertFalse(text(historical).contains("trigger."), "no trigger is claimed when none was recorded");
        assertFalse(text(historical).contains("quality"));
    }

    // ------------------------------------------------------------------ only executors that ran; persisted verification, never inferred

    private static CandidateSummary verified(String verification, String evidence, String reason, String ack) {
        return new CandidateSummary(UUID.randomUUID(), UUID.randomUUID(), 0, "SEND_COMMAND", "device:" + UUID.randomUUID() + ":led", 1, "SELECTED", "INDEPENDENT_TARGET", null, null,
            "ACCEPTED", null, "SUCCESS", T, ack, verification, evidence, reason);
    }

    @Test void theNoteDescribesOnlyExecutorsThatActuallyRan() {
        var suppressedWebhook = candidate(0, "WEBHOOK", "SUPPRESSED_PRIORITY", "LOWER_PRIORITY_CONFLICT", "SEND_COMMAND", null, null, null, null, null);
        var coalescedAlert = candidate(1, "CREATE_ALERT", "COALESCED", "IDENTICAL_COMMAND_COALESCED", null, null, null, null, null, null);
        var denied = candidate(2, "CREATE_ALERT", "SELECTED", "INDEPENDENT_TARGET", null, "DENIED", null, null, null, null);
        var onlyNotRun = renderer.render(context(null, List.of(), List.of(suppressedWebhook, coalescedAlert, denied)));
        assertNull(onlyNotRun.actionNote(), "nothing ran, so no executor is described: " + onlyNotRun.actionNote());

        var accepted = candidate(3, "SEND_COMMAND", "SELECTED", "INDEPENDENT_TARGET", null, "ACCEPTED", null, "SUCCESS", T, null);
        var mixed = renderer.render(context(null, List.of(history("SEND_COMMAND", "SUCCESS")), List.of(accepted, suppressedWebhook, coalescedAlert, denied)));
        assertTrue(mixed.actionNote().contains("SEND_COMMAND:"));
        assertFalse(mixed.actionNote().contains("stub"), "the suppressed webhook and the coalesced alert never ran, so the stub sentence is not claimed: " + mixed.actionNote());

        var failed = candidate(4, "WEBHOOK", "SELECTED", "INDEPENDENT_TARGET", null, "EXECUTION_FAILED", "refused", "FAILURE", null, null);
        assertTrue(renderer.render(context(null, List.of(), List.of(failed))).actionNote().contains("stub"), "a failed executor DID run");
        assertEquals(java.util.Set.of("SEND_COMMAND"), ExecutionTruthfulness.typesOf(context(null, List.of(), List.of(accepted, suppressedWebhook))));
    }

    @Test void convergenceIsGroundedInPersistedVerificationAndNeverInferredFromPublicationOrAck() {
        // Published AND acknowledged, but no verification record: convergence is UNKNOWN.
        var noRecord = renderer.render(context(null, List.of(history("SEND_COMMAND", "SUCCESS")), List.of(verified(null, null, null, "ACKNOWLEDGED"))));
        assertTrue(tier(noRecord, ClaimTier.UNKNOWN, DeterministicExplanationRenderer.NO_VERIFICATION));
        assertTrue(tier(noRecord, ClaimTier.FACT, "A device acknowledgement with status ACKNOWLEDGED was recorded"));
        assertFalse(text(noRecord).contains("CONVERGED"));

        var converged = renderer.render(context(null, List.of(history("SEND_COMMAND", "SUCCESS")), List.of(verified("CONVERGED", "QUALIFIED_MAPPED_READBACK", null, null))));
        assertTrue(tier(converged, ClaimTier.FACT, "A qualified reported readback matched the desired state (verification status CONVERGED); readback evidence QUALIFIED_MAPPED_READBACK"));
        assertTrue(tier(converged, ClaimTier.UNKNOWN, DeterministicExplanationRenderer.NO_ACK), "convergence does not create an acknowledgement");
        assertTrue(tier(converged, ClaimTier.UNKNOWN, DeterministicExplanationRenderer.PHYSICAL_UNKNOWN), "even verified convergence is not proof of physical actuation");
        assertFalse(tier(converged, ClaimTier.UNKNOWN, DeterministicExplanationRenderer.NO_VERIFICATION));

        assertTrue(tier(renderer.render(context(null, List.of(), List.of(verified("DIVERGED", null, "VALUE_MISMATCH", null)))), ClaimTier.FACT, "did not match the desired state (verification status DIVERGED) (VALUE_MISMATCH)"));
        assertTrue(tier(renderer.render(context(null, List.of(), List.of(verified("TIMED_OUT", null, null, null)))), ClaimTier.FACT, "before the verification deadline (verification status TIMED_OUT)"));
        assertTrue(tier(renderer.render(context(null, List.of(), List.of(verified("PENDING", null, null, null)))), ClaimTier.FACT, "Verification is pending"));
        var unverified = renderer.render(context(null, List.of(), List.of(verified("UNVERIFIED", null, null, null))));
        assertTrue(tier(unverified, ClaimTier.FACT, "No readback verification applies to this command (verification status UNVERIFIED); convergence is not established"));
        assertFalse(text(unverified).contains("matched the desired state"));

        // A verification fact is only ever claimed for an ACCEPTED command; a denied or failed one has none to claim.
        var denied = new CandidateSummary(UUID.randomUUID(), UUID.randomUUID(), 0, "SEND_COMMAND", null, 1, "SELECTED", "INDEPENDENT_TARGET", null, null, "DENIED", null, null, null, null, "CONVERGED", "X", null);
        assertFalse(text(renderer.render(context(null, List.of(), List.of(denied)))).contains("verification status"));
    }

    @Test void persistedVerificationClaimsPassGroundingAndAWrongModelClaimAboutConvergenceIsRejected() {
        var converged = verified("CONVERGED", "QUALIFIED_MAPPED_READBACK", null, "ACKNOWLEDGED");
        var context = context(null, List.of(history("SEND_COMMAND", "SUCCESS")), List.of(converged));
        assertEquals(0, checker.check(context, renderer.render(context)).rejectedClaimCount());
        var pending = verified("PENDING", null, null, "ACKNOWLEDGED");
        var pendingContext = context(null, List.of(history("SEND_COMMAND", "SUCCESS")), List.of(pending));
        var lie = new Claim(ClaimTier.FACT, "The device converged.", "candidates[0].verificationStatus", "CONVERGED", List.of());
        var result = checker.check(pendingContext, new RuleMatchExplanation(pendingContext.matchId(), "s", List.of(lie), null, "AI"));
        assertEquals(1, result.rejectedClaimCount(), "convergence cannot be asserted when the persisted status is PENDING");
        var ai = mapper.toAiContext(context).candidates().getFirst();
        assertEquals("CONVERGED", ai.verificationStatus());
        assertTrue(ai.qualifiedReadbackRecorded());
    }
}
