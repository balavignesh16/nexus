package com.nexus.intelligence.api;

import com.nexus.intelligence.assistant.AssistantAnswer;
import com.nexus.intelligence.assistant.AssistantService;
import com.nexus.rule.ai.ExplanationRateLimiter;
import com.nexus.shared.exception.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ask the NEXUS Assistant a question. The only route is POST /ask, which reads stored facts and keeps a little in-memory conversation state; it writes no rule,
 * command, approval, draft or device state. Present only when {@code nexus.assistant.enabled=true}. Unauthenticated like every NEXUS endpoint, so rate limited
 * (the limiter is not an identity); anyone who can reach it can read what the lookups return, which is why it is for local or private environments only.
 */
@RestController
@RequestMapping("/api/v1/assistant")
@ConditionalOnProperty(prefix = "nexus.assistant", name = "enabled", havingValue = "true")
public class AssistantController {
    public record Request(String conversationId, String question) {}

    private final AssistantService assistant;
    private final ExplanationRateLimiter limiter;

    public AssistantController(AssistantService assistant, ExplanationRateLimiter limiter) {
        this.assistant = assistant;
        this.limiter = limiter;
    }

    @PostMapping("/ask")
    public AssistantAnswer ask(@RequestBody Request request, HttpServletRequest http) {
        if (request == null) throw new ValidationException("a question is required");
        limiter.acquire(http.getRemoteAddr());
        try {
            return assistant.ask(request.conversationId(), request.question());
        } catch (IllegalArgumentException invalid) {
            throw new ValidationException(invalid.getMessage());
        } finally {
            limiter.release();
        }
    }
}
