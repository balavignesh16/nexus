package com.nexus.intelligence.model;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * The optional, explicitly configured provider (nexus.ai.enabled=true): an OpenAI-compatible chat completion that asks for a JSON object. It returns text
 * only, which the gateway treats as untrusted. Failures become fixed-code {@link ModelProviderException}s (transient only for 5xx and network errors); the
 * exception never carries response text, a URL or the key.
 */
@Component
@ConditionalOnProperty(name = "nexus.ai.enabled", havingValue = "true")
public class OpenAiModelProvider implements ModelProvider {
    private final RestClient client;
    private final String model;
    private final int maxOutputTokens;
    private final ProviderIdentity identity;

    public OpenAiModelProvider(RestClient aiRestClient,
                               @Value("${nexus.ai.model:gpt-4o-mini}") String model,
                               @Value("${nexus.ai.max-output-tokens:1024}") int maxOutputTokens,
                               @Value("${nexus.ai.base-url:https://api.openai.com/v1}") String baseUrl) {
        this.client = aiRestClient;
        this.model = model;
        this.maxOutputTokens = maxOutputTokens;
        // the host of the endpoint is configuration, not a secret; the key is never part of the identity
        this.identity = new ProviderIdentity("openai-compatible", model, ProviderIdentity.hashOf(model, baseUrl, String.valueOf(maxOutputTokens)));
    }

    @Override
    public ProviderIdentity identity() {
        return identity;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String complete(String prompt, int maxOutputChars) throws ModelProviderException {
        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", maxOutputTokens,
                "temperature", 0,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(Map.of("role", "user", "content", prompt)));
        try {
            Map<?, ?> response = client.post().uri("/chat/completions").body(body).retrieve().body(Map.class);
            String content = content(response);
            if (content == null) throw new ModelProviderException("NO_CONTENT", false);
            return content;
        } catch (HttpServerErrorException e) {
            throw new ModelProviderException("HTTP_5XX", true);
        } catch (HttpClientErrorException e) {
            throw new ModelProviderException("HTTP_" + e.getStatusCode().value(), false);
        } catch (ResourceAccessException e) {
            throw new ModelProviderException("NETWORK", true);
        } catch (RestClientException e) {
            throw new ModelProviderException("CLIENT_ERROR", false);
        }
    }

    private static String content(Map<?, ?> response) {
        if (response == null || !(response.get("choices") instanceof List<?> choices) || choices.isEmpty()) return null;
        if (!(choices.get(0) instanceof Map<?, ?> first) || !(first.get("message") instanceof Map<?, ?> message)) return null;
        return message.get("content") instanceof String s && !s.isBlank() ? s : null;
    }
}
