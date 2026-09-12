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

    /**
     * Fixed, machine-readable outcome/fallback-reason codes (Tier 4 Phase
     * 2C) - additive observability only. These identify which branch of
     * explainWithOutcome() produced the result, purely so a caller (the
     * Phase 2C metrics layer) can classify outcomes without re-deriving or
     * duplicating this service's own branching logic.
     */
    public static final String REASON_AI_SUCCESS = "AI_SUCCESS";
    public static final String REASON_AI_DISABLED = "AI_DISABLED";
    public static final String REASON_AI_UNAVAILABLE = "AI_UNAVAILABLE";
    public static final String REASON_AI_PARSE_FAILURE = "AI_PARSE_FAILURE";
    public static final String REASON_AI_GROUNDING_REJECTION = "AI_GROUNDING_REJECTION";

    /**
     * The result of explainWithOutcome(): the explanation itself, plus the
     * fixed reason code identifying which branch produced it. explain(UUID)
     * remains the primary, unchanged entry point for any caller that only
     * needs the explanation.
     */
    public record ExplanationOutcome(RuleMatchExplanation explanation, String reason) {
    }

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
        return explainWithOutcome(matchId).explanation();
    }

    /**
     * Same behavior as explain(UUID), additionally reporting which fixed
     * reason code produced the result (Tier 4 Phase 2C, additive only -
     * every branch below is unchanged from explain(UUID)'s original
     * behavior, just also tagged with a reason).
     */
    public ExplanationOutcome explainWithOutcome(UUID matchId) {
        RuleMatchExplanationContext context = contextBuilder.build(matchId);

        if (!aiEnabled) {
            log.debug("AI disabled; returning deterministic explanation for match {}", matchId);
            return new ExplanationOutcome(fallbackRenderer.render(context), REASON_AI_DISABLED);
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
            return new ExplanationOutcome(fallbackRenderer.render(context), REASON_AI_UNAVAILABLE);
        }

        if (raw.isEmpty()) {
            log.info("No usable AI response for match {}; falling back to deterministic explanation", matchId);
            return new ExplanationOutcome(fallbackRenderer.render(context), REASON_AI_UNAVAILABLE);
        }

        AiParseResult parseResult = candidateParser.parse(raw.get());
        if (!parseResult.success()) {
            log.info("AI candidate for match {} failed to parse ({}); falling back to deterministic explanation",
                    matchId, parseResult.failureReason());
            return new ExplanationOutcome(fallbackRenderer.render(context), REASON_AI_PARSE_FAILURE);
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
            return new ExplanationOutcome(fallbackRenderer.render(context), REASON_AI_GROUNDING_REJECTION);
        }

        log.debug("AI explanation for match {} accepted with {} validated claim(s), {} rejected",
                matchId, groundingResult.validated().claims().size(), groundingResult.rejectedClaimCount());
        return new ExplanationOutcome(groundingResult.validated(), REASON_AI_SUCCESS);
    }
}
