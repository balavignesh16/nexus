package com.nexus.rule.ai;

import java.util.Optional;
import java.util.function.Function;

/**
 * Test-only stand-in for a real AiReasoningBoundary implementation (Tier 4
 * Phase 2A/2B) - proves the boundary interface and the rest of the
 * pipeline work end-to-end without any real provider, network call, or
 * SDK. Production code's only implementations are NoOpAiReasoningBoundary
 * and OpenAiReasoningBoundary.
 */
public class FakeAiReasoningBoundary implements AiReasoningBoundary {

    private final Function<AiExplanationContext, Optional<String>> responder;

    public FakeAiReasoningBoundary(Function<AiExplanationContext, Optional<String>> responder) {
        this.responder = responder;
    }

    /** Convenience factory for a fake that always returns the given raw JSON text. */
    public static FakeAiReasoningBoundary alwaysReturning(String rawJson) {
        return new FakeAiReasoningBoundary(ctx -> Optional.of(rawJson));
    }

    /** Convenience factory for a fake that always returns no usable response. */
    public static FakeAiReasoningBoundary alwaysEmpty() {
        return new FakeAiReasoningBoundary(ctx -> Optional.empty());
    }

    @Override
    public Optional<String> generate(AiExplanationContext context) {
        return responder.apply(context);
    }
}
