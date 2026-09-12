package com.nexus.rule.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Wires the AI provider's HTTP client (Tier 4 Phase 2B). This bean, and
 * therefore OpenAiReasoningBoundary itself (which depends on it), is only
 * ever constructed when nexus.ai.enabled=true - with AI disabled (the
 * default), neither exists in the application context, and
 * NoOpAiReasoningBoundary is used instead. A missing/blank API key does
 * not prevent this bean, or the application, from starting - it only
 * causes the provider to reject the request (HTTP 401), handled as a
 * normal per-request failure by OpenAiReasoningBoundary.
 */
@Configuration
public class AiConfiguration {

    private static final String OPENAI_BASE_URL = "https://api.openai.com/v1";

    @Bean
    @ConditionalOnProperty(name = "nexus.ai.enabled", havingValue = "true")
    public RestClient aiRestClient(@Value("${nexus.ai.api-key:}") String apiKey,
                                    @Value("${nexus.ai.timeout-ms:8000}") int timeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMs);
        requestFactory.setReadTimeout(timeoutMs);

        return RestClient.builder()
                .baseUrl(OPENAI_BASE_URL)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .requestFactory(requestFactory)
                .build();
    }
}
