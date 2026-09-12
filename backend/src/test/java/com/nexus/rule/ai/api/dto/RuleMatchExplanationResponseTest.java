package com.nexus.rule.ai.api.dto;

import com.nexus.rule.ai.Claim;
import com.nexus.rule.ai.ClaimTier;
import com.nexus.rule.ai.RuleMatchExplanation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuleMatchExplanationResponseTest {

    @Test
    void from_aiExplanation_mapsAllFieldsExactly() {
        UUID matchId = UUID.randomUUID();
        List<Claim> claims = List.of(new Claim(ClaimTier.FACT, "Observed value was 34.7.",
                "evaluationEvidence.evaluatedConditions[0].actualValue", 34.7, List.of()));
        RuleMatchExplanation explanation = new RuleMatchExplanation(matchId, "summary text", claims, "action note", "AI");

        RuleMatchExplanationResponse response = RuleMatchExplanationResponse.from(explanation);

        assertEquals(matchId, response.matchId());
        assertEquals("summary text", response.summary());
        assertEquals(claims, response.claims());
        assertEquals("action note", response.actionNote());
        assertEquals("AI", response.generatedBy());
    }

    @Test
    void from_deterministicFallback_mapsAllFieldsExactly() {
        UUID matchId = UUID.randomUUID();
        RuleMatchExplanation explanation = new RuleMatchExplanation(matchId, "fallback summary", List.of(), null, "DETERMINISTIC_FALLBACK");

        RuleMatchExplanationResponse response = RuleMatchExplanationResponse.from(explanation);

        assertEquals(matchId, response.matchId());
        assertEquals("DETERMINISTIC_FALLBACK", response.generatedBy());
        assertEquals(null, response.actionNote());
        assertEquals(0, response.claims().size());
    }

    @Test
    void from_actionNotePreserved_whenPresent() {
        RuleMatchExplanation explanation = new RuleMatchExplanation(
                UUID.randomUUID(), "s", List.of(), "NEXUS recorded the action(s) above...", "DETERMINISTIC_FALLBACK");

        RuleMatchExplanationResponse response = RuleMatchExplanationResponse.from(explanation);

        assertEquals("NEXUS recorded the action(s) above...", response.actionNote());
    }
}
