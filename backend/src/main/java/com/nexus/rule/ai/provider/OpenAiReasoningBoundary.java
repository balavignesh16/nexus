package com.nexus.rule.ai.provider;

import com.nexus.rule.ai.AiExplanationContext;
import com.nexus.rule.ai.AiExplanationPromptBuilder;
import com.nexus.rule.ai.AiReasoningBoundary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The one real AiReasoningBoundary implementation (Tier 4 Phase 2B): an
 * OpenAI-compatible Chat Completions adapter using Spring's RestClient. No
 * SDK, no Spring AI - a plain HTTP POST with a strict JSON-schema
 * response-format declaration.
 *
 * This class never constructs or returns a RuleMatchExplanation - only
 * Optional<String> (the raw candidate JSON text extracted from the
 * provider's response envelope, or empty on any failure). Every
 * OpenAi-specific type (request/response records) is package-private and
 * confined to this package; nothing here is visible to
 * RuleMatchExplanationService, AiCandidateParser, GroundingChecker, or any
 * other caller.
 *
 * Retry policy: at most one retry, and only for a transient failure
 * (timeout/connection failure or HTTP 5xx). 4xx (including 401/403/429)
 * and malformed/oversized responses are never retried.
 */
@Component
@ConditionalOnProperty(name = "nexus.ai.enabled", havingValue = "true")
public class OpenAiReasoningBoundary implements AiReasoningBoundary {

    private static final Logger log = LoggerFactory.getLogger(OpenAiReasoningBoundary.class);
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final String SCHEMA_NAME = "rule_match_explanation";
    // A generous, purely defensive safety cap on the extracted candidate
    // text - the request already bounds output via max_tokens; this only
    // guards against an unexpectedly huge response being handed downstream.
    private static final int MAX_CONTENT_LENGTH = 50_000;

    private final RestClient restClient;
    private final AiExplanationPromptBuilder promptBuilder;
    private final String model;
    private final int maxOutputTokens;

    public OpenAiReasoningBoundary(RestClient aiRestClient,
                                    AiExplanationPromptBuilder promptBuilder,
                                    @Value("${nexus.ai.model:gpt-4o-mini}") String model,
                                    @Value("${nexus.ai.max-output-tokens:1024}") int maxOutputTokens) {
        this.restClient = aiRestClient;
        this.promptBuilder = promptBuilder;
        this.model = model;
        this.maxOutputTokens = maxOutputTokens;
    }

    @Override
    public Optional<String> generate(AiExplanationContext context) {
        OpenAiChatRequest request = buildRequest(context);

        Attempt first = attempt(request);
        if (first.content() != null) {
            return Optional.of(first.content());
        }
        if (!first.retryable()) {
            return Optional.empty();
        }

        log.info("Retrying OpenAI request once after a transient failure");
        Attempt retry = attempt(request);
        return Optional.ofNullable(retry.content());
    }

    private Attempt attempt(OpenAiChatRequest request) {
        long startedAt = System.currentTimeMillis();
        try {
            OpenAiChatResponse response = restClient.post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .body(request)
                    .retrieve()
                    .body(OpenAiChatResponse.class);

            String content = extractContent(response);
            long durationMs = System.currentTimeMillis() - startedAt;

            if (content == null) {
                log.warn("OpenAI response contained no usable content ({} ms)", durationMs);
                return Attempt.permanentFailure();
            }
            if (content.length() > MAX_CONTENT_LENGTH) {
                log.warn("OpenAI response content exceeded the safety size bound ({} chars); discarding", content.length());
                return Attempt.permanentFailure();
            }

            log.info("OpenAI request succeeded in {} ms", durationMs);
            return Attempt.success(content);
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            log.warn("OpenAI authentication/authorization failure ({}); check nexus.ai.api-key configuration", e.getStatusCode());
            return Attempt.permanentFailure();
        } catch (HttpClientErrorException.TooManyRequests e) {
            log.warn("OpenAI rate limit exceeded (429)");
            return Attempt.permanentFailure();
        } catch (HttpClientErrorException e) {
            log.warn("OpenAI request rejected: {}", e.getStatusCode());
            return Attempt.permanentFailure();
        } catch (HttpServerErrorException e) {
            log.warn("OpenAI server error: {}", e.getStatusCode());
            return Attempt.retryableFailure();
        } catch (ResourceAccessException e) {
            log.warn("OpenAI request failed (timeout or connection failure): {}", e.getMessage());
            return Attempt.retryableFailure();
        } catch (RestClientException e) {
            log.warn("OpenAI request failed unexpectedly: {}", e.getMessage());
            return Attempt.permanentFailure();
        }
    }

    private String extractContent(OpenAiChatResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            return null;
        }
        OpenAiChoice firstChoice = response.choices().get(0);
        if (firstChoice == null || firstChoice.message() == null) {
            return null;
        }
        String content = firstChoice.message().content();
        return (content == null || content.isBlank()) ? null : content;
    }

    private OpenAiChatRequest buildRequest(AiExplanationContext context) {
        List<OpenAiMessage> messages = List.of(
                new OpenAiMessage("system", promptBuilder.systemInstruction()),
                new OpenAiMessage("user", promptBuilder.userContent(context))
        );
        OpenAiResponseFormat responseFormat = new OpenAiResponseFormat(
                "json_schema",
                new OpenAiJsonSchema(SCHEMA_NAME, true, outputJsonSchema())
        );
        return new OpenAiChatRequest(model, messages, 0.0, maxOutputTokens, responseFormat);
    }

    /**
     * The strict JSON schema requested from the provider - deliberately
     * narrower than RuleMatchExplanation's full Java shape: actionNote and
     * generatedBy are not offered as fields at all (Tier 4 Phase 2A/2B
     * requirement - see RuleMatchExplanationService, which is solely
     * responsible for both).
     */
    private Map<String, Object> outputJsonSchema() {
        Map<String, Object> claimSchema = new LinkedHashMap<>();
        claimSchema.put("type", "object");
        claimSchema.put("properties", Map.of(
                "tier", Map.of("type", "string", "enum", List.of("FACT", "INFERENCE", "UNKNOWN")),
                "text", Map.of("type", "string"),
                "evidenceRef", Map.of("type", List.of("string", "null")),
                "assertedValue", Map.of("type", List.of("string", "number", "boolean", "null")),
                "supportingEvidenceRefs", Map.of("type", "array", "items", Map.of("type", "string"))
        ));
        claimSchema.put("required", List.of("tier", "text", "evidenceRef", "assertedValue", "supportingEvidenceRefs"));
        claimSchema.put("additionalProperties", false);

        Map<String, Object> rootSchema = new LinkedHashMap<>();
        rootSchema.put("type", "object");
        rootSchema.put("properties", Map.of(
                "matchId", Map.of("type", "string"),
                "summary", Map.of("type", "string"),
                "claims", Map.of("type", "array", "items", claimSchema)
        ));
        rootSchema.put("required", List.of("matchId", "summary", "claims"));
        rootSchema.put("additionalProperties", false);
        return rootSchema;
    }

    private record Attempt(String content, boolean retryable) {
        static Attempt success(String content) {
            return new Attempt(content, false);
        }

        static Attempt retryableFailure() {
            return new Attempt(null, true);
        }

        static Attempt permanentFailure() {
            return new Attempt(null, false);
        }
    }
}
