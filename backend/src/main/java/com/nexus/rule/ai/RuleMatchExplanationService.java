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
    private com.nexus.intelligence.model.AiCallGuard callGuard;

    public RuleMatchExplanationService(RuleMatchExplanationContextBuilder contextBuilder,
                                        AiExplanationContextMapper mapper,
                                        AiReasoningBoundary reasoningBoundary,
                                        AiCandidateParser candidateParser,
                                        GroundingChecker groundingChecker,
                                        DeterministicExplanationRenderer fallbackRenderer,
                                        // device evidence leaves the platform only when the provider is enabled AND sending it is separately consented to
                                        @Value("#{${nexus.ai.enabled:false} and ${nexus.ai.send-device-evidence:false}}") boolean aiEnabled) {
        this.contextBuilder = contextBuilder;
        this.mapper = mapper;
        this.reasoningBoundary = reasoningBoundary;
        this.candidateParser = candidateParser;
        this.groundingChecker = groundingChecker;
        this.fallbackRenderer = fallbackRenderer;
        this.aiEnabled = aiEnabled;
    }

    /** Optional: bounds the provider call (timeout, bulkhead, circuit breaker). Absent in unit tests that construct the service directly. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCallGuard(com.nexus.intelligence.model.AiCallGuard callGuard) {
        this.callGuard = callGuard;
    }

    private Optional<String> guarded(AiExplanationContext aiContext) {
        com.nexus.intelligence.model.AiCallGuard.Result result = callGuard.call(() -> reasoningBoundary.generate(aiContext).orElse(null));
        return result.refusal() != null ? Optional.empty() : Optional.ofNullable(result.text());
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
            raw = callGuard == null ? reasoningBoundary.generate(aiContext) : guarded(aiContext);
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

        // The model's words never reach the user. What it contributed is a choice and an order of grounded evidence; every sentence shown is a
        // platform-owned statement (see platformRendered).
        RuleMatchExplanation rendered = platformRendered(context, groundingResult.validated());
        if (rendered == null) {
            log.info("AI candidate for match {} selected no grounded fact; falling back to deterministic explanation", matchId);
            return new ExplanationOutcome(fallbackRenderer.render(context), REASON_AI_GROUNDING_REJECTION);
        }
        log.debug("AI explanation for match {} accepted: {} grounded claim(s) rendered from platform statements, {} rejected",
                matchId, rendered.claims().size(), groundingResult.rejectedClaimCount());
        return new ExplanationOutcome(rendered, REASON_AI_SUCCESS);
    }

    /**
     * CONSTRAINED OUTPUT (P6.6). Model prose cannot be verified automatically, and a closed word list cannot make it faithful: ten of ten meaning-equivalent
     * unsupported sentences passed the wording guard in the P6.5 evaluation. So the user never sees model-written text. A grounded FACT is replaced by the
     * platform's own statement for the same evidence and value (the deterministic renderer's sentence, or a fixed template naming the evidence and value
     * when the renderer has none); model INFERENCE claims and model UNKNOWN claims are dropped (the platform's own UNKNOWN statements are kept); the summary is
     * the platform's. Nothing the model wrote becomes an authoritative fact, and it can choose neither the wording nor an unrecorded claim. Returns null when
     * no grounded fact is left, in which case the caller uses the deterministic explanation.
     */
    private RuleMatchExplanation platformRendered(RuleMatchExplanationContext context, RuleMatchExplanation validated) {
        RuleMatchExplanation deterministic = fallbackRenderer.render(context);
        java.util.Map<String, Claim> platformFacts = new java.util.LinkedHashMap<>();
        java.util.Set<String> platformUnknown = new java.util.HashSet<>();
        for (Claim c : deterministic.claims()) {
            if (c.tier() == ClaimTier.FACT && c.evidenceRef() != null) platformFacts.putIfAbsent(factKey(c.evidenceRef(), c.assertedValue()), c);
            if (c.tier() == ClaimTier.UNKNOWN) platformUnknown.add(c.text());
        }
        java.util.List<Claim> out = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int facts = 0;
        for (Claim c : validated.claims()) {
            Claim chosen = null;
            if (c.tier() == ClaimTier.FACT) {
                chosen = platformFacts.get(factKey(c.evidenceRef(), c.assertedValue()));
                if (chosen == null) {
                    chosen = new Claim(ClaimTier.FACT, "The recorded evidence " + c.evidenceRef() + " has the value " + c.assertedValue() + ".", c.evidenceRef(), c.assertedValue(), java.util.List.of());
                }
            } else if (c.tier() == ClaimTier.UNKNOWN && platformUnknown.contains(c.text())) {
                chosen = c;
            }
            if (chosen != null && seen.add(chosen.tier() + "|" + chosen.text())) {
                out.add(chosen);
                if (chosen.tier() == ClaimTier.FACT) facts++;
            }
        }
        if (facts == 0) return null;
        return new RuleMatchExplanation(context.matchId(), deterministic.summary(), out, validated.actionNote(), validated.generatedBy());
    }

    private static String factKey(String ref, Object value) {
        String v;
        if (value instanceof Number n) {
            try {
                v = new java.math.BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException notFinite) {
                v = String.valueOf(value);
            }
        } else {
            v = String.valueOf(value);
        }
        return ref + "=" + v;
    }
}
