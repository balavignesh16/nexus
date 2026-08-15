package com.nexus.pipeline.domain;

import java.util.UUID;
import java.util.Map;
import java.util.HashMap;

/**
 * A single trace entry for an execution stage in the NEXUS pipeline.
 * Represents the processing of a packet at a specific stage (Telemetry, Twin, Rule, etc.).
 */
public record PipelineTrace(
        String traceId,
        String correlationId,
        String deviceId,
        PipelineStage stage,
        int stageIndex,
        PipelineStage previousStage,
        PipelineStage nextStage,
        long startTimestamp,
        long endTimestamp,
        long durationMs,
        String status,
        int payloadSize,
        Map<String, Object> metadata
) {
    public static PipelineTrace of(
            String correlationId,
            String deviceId,
            PipelineStage stage,
            long startTimestamp,
            String status,
            Object payload,
            Map<String, Object> metadata
    ) {
        long endTimestamp = System.currentTimeMillis();
        int payloadSize = payload != null ? payload.toString().length() : 0; // naive size estimation
        
        return new PipelineTrace(
                UUID.randomUUID().toString(),
                correlationId,
                deviceId,
                stage,
                stage.getIndex(),
                stage.getPrevious(),
                stage.getNext(),
                startTimestamp,
                endTimestamp,
                endTimestamp - startTimestamp,
                status,
                payloadSize,
                metadata != null ? metadata : new HashMap<>()
        );
    }
}
