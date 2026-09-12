package com.nexus.rule.ai;

import java.util.Optional;

/**
 * Provider-independent AI reasoning boundary (Tier 4 Phase 2A/2B). Exactly
 * one method: given the sanitized context, return the provider's raw
 * response text, or empty if no usable response was obtained (timeout,
 * HTTP error, refusal, etc. - see implementations for exact failure
 * handling).
 *
 * Deliberately returns Optional<String>, not RuleMatchExplanation: the raw
 * text is UNTRUSTED input, regardless of implementation, and must always
 * be passed through AiCandidateParser and then GroundingChecker before it
 * can become a shown explanation. No implementation of this interface is
 * permitted to construct or return a RuleMatchExplanation directly -
 * doing so would let a provider's output bypass the grounding boundary
 * entirely.
 *
 * Provider-specific types (HTTP client responses, SDK objects) must never
 * appear in this interface's signature or in any caller of it - they are
 * confined to the implementation itself (see com.nexus.rule.ai.provider).
 */
public interface AiReasoningBoundary {
    Optional<String> generate(AiExplanationContext context);
}
