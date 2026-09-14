import type { PipelineTracePayload as RealtimePipelineTracePayload } from './RealtimeEvents';
import type { PipelineTracePayload as CanonicalPipelineTracePayload } from '../repositories/types';

// Step 6 audit: TELEMETRY/TWIN/ACTION are the only pipeline stages ever
// actually persisted by the backend - the same canonical order
// PipelineCanvas.tsx already treats as its real fallback stage sequence.
const REAL_STAGE_ORDER = ['TELEMETRY', 'TWIN', 'ACTION'];

/**
 * The realtime PipelineTrace WebSocket payload and the REST
 * GET /pipeline/history response are two different shapes for the same
 * real concept, but both feed the shared ['global-pipeline-traces'] cache.
 * Every realtime trace is normalized into the REST/canonical shape here,
 * once, at the boundary - never inside the consuming components
 * (PipelineCanvas/PipelineTimeline/CorrelationInspector), which only ever
 * read the canonical shape.
 *
 * Every field below is either passed through unchanged or derived from a
 * real field already present on the realtime payload - nothing is invented:
 *  - stageIndex/previousStage/nextStage: the realtime payload doesn't carry
 *    these, but they are a structural position within the same real,
 *    already-established stage order PipelineCanvas.tsx uses as its
 *    canonical fallback - not a fabricated business value. A stage outside
 *    that real order (never observed in production, per the Step 6 audit)
 *    sorts after the known real stages rather than being assigned a
 *    made-up index.
 *  - startTimestamp/endTimestamp: the realtime payload's own
 *    enteredAt/exitedAt.
 *  - durationMs: the realtime payload's own latencyMs.
 *  - status: derived from the realtime payload's own real `succeeded`
 *    boolean, mapped to the same SUCCESS/FAILURE vocabulary the REST
 *    contract and every consumer already use.
 *  - payloadSize: the real serialized byte length of the realtime
 *    payload's own stagePayload - not an invented number.
 *  - metadata: the realtime payload's own stagePayload, when it is a
 *    plain object - never fabricated.
 */
export function normalizeRealtimePipelineTrace(payload: RealtimePipelineTracePayload): CanonicalPipelineTracePayload {
  const stageIdx = REAL_STAGE_ORDER.indexOf(payload.stage);
  const stageIndex = stageIdx === -1 ? REAL_STAGE_ORDER.length : stageIdx;
  const previousStage = stageIdx > 0 ? REAL_STAGE_ORDER[stageIdx - 1] : null;
  const nextStage = stageIdx !== -1 && stageIdx < REAL_STAGE_ORDER.length - 1 ? REAL_STAGE_ORDER[stageIdx + 1] : null;

  const metadata = (payload.stagePayload && typeof payload.stagePayload === 'object')
    ? payload.stagePayload as Record<string, any>
    : {};

  return {
    traceId: payload.traceId,
    correlationId: payload.correlationId,
    deviceId: payload.deviceId,
    stage: payload.stage,
    stageIndex,
    previousStage,
    nextStage,
    startTimestamp: payload.enteredAt,
    endTimestamp: payload.exitedAt,
    durationMs: payload.latencyMs,
    status: payload.succeeded ? 'SUCCESS' : 'FAILURE',
    payloadSize: JSON.stringify(payload.stagePayload ?? null).length,
    metadata,
  };
}
