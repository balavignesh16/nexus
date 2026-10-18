package com.nexus.intelligence.model;

import com.nexus.rule.ai.AiCandidateParser;
import com.nexus.rule.ai.AiExplanationContext;
import com.nexus.rule.ai.AiExplanationContextMapper;
import com.nexus.rule.ai.AiReasoningBoundary;
import com.nexus.rule.ai.DeterministicExplanationRenderer;
import com.nexus.rule.ai.GroundingChecker;
import com.nexus.rule.ai.RuleMatchExplanation;
import com.nexus.rule.ai.RuleMatchExplanationContext;
import com.nexus.rule.ai.RuleMatchExplanationContextBuilder;
import com.nexus.rule.ai.RuleMatchExplanationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** P6-010 / P6-013 on the real explanation service: a provider that throws, hangs or floods never breaks the deterministic explanation. */
class GuardedExplanationServiceTest {
    private final UUID matchId = UUID.randomUUID();
    private AiCallGuard guard;

    @AfterEach
    void close() {
        if (guard != null) guard.close();
    }

    private RuleMatchExplanationService service(AiReasoningBoundary boundary, boolean enabled, GuardSettings settings) {
        RuleMatchExplanationContextBuilder builder = mock(RuleMatchExplanationContextBuilder.class);
        // the contexts are final records and are only passed through; the stubs below do not inspect them
        RuleMatchExplanationContext context = null;
        AiExplanationContext aiContext = null;
        when(builder.build(any())).thenReturn(context);
        AiExplanationContextMapper mapper = mock(AiExplanationContextMapper.class);
        when(mapper.toAiContext(any())).thenReturn(aiContext);
        DeterministicExplanationRenderer renderer = mock(DeterministicExplanationRenderer.class);
        when(renderer.render(any())).thenReturn(new RuleMatchExplanation(matchId, "fallback", List.of(), null, "DETERMINISTIC_FALLBACK"));
        RuleMatchExplanationService s = new RuleMatchExplanationService(builder, mapper, boundary, mock(AiCandidateParser.class), mock(GroundingChecker.class), renderer, enabled);
        guard = new AiCallGuard(settings, Clock.systemUTC());
        s.setCallGuard(guard);
        return s;
    }

    private static GuardSettings fast() {
        return new GuardSettings(1, Duration.ofMillis(150), 0, 50, Duration.ofSeconds(30), 8_000, 2_000);
    }

    @Test
    void p6_010_aHangingProviderFallsBackToTheDeterministicExplanationWithinTheTimeout() {
        CountDownLatch never = new CountDownLatch(1);
        RuleMatchExplanationService s = service(ctx -> { try { never.await(); } catch (InterruptedException ignored) { /* stuck provider */ } return Optional.empty(); }, true, fast());
        long start = System.nanoTime();
        RuleMatchExplanationService.ExplanationOutcome outcome = s.explainWithOutcome(matchId);
        assertThat(outcome.explanation().generatedBy()).isEqualTo("DETERMINISTIC_FALLBACK");
        assertThat(outcome.reason()).isEqualTo(RuleMatchExplanationService.REASON_AI_UNAVAILABLE);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        never.countDown();
    }

    @Test
    void p6_010_aThrowingProviderFallsBackAndTheCircuitProtectsLaterCalls() {
        AtomicInteger calls = new AtomicInteger();
        GuardSettings s2 = new GuardSettings(2, Duration.ofSeconds(2), 0, 2, Duration.ofSeconds(30), 8_000, 2_000);
        RuleMatchExplanationService s = service(ctx -> { calls.incrementAndGet(); throw new IllegalStateException("boom"); }, true, s2);
        for (int i = 0; i < 4; i++) assertThat(s.explain(matchId).generatedBy()).isEqualTo("DETERMINISTIC_FALLBACK");
        assertThat(calls.get()).isEqualTo(2); // the breaker opened after two failures; the provider was not called again
    }

    @Test
    void p6_008_aServiceWithoutConsentNeverCallsTheProvider() {
        AtomicInteger calls = new AtomicInteger();
        RuleMatchExplanationService s = service(ctx -> { calls.incrementAndGet(); return Optional.of("{}"); }, false, fast());
        RuleMatchExplanationService.ExplanationOutcome outcome = s.explainWithOutcome(matchId);
        assertThat(outcome.reason()).isEqualTo(RuleMatchExplanationService.REASON_AI_DISABLED);
        assertThat(calls.get()).isZero();
    }
}
