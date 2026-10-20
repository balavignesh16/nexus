package com.nexus.intelligence.api;

import com.nexus.intelligence.retrieval.EvidenceResult;
import com.nexus.intelligence.retrieval.EvidenceRetrievalService;
import com.nexus.intelligence.retrieval.LookupRequest;
import com.nexus.intelligence.retrieval.LookupType;
import com.nexus.rule.ai.ExplanationRateLimiter;
import com.nexus.shared.exception.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * The read-only evidence lookups of the NEXUS Assistant over HTTP: GET only, twelve fixed lookup types, no write route. It exists only when
 * {@code nexus.assistant.enabled=true}, which is for a local or private environment: NEXUS has no user authentication, so anyone who can reach this endpoint can
 * read the facts it returns (the rate limiter is not an identity). No per-user authorisation is claimed.
 */
@RestController
@RequestMapping("/api/v1/assistant/evidence")
@ConditionalOnProperty(prefix = "nexus.assistant", name = "enabled", havingValue = "true")
public class AssistantEvidenceController {
    private final EvidenceRetrievalService retrieval;
    private final ExplanationRateLimiter limiter;

    public AssistantEvidenceController(EvidenceRetrievalService retrieval, ExplanationRateLimiter limiter) {
        this.retrieval = retrieval;
        this.limiter = limiter;
    }

    @GetMapping("/lookups")
    public List<String> catalogue() {
        return Arrays.stream(LookupType.values()).map(Enum::name).toList();
    }

    @GetMapping("/lookups/{type}")
    public EvidenceResult lookup(@PathVariable String type, @RequestParam(required = false) String deviceId, @RequestParam(required = false) String deviceName,
                                 @RequestParam(required = false) String ruleId, @RequestParam(required = false) String commandId, @RequestParam(required = false) String capabilityKey,
                                 @RequestParam(required = false) Integer lookbackHours, @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor,
                                 HttpServletRequest request) {
        LookupType lookup;
        try {
            lookup = LookupType.valueOf(type);
        } catch (IllegalArgumentException unknown) {
            throw new ValidationException("unknown lookup type");
        }
        limiter.acquire(request.getRemoteAddr());
        return retrieval.lookup(new LookupRequest(lookup, uuid(deviceId), deviceName, uuid(ruleId), uuid(commandId), capabilityKey, lookbackHours, limit, cursor));
    }

    private static UUID uuid(String text) {
        if (text == null) return null;
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException invalid) {
            throw new ValidationException("an identifier is not a UUID");
        }
    }
}
