package com.nexus.rule.ai;

import java.util.function.Function;

/**
 * Test-only stand-in for a real AiReasoningBoundary implementation (Tier 4
 * Phase 2A) - proves the boundary interface and the rest of the pipeline
 * work end-to-end without any real provider, network call, or SDK.
 * Production code has no implementation of AiReasoningBoundary at all in
 * this phase.
 */
public class FakeAiReasoningBoundary implements AiReasoningBoundary {

    private final Function<AiExplanationContext, RuleMatchExplanation> responder;

    public FakeAiReasoningBoundary(Function<AiExplanationContext, RuleMatchExplanation> responder) {
        this.responder = responder;
    }

    @Override
    public RuleMatchExplanation generate(AiExplanationContext context) {
        return responder.apply(context);
    }
}
