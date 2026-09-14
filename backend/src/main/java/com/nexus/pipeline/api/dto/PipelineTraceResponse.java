package com.nexus.pipeline.api.dto;

import com.nexus.pipeline.domain.PipelineTrace;

import java.util.Map;

/**
 * Purpose-built DTO for pipeline trace history (Phase 3) - the controller
 * previously returned the raw PipelineTrace JPA entity directly. Mirrors
 * every other NEXUS list-endpoint DTO convention (see EventResponse,
 * ActionHistoryResponse). startTimestamp/endTimestamp are deliberately kept
 * as epoch-millisecond longs (not Instant) - this is exactly what the raw
 * entity's own getStartTimestamp()/getEndTimestamp() already returned and
 * serialized as, and the existing frontend (PipelineCanvas.tsx) does real
 * numeric arithmetic on these fields (`endTimestamp > Date.now() - 5000`);
 * switching to Instant would silently change the wire format to an ISO
 * string and break that comparison.
 */
public record PipelineTraceResponse(
        String traceId,
        String correlationId,
        String deviceId,
        String stage,
        long startTimestamp,
        long endTimestamp,
        long durationMs,
        String status,
        Map<String, Object> metadata
) {
    public static PipelineTraceResponse from(PipelineTrace trace) {
        return new PipelineTraceResponse(
                trace.getTraceId(),
                trace.getCorrelationId(),
                trace.getDeviceId(),
                trace.getStage() != null ? trace.getStage().name() : null,
                trace.getStartTimestamp(),
                trace.getEndTimestamp(),
                trace.getDurationMs(),
                trace.getStatus(),
                trace.getMetadata()
        );
    }
}
