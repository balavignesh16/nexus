package com.nexus.intelligence.model;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/** Wires the bounded model boundary. All limits are validated at startup; the defaults are conservative and nothing here enables a provider. */
@Configuration
public class ModelConfiguration {
    @Bean
    GuardSettings aiGuardSettings(@Value("${nexus.ai.guard.max-concurrent:4}") int maxConcurrent,
                                  @Value("${nexus.ai.guard.timeout-ms:8000}") long timeoutMs,
                                  @Value("${nexus.ai.guard.max-retries:1}") int maxRetries,
                                  @Value("${nexus.ai.guard.breaker-failures:5}") int breakerFailures,
                                  @Value("${nexus.ai.guard.breaker-open-seconds:30}") long breakerOpenSeconds,
                                  @Value("${nexus.ai.guard.max-context-chars:8000}") int maxContextChars,
                                  @Value("${nexus.ai.guard.max-output-chars:20000}") int maxOutputChars) {
        return new GuardSettings(maxConcurrent, Duration.ofMillis(timeoutMs), maxRetries, breakerFailures, Duration.ofSeconds(breakerOpenSeconds), maxContextChars, maxOutputChars);
    }

    @Bean(destroyMethod = "close")
    AiCallGuard aiCallGuard(GuardSettings settings, ObjectProvider<Clock> clock) {
        return new AiCallGuard(settings, clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    ModelGateway modelGateway(ModelProvider provider, AiCallGuard guard, GuardSettings settings,
                              @Value("${nexus.ai.send-device-evidence:false}") boolean sendDeviceEvidence) {
        return new ModelGateway(provider, guard, settings, sendDeviceEvidence);
    }
}
