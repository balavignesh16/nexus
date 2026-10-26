package com.nexus.intelligence.explain;

import com.nexus.intelligence.evidence.EvidenceBundle;
import com.nexus.intelligence.evidence.EvidenceItem;
import com.nexus.rule.ai.Claim;
import com.nexus.rule.ai.ClaimTier;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The independent grounding check: it must fail every way an explanation can drift from its evidence (ledger P6-017, P6-018, P6-032, P6-034). */
class ExplanationVerifierTest {
    private final EvidenceBundle bundle = new EvidenceBundle()
            .add(EvidenceItem.present("x/status", "status", "PUBLISHED")).add(EvidenceItem.present("x/count", "count", 3))
            .add(EvidenceItem.absent("x/reason", "reason")).add(EvidenceItem.notRecorded("x/ack", "ack"));

    private Explanation of(Claim... claims) {
        return new Explanation(ExplanationKind.COMMAND_LIFECYCLE, "s", "sum", List.of(claims), List.of(), false, null, false, Instant.EPOCH, List.of(), Explanation.DETERMINISTIC);
    }

    private static Claim fact(String ref, Object value) { return new Claim(ClaimTier.FACT, "t", ref, value, List.of()); }

    @Test
    void aGroundedExplanationHasNoProblems() {
        assertEquals(List.of(), ExplanationVerifier.verify(bundle, of(fact("x/status", "PUBLISHED"), fact("x/count", 3), fact("x/reason", null),
                new Claim(ClaimTier.INFERENCE, "t", null, null, List.of("x/status", "x/count")), new Claim(ClaimTier.UNKNOWN, "t", null, null, List.of()))));
        assertEquals(List.of(), ExplanationVerifier.verify(bundle, of(fact("x/count", 3.0))), "3 and 3.0 are the same number");
    }

    @Test
    void aFactThatDisagreesWithItsEvidenceIsRejected() {
        assertTrue(ExplanationVerifier.verify(bundle, of(fact("x/status", "FAILED"))).get(0).contains("asserts FAILED but the evidence holds PUBLISHED"));
        assertEquals(1, ExplanationVerifier.verify(bundle, of(fact("x/count", 4))).size());
        assertEquals(1, ExplanationVerifier.verify(bundle, of(fact("x/reason", "TIMEOUT"))).size(), "an ABSENT item cannot be asserted to hold a value");
    }

    @Test
    void anUnresolvableReferenceIsRejected() {
        assertTrue(ExplanationVerifier.verify(bundle, of(fact("x/none", "A"))).get(0).contains("unresolvable"));
        assertTrue(ExplanationVerifier.verify(bundle, of(new Claim(ClaimTier.INFERENCE, "t", null, null, List.of("x/status", "x/none")))).get(0).contains("unresolvable"));
        assertEquals(1, ExplanationVerifier.verify(bundle, of(fact(null, "A"))).size());
    }

    @Test
    void aFactAboutARecordThatDoesNotExistIsRejectedBecauseUnknownIsNotNegative() {
        assertTrue(ExplanationVerifier.verify(bundle, of(fact("x/ack", null))).get(0).contains("NOT_RECORDED"));
    }

    @Test
    void shapeRulesOfEveryTierAreEnforced() {
        assertEquals(1, ExplanationVerifier.verify(bundle, of(new Claim(ClaimTier.INFERENCE, "t", "x/status", null, List.of("x/count")))).size(), "an inference with a direct reference");
        assertEquals(1, ExplanationVerifier.verify(bundle, of(new Claim(ClaimTier.INFERENCE, "t", null, null, List.of()))).size(), "an inference without support");
        assertEquals(1, ExplanationVerifier.verify(bundle, of(new Claim(ClaimTier.UNKNOWN, "t", "x/status", null, List.of()))).size(), "an unknown that cites evidence");
        assertEquals(1, ExplanationVerifier.verify(bundle, of(new Claim(ClaimTier.FACT, "t", "x/status", "PUBLISHED", List.of("x/count")))).size(), "a fact with support");
        assertEquals(1, ExplanationVerifier.verify(bundle, of(new Claim(ClaimTier.UNKNOWN, " ", null, null, List.of()))).size(), "no text");
    }

    @Test
    void anAbstentionMustStateWhyAndCarryNoFact() {
        var abstained = new Explanation(ExplanationKind.COMMAND_LIFECYCLE, "s", "none", List.of(fact("x/status", "PUBLISHED")), List.of(), true, "no evidence", false, Instant.EPOCH, List.of(), "D");
        assertEquals(1, ExplanationVerifier.verify(bundle, abstained).size());
        var silent = new Explanation(ExplanationKind.COMMAND_LIFECYCLE, "s", "none", List.of(), List.of(), true, " ", false, Instant.EPOCH, List.of(), "D");
        assertEquals(1, ExplanationVerifier.verify(bundle, silent).size());
        assertEquals(List.of(), ExplanationVerifier.verify(bundle, Explanation.abstention(ExplanationKind.RULE_NON_MATCH, "r", "the rule has no trigger", Instant.EPOCH)));
    }

    @Test
    void aCitedItemThatIsNotTheBundlesItemIsRejected() {
        var forged = new Explanation(ExplanationKind.COMMAND_LIFECYCLE, "s", "x", List.of(), List.of(), false, null, false, Instant.EPOCH,
                List.of(EvidenceItem.present("x/status", "status", "FAILED")), "D");
        assertTrue(ExplanationVerifier.verify(bundle, forged).get(0).contains("not the bundle's item"));
    }
}
