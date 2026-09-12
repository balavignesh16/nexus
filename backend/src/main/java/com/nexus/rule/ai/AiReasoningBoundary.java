package com.nexus.rule.ai;

/**
 * Provider-independent AI reasoning boundary (Tier 4 Phase 2A). Exactly
 * one method: given the sanitized context, produce a candidate
 * explanation. No implementation exists in production code yet - this
 * interface exists purely as the seam a future, real provider integration
 * (Tier 4 Phase 2B) will implement, without requiring any change to
 * AiExplanationContext, AiCandidateParser, or GroundingChecker.
 *
 * The candidate returned here is UNTRUSTED input, regardless of
 * implementation - it must always be passed through GroundingChecker
 * before being shown to anyone.
 */
public interface AiReasoningBoundary {
    RuleMatchExplanation generate(AiExplanationContext context);
}
