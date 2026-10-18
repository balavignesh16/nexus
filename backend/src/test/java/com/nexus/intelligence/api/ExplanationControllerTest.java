package com.nexus.intelligence.api;

import com.nexus.intelligence.evidence.EvidenceBundle;
import com.nexus.intelligence.explain.CommandExplainer;
import com.nexus.intelligence.explain.Explanation;
import com.nexus.intelligence.explain.ExplanationKind;
import com.nexus.intelligence.explain.RuleNonMatchExplainer;
import com.nexus.rule.ai.ExplanationRateLimiter;
import com.nexus.rule.ai.RateLimitExceededException;
import com.nexus.shared.exception.ResourceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Ledger P6-036: the explanation endpoints are rate limited (the same limiter as the rule-match explanations) and never leak a permit. */
class ExplanationControllerTest {
    private final CommandExplainer commands = mock(CommandExplainer.class);
    private final RuleNonMatchExplainer rules = mock(RuleNonMatchExplainer.class);

    private HttpServletRequest from(String ip) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(ip);
        return request;
    }

    @Test
    void p6_036_aCallerBeyondItsBurstIsRefusedAndOthersAreNot() {
        var limiter = new ExplanationRateLimiter(60, 2, 4);
        var controller = new ExplanationController(commands, rules, limiter);
        UUID id = UUID.randomUUID();
        when(commands.explain(any(UUID.class))).thenReturn(Optional.of(new CommandExplainer.Result(new EvidenceBundle(),
                Explanation.abstention(ExplanationKind.COMMAND_LIFECYCLE, "c", "test", Instant.EPOCH))));
        assertEquals(200, controller.command(id, from("10.0.0.1")).getStatusCode().value());
        assertEquals(200, controller.command(id, from("10.0.0.1")).getStatusCode().value());
        assertThrows(RateLimitExceededException.class, () -> controller.command(id, from("10.0.0.1")), "the third call within the burst window");
        assertEquals(200, controller.command(id, from("10.0.0.2")).getStatusCode().value(), "another caller has its own bucket");
    }

    @Test
    void p6_036_aNotFoundOrAFailureReleasesItsConcurrencyPermit() {
        var limiter = new ExplanationRateLimiter(6000, 6000, 1);       // a single concurrent permit
        var controller = new ExplanationController(commands, rules, limiter);
        when(commands.explain(any(UUID.class))).thenReturn(Optional.empty());
        for (int i = 0; i < 20; i++) assertThrows(ResourceNotFoundException.class, () -> controller.command(UUID.randomUUID(), from("10.0.0.3")));
        when(commands.explain(any(UUID.class))).thenThrow(new IllegalStateException("boom"));
        for (int i = 0; i < 5; i++) assertThrows(IllegalStateException.class, () -> controller.command(UUID.randomUUID(), from("10.0.0.3")));
        when(commands.explain(any(UUID.class))).thenReturn(Optional.of(new CommandExplainer.Result(new EvidenceBundle(),
                Explanation.abstention(ExplanationKind.COMMAND_LIFECYCLE, "c", "test", Instant.EPOCH))));
        assertEquals(200, controller.command(UUID.randomUUID(), from("10.0.0.3")).getStatusCode().value(), "no permit leaked by the earlier failures");
    }
}
