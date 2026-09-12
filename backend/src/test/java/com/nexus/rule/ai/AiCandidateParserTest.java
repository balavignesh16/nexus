package com.nexus.rule.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for AiCandidateParser (Tier 4 Phase 2A) - the defensive boundary
 * between untrusted raw JSON and internal Java records. Every test
 * confirms parse() returns a result object; none should ever let an
 * exception escape.
 */
class AiCandidateParserTest {

    private AiCandidateParser parser;
    private UUID matchId;

    @BeforeEach
    void setUp() {
        parser = new AiCandidateParser(new ObjectMapper());
        matchId = UUID.randomUUID();
    }

    @Test
    void validCandidate_parsesSuccessfully() {
        String json = """
                {
                  "matchId": "%s",
                  "summary": "The rule matched because the observed value exceeded the threshold.",
                  "claims": [
                    {
                      "tier": "FACT",
                      "text": "Observed value was 34.7.",
                      "evidenceRef": "evaluationEvidence.evaluatedConditions[0].actualValue",
                      "assertedValue": 34.7,
                      "supportingEvidenceRefs": []
                    }
                  ]
                }
                """.formatted(matchId);

        AiParseResult result = parser.parse(json);

        assertTrue(result.success());
        assertEquals(matchId, result.candidate().matchId());
        assertEquals(1, result.candidate().claims().size());
        assertEquals("AI", result.candidate().generatedBy());
    }

    @Test
    void factWithCorrectAssertedValue_parses() {
        String json = """
                {
                  "matchId": "%s",
                  "summary": "s",
                  "claims": [
                    { "tier": "FACT", "text": "x", "evidenceRef": "a.b", "assertedValue": "ok", "supportingEvidenceRefs": [] }
                  ]
                }
                """.formatted(matchId);

        AiParseResult result = parser.parse(json);

        assertTrue(result.success());
        assertEquals(1, result.candidate().claims().size());
        assertEquals("ok", result.candidate().claims().get(0).assertedValue());
    }

    @Test
    void factMissingAssertedValueKey_claimIsSkipped() {
        String json = """
                {
                  "matchId": "%s",
                  "summary": "s",
                  "claims": [
                    { "tier": "FACT", "text": "x", "evidenceRef": "a.b", "supportingEvidenceRefs": [] }
                  ]
                }
                """.formatted(matchId);

        AiParseResult result = parser.parse(json);

        assertTrue(result.success());
        assertTrue(result.candidate().claims().isEmpty());
        assertEquals(1, result.skippedClaimReasons().size());
        assertTrue(result.skippedClaimReasons().get(0).contains("assertedValue"));
    }

    @Test
    void factWithExplicitNullAssertedValue_isAccepted() {
        String json = """
                {
                  "matchId": "%s",
                  "summary": "s",
                  "claims": [
                    { "tier": "FACT", "text": "x", "evidenceRef": "a.b", "assertedValue": null, "supportingEvidenceRefs": [] }
                  ]
                }
                """.formatted(matchId);

        AiParseResult result = parser.parse(json);

        assertTrue(result.success());
        assertEquals(1, result.candidate().claims().size());
        assertEquals(null, result.candidate().claims().get(0).assertedValue());
    }

    @Test
    void nullContainingClaimsList_rejectedSafely_noException() {
        String json = """
                {
                  "matchId": "%s",
                  "summary": "s",
                  "claims": [
                    null,
                    { "tier": "FACT", "text": "x", "evidenceRef": "a.b", "assertedValue": 1, "supportingEvidenceRefs": [] }
                  ]
                }
                """.formatted(matchId);

        AiParseResult result = parser.parse(json);

        assertTrue(result.success());
        assertEquals(1, result.candidate().claims().size(), "the null element is skipped, the valid one survives");
        assertTrue(result.skippedClaimReasons().stream().anyMatch(r -> r.contains("null")));
    }

    @Test
    void nullContainingSupportingEvidenceRefsList_rejectedSafely_noException() {
        String json = """
                {
                  "matchId": "%s",
                  "summary": "s",
                  "claims": [
                    { "tier": "INFERENCE", "text": "x", "supportingEvidenceRefs": ["a.b", null] }
                  ]
                }
                """.formatted(matchId);

        AiParseResult result = parser.parse(json);

        assertTrue(result.success());
        assertTrue(result.candidate().claims().isEmpty());
        assertEquals(1, result.skippedClaimReasons().size());
    }

    @Test
    void malformedJson_rejectedWithoutException() {
        AiParseResult result = parser.parse("{ this is not valid json ]]");

        assertFalse(result.success());
        assertTrue(result.failureReason() != null);
    }

    @Test
    void invalidTier_claimSkipped() {
        String json = """
                {
                  "matchId": "%s",
                  "summary": "s",
                  "claims": [
                    { "tier": "MAYBE", "text": "x", "supportingEvidenceRefs": [] }
                  ]
                }
                """.formatted(matchId);

        AiParseResult result = parser.parse(json);

        assertTrue(result.success());
        assertTrue(result.candidate().claims().isEmpty());
        assertEquals(1, result.skippedClaimReasons().size());
    }

    @Test
    void inferenceWithAssertedValue_claimSkipped() {
        String json = """
                {
                  "matchId": "%s",
                  "summary": "s",
                  "claims": [
                    { "tier": "INFERENCE", "text": "x", "assertedValue": "not allowed", "supportingEvidenceRefs": ["a.b"] }
                  ]
                }
                """.formatted(matchId);

        AiParseResult result = parser.parse(json);

        assertTrue(result.success());
        assertTrue(result.candidate().claims().isEmpty());
    }

    @Test
    void unknownWithAssertedValue_claimSkipped() {
        String json = """
                {
                  "matchId": "%s",
                  "summary": "s",
                  "claims": [
                    { "tier": "UNKNOWN", "text": "x", "assertedValue": "not allowed", "supportingEvidenceRefs": [] }
                  ]
                }
                """.formatted(matchId);

        AiParseResult result = parser.parse(json);

        assertTrue(result.success());
        assertTrue(result.candidate().claims().isEmpty());
    }

    @Test
    void missingMatchId_wholeCandidateRejected() {
        String json = """
                {
                  "summary": "s",
                  "claims": []
                }
                """;

        AiParseResult result = parser.parse(json);

        assertFalse(result.success());
    }

    @Test
    void invalidMatchIdFormat_wholeCandidateRejected() {
        String json = """
                { "matchId": "not-a-uuid", "summary": "s", "claims": [] }
                """;

        AiParseResult result = parser.parse(json);

        assertFalse(result.success());
    }

    @Test
    void missingSummary_wholeCandidateRejected() {
        String json = """
                { "matchId": "%s", "claims": [] }
                """.formatted(matchId);

        AiParseResult result = parser.parse(json);

        assertFalse(result.success());
    }

    @Test
    void missingClaimsArray_wholeCandidateRejected() {
        String json = """
                { "matchId": "%s", "summary": "s" }
                """.formatted(matchId);

        AiParseResult result = parser.parse(json);

        assertFalse(result.success());
    }

    @Test
    void candidateActionNoteAndGeneratedBy_neverReadFromJson() {
        String json = """
                {
                  "matchId": "%s",
                  "summary": "s",
                  "actionNote": "attempted override",
                  "generatedBy": "NOT_AI",
                  "claims": []
                }
                """.formatted(matchId);

        AiParseResult result = parser.parse(json);

        assertTrue(result.success());
        assertEquals(null, result.candidate().actionNote());
        assertEquals("AI", result.candidate().generatedBy());
    }

    @Test
    void nonObjectTopLevelJson_rejected() {
        AiParseResult result = parser.parse("[1, 2, 3]");

        assertFalse(result.success());
    }
}
