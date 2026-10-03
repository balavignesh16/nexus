import { describe, it, expect } from 'vitest';
import { normalizeRealtimePipelineTrace } from './normalizePipelineTrace';
import type { PipelineTracePayload as RealtimePipelineTracePayload } from './RealtimeEvents';

function realtimeTrace(overrides: Partial<RealtimePipelineTracePayload> = {}): RealtimePipelineTracePayload {
  return {
    traceId: 'trace-1',
    correlationId: 'corr-1',
    stage: 'TELEMETRY',
    deviceId: 'dev-1',
    stageIndex: 0,
    previousStage: null,
    nextStage: 'TWIN',
    startTimestamp: 1700000000000,
    endTimestamp: 1700000000123,
    durationMs: 123,
    status: 'SUCCESS',
    payloadSize: 42,
    metadata: { sensorType: 'TEMPERATURE', value: 21.5 },
    ...overrides,
  };
}

describe('normalizeRealtimePipelineTrace - realtime payload is the same shape the backend persists', () => {
  it('passes real identity fields through unchanged', () => {
    const trace = normalizeRealtimePipelineTrace(realtimeTrace({ traceId: 't-9', correlationId: 'c-9', deviceId: 'dev-9', stage: 'TWIN' }));

    expect(trace.traceId).toBe('t-9');
    expect(trace.correlationId).toBe('c-9');
    expect(trace.deviceId).toBe('dev-9');
    expect(trace.stage).toBe('TWIN');
  });

  it('passes stageIndex/previousStage/nextStage through unchanged - the backend already computes these', () => {
    const trace = normalizeRealtimePipelineTrace(realtimeTrace({ stageIndex: 1, previousStage: 'TELEMETRY', nextStage: 'ACTION' }));

    expect(trace.stageIndex).toBe(1);
    expect(trace.previousStage).toBe('TELEMETRY');
    expect(trace.nextStage).toBe('ACTION');
  });

  it('passes startTimestamp/endTimestamp/durationMs through unchanged', () => {
    const trace = normalizeRealtimePipelineTrace(realtimeTrace({ startTimestamp: 1000, endTimestamp: 1500, durationMs: 500 }));

    expect(trace.startTimestamp).toBe(1000);
    expect(trace.endTimestamp).toBe(1500);
    expect(trace.durationMs).toBe(500);
  });

  it('passes a real status string through unchanged, never derived from a nonexistent boolean', () => {
    expect(normalizeRealtimePipelineTrace(realtimeTrace({ status: 'SUCCESS' })).status).toBe('SUCCESS');
    expect(normalizeRealtimePipelineTrace(realtimeTrace({ status: 'FAILURE' })).status).toBe('FAILURE');
  });

  it('passes the real payloadSize through unchanged', () => {
    const trace = normalizeRealtimePipelineTrace(realtimeTrace({ payloadSize: 17 }));
    expect(trace.payloadSize).toBe(17);
  });

  it('passes a real object metadata through unchanged', () => {
    const trace = normalizeRealtimePipelineTrace(realtimeTrace({ metadata: { sensorType: 'GAS', value: 12 } }));
    expect(trace.metadata).toEqual({ sensorType: 'GAS', value: 12 });
  });

  it('a non-object metadata results in an honest empty object, never fabricated content', () => {
    const trace = normalizeRealtimePipelineTrace(realtimeTrace({ metadata: 'raw string' as any }));
    expect(trace.metadata).toEqual({});
  });

  it('produces no undefined/NaN on any field a consumer reads for ordering/liveness', () => {
    const trace = normalizeRealtimePipelineTrace(realtimeTrace());

    expect(trace.stageIndex).not.toBeNaN();
    expect(trace.durationMs).not.toBeNaN();
    expect(trace.startTimestamp).not.toBeNaN();
    expect(trace.endTimestamp).not.toBeNaN();
    expect(trace.status).not.toBeUndefined();
    expect(trace.payloadSize).not.toBeUndefined();
    expect(trace.metadata).not.toBeUndefined();
  });
});
