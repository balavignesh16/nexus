package com.nexus.rule.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The AiReasoningBoundary bean active whenever nexus.ai.enabled is false
 * (or unset) - Tier 4 Phase 2B. Always returns empty; exists purely so
 * RuleMatchExplanationService can be constructed via normal dependency
 * injection without any conditional wiring of its own, and so that a real
 * provider adapter (OpenAiReasoningBoundary, which requires
 * nexus.ai.api-key/model/etc.) is never even instantiated - let alone
 * called - when AI is disabled.
 */
@Component
@ConditionalOnProperty(name = "nexus.ai.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpAiReasoningBoundary implements AiReasoningBoundary {

    @Override
    public Optional<String> generate(AiExplanationContext context) {
        return Optional.empty();
    }
}
