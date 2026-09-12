package com.nexus.rule.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * The production orchestration boundary for rule-match explanations (Tier
 * 4 Phase 2B). Wires RuleMatchExplanationContextBuilder,
 * AiExplanationContextMapper, AiReasoningBoundary, AiCandidateParser, and
 * GroundingChecker together, falling back to DeterministicExplanationRenderer
 * whenever AI is disabled, unavailable, malformed, or fails grounding.
 *
 * This class is deliberately the ONLY caller of AiReasoningBoundary in
 * production code. It is called on-demand only - nothing in the telemetry
 * ingestion, rule evaluation, or action execution pipeline invokes it, and
 * it must never be wired into any of those paths. No REST endpoint calls
 * it yet (Tier 4 Phase 2C).
 */
@Service
public class RuleMatchExplanationService {

    private static final Logger log = LoggerFactory.getLogger(RuleMatchExplanationService.class);

    private final RuleMatchExplanationContextBuilder contextBuilder;
    private final AiExplanationContextMapper mapper;
    private final AiReasoningBoundary reasoningBoundary;
    private final AiCandidateParser candidateParser;
    private final GroundingChecker groundingChecker;
    private final DeterministicExplanationRenderer fallbackRenderer;
    private final boolean aiEnabled;

    public RuleMatchExplanationService(RuleMatchExplanationContextBuilder contextBuilder,
                                        AiExplanationContextMapper mapper,
                                        AiReasoningBoundary reasoningBoundary,
                                        AiCandidateParser candidateParser,
                                        GroundingChecker groundingChecker,
                                        DeterministicExplanationRenderer fallbackRenderer,
                                        @Value("${nexus.ai.enabled:false}") boolean aiEnabled) {
        this.contextBuilder = contextBuilder;
        this.mapper = mapper;
        this.reasoningBoundary = reasoningBoundary;
        this.candidateParser = candidateParser;
        this.groundingChecker = groundingChecker;
        this.fallbackRenderer = fallbackRenderer;
        this.aiEnabled = aiEnabled;
    }

    public RuleMatchExplanation explain(UUID matchId) {
        RuleMatchExplanationContext context = contextBuilder.build(matchId);

        if (!aiEnabled) {
            log.debug("AI disabled; returning deterministic explanation for match {}", matchId);
            return fallbackRenderer.render(context);
        }

        AiExplanationContext aiContext = mapper.toAiContext(context);

        Optional<String> raw;
        try {
            raw = reasoningBoundary.generate(aiContext);
        } catch (RuntimeException e) {
            // No provider-implementation exception may escape this
            // service - any unexpected failure inside a boundary
            // implementation is treated identically to "no response".
            log.warn("AI reasoning boundary threw for match {}; falling back to deterministic explanation", matchId, e);
            return fallbackRenderer.render(context);
        }

        if (raw.isEmpty()) {
            log.info("No usable AI response for match {}; falling back to deterministic explanation", matchId);
            return fallbackRenderer.render(context);
        }

        AiParseResult parseResult = candidateParser.parse(raw.get());
        if (!parseResult.success()) {
            log.info("AI candidate for match {} failed to parse ({}); falling back to deterministic explanation",
                    matchId, parseResult.failureReason());
            return fallbackRenderer.render(context);
        }

        // matchId is never sent to the model (data minimization) - the
        // model emits a fixed placeholder (see AiExplanationPromptBuilder)
        // which is discarded here and replaced with the real, NEXUS-owned
        // matchId before grounding. Nothing about the model's own claimed
        // matchId is trusted or even inspected.
        RuleMatchExplanation candidateWithRealMatchId = new RuleMatchExplanation(
                context.matchId(),
                parseResult.candidate().summary(),
                parseResult.candidate().claims(),
                parseResult.candidate().actionNote(),
                parseResult.candidate().generatedBy());

        GroundingResult groundingResult = groundingChecker.check(context, candidateWithRealMatchId);
        if (groundingResult.validated().claims().isEmpty()) {
            log.info("AI candidate for match {} had zero claims survive grounding ({} rejected: {}); "
                            + "falling back to deterministic explanation",
                    matchId, groundingResult.rejectedClaimCount(), groundingResult.rejectionReasons());
            return fallbackRenderer.render(context);
        }

        log.debug("AI explanation for match {} accepted with {} validated claim(s), {} rejected",
                matchId, groundingResult.validated().claims().size(), groundingResult.rejectedClaimCount());
        return groundingResult.validated();
    }
}
