package com.nexus.intelligence.retrieval;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Binds {@code nexus.assistant.*}; an out-of-range value stops startup. The settings bean always exists; the service and endpoints exist only when enabled. */
@Configuration
public class RetrievalConfiguration {
    @Bean
    RetrievalSettings retrievalSettings(@Value("${nexus.assistant.enabled:false}") boolean enabled,
                                        @Value("${nexus.assistant.max-lookups-per-question:6}") int lookups,
                                        @Value("${nexus.assistant.max-rows:200}") int rows,
                                        @Value("${nexus.assistant.max-lookback-hours:168}") int lookback,
                                        @Value("${nexus.assistant.max-evidence-bytes:65536}") int bytes,
                                        @Value("${nexus.assistant.statement-timeout-millis:2000}") int statement,
                                        @Value("${nexus.assistant.max-concurrent:4}") int concurrent,
                                        @Value("${nexus.assistant.total-time-millis:8000}") int total,
                                        @Value("${nexus.assistant.freshness-seconds:900}") int freshness,
                                        @Value("${nexus.assistant.future-skew-seconds:300}") int skew,
                                        @Value("${nexus.assistant.max-text-chars:200}") int text) {
        return new RetrievalSettings(enabled, lookups, rows, lookback, bytes, statement, concurrent, total, freshness, skew, text);
    }
}
