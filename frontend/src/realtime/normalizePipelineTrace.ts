import type { PipelineTracePayload as RealtimePipelineTracePayload } from './RealtimeEvents';
import type { PipelineTracePayload as CanonicalPipelineTracePayload } from '../repositories/types';

/**
 * The realtime PipelineTrace WebSocket payload and the REST
 * GET /pipeline/history response are the SAME shape - both are the
 * backend's persisted PipelineTrace bean, serialized via its getters
 * (RealtimePublisher.publishPipelineTrace broadcasts the raw trace object,
 * it does not build a separate DTO). This function exists as the one
 * normalization boundary the rest of the frontend already reads through
 * (PipelineCanvas/PipelineTimeline/CorrelationInspector never touch the
 * realtime payload directly) - it is a pass-through, not a field mapping,
 * because there is nothing to map.
 */
export function normalizeRealtimePipelineTrace(payload: RealtimePipelineTracePayload): CanonicalPipelineTracePayload {
  return {
    traceId: payload.traceId,
    correlationId: payload.correlationId,
    deviceId: payload.deviceId,
    stage: payload.stage,
    stageIndex: payload.stageIndex,
    previousStage: payload.previousStage,
    nextStage: payload.nextStage,
    startTimestamp: payload.startTimestamp,
    endTimestamp: payload.endTimestamp,
    durationMs: payload.durationMs,
    status: payload.status,
    payloadSize: payload.payloadSize,
    metadata: (payload.metadata && typeof payload.metadata === 'object') ? payload.metadata : {},
  };
}
