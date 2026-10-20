package com.nexus.intelligence.retrieval;

/**
 * Bounds of the evidence retrieval layer ({@code nexus.assistant.*}). The assistant is OFF by default: {@code enabled} must be set explicitly, and only for a local
 * or private environment, because NEXUS has no user authentication and the lookups return device, rule and command facts to whoever can reach the endpoint.
 */
public record RetrievalSettings(boolean enabled, int maxLookupsPerQuestion, int maxRows, int maxLookbackHours, int maxEvidenceBytes, int statementTimeoutMillis, int maxConcurrent,
                                int totalTimeMillis, int freshnessSeconds, int futureSkewSeconds, int maxTextChars) {
    public RetrievalSettings {
        if (maxLookupsPerQuestion < 1 || maxLookupsPerQuestion > 20) throw new IllegalStateException("nexus.assistant.max-lookups-per-question must be 1 to 20");
        if (maxRows < 1 || maxRows > 1_000) throw new IllegalStateException("nexus.assistant.max-rows must be 1 to 1000");
        if (maxLookbackHours < 1 || maxLookbackHours > 720) throw new IllegalStateException("nexus.assistant.max-lookback-hours must be 1 to 720");
        if (maxEvidenceBytes < 1_024 || maxEvidenceBytes > 1_048_576) throw new IllegalStateException("nexus.assistant.max-evidence-bytes must be 1024 to 1048576");
        if (statementTimeoutMillis < 100 || statementTimeoutMillis > 30_000) throw new IllegalStateException("nexus.assistant.statement-timeout-millis must be 100 to 30000");
        if (maxConcurrent < 1 || maxConcurrent > 8) throw new IllegalStateException("nexus.assistant.max-concurrent must be 1 to 8 (each lookup holds one pool connection)");
        if (totalTimeMillis < 500 || totalTimeMillis > 120_000) throw new IllegalStateException("nexus.assistant.total-time-millis must be 500 to 120000");
        if (freshnessSeconds < 1 || freshnessSeconds > 604_800) throw new IllegalStateException("nexus.assistant.freshness-seconds must be 1 to 604800");
        if (futureSkewSeconds < 0 || futureSkewSeconds > 3_600) throw new IllegalStateException("nexus.assistant.future-skew-seconds must be 0 to 3600");
        if (maxTextChars < 20 || maxTextChars > 2_000) throw new IllegalStateException("nexus.assistant.max-text-chars must be 20 to 2000");
    }

    public static RetrievalSettings defaults() {
        return new RetrievalSettings(false, 6, 200, 168, 65_536, 2_000, 4, 8_000, 900, 300, 200);
    }
}
