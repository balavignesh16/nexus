import { describe, it, expect } from 'vitest';
import { normalizeRealtimePipelineTrace } from './normalizePipelineTrace';
import type { PipelineTracePayload as RealtimePipelineTracePayload } from './RealtimeEvents';

function realtimeTrace(overrides: Partial<RealtimePipelineTracePayload> = {}): RealtimePipelineTracePayload {
  return {
    traceId: 'trace-1',
    correlationId: 'corr-1',
    stage: 'TELEMETRY',
    deviceId: 'dev-1',
    enteredAt: 1700000000000,
    exitedAt: 1700000000123,
    latencyMs: 123,
    succeeded: true,
    stagePayload: { sensorType: 'TEMPERATURE_SENSOR', value: 21.5 },
    ...overrides,
  };
}

describe('normalizeRealtimePipelineTrace - realtime payload becomes a valid canonical trace', () => {
  it('passes real identity fields through unchanged', () => {
    const trace = normalizeRealtimePipelineTrace(realtimeTrace({ traceId: 't-9', correlationId: 'c-9', deviceId: 'dev-9', stage: 'TWIN' }));

    expect(trace.traceId).toBe('t-9');
    expect(trace.correlationId).toBe('c-9');
    expect(trace.deviceId).toBe('dev-9');
    expect(trace.stage).toBe('TWIN');
  });

  it('derives stageIndex/previousStage/nextStage from the real, established TELEMETRY/TWIN/ACTION order', () => {
    const telemetry = normalizeRealtimePipelineTrace(realtimeTrace({ stage: 'TELEMETRY' }));
    const twin = normalizeRealtimePipelineTrace(realtimeTrace({ stage: 'TWIN' }));
    const action = normalizeRealtimePipelineTrace(realtimeTrace({ stage: 'ACTION' }));

    expect(telemetry.stageIndex).toBe(0);
    expect(telemetry.previousStage).toBeNull();
    expect(telemetry.nextStage).toBe('TWIN');

    expect(twin.stageIndex).toBe(1);
    expect(twin.previousStage).toBe('TELEMETRY');
    expect(twin.nextStage).toBe('ACTION');

    expect(action.stageIndex).toBe(2);
    expect(action.previousStage).toBe('TWIN');
    expect(action.nextStage).toBeNull();
  });

  it('a stage outside the real known order sorts after the known stages, never a fabricated index', () => {
    const trace = normalizeRealtimePipelineTrace(realtimeTrace({ stage: 'MQTT_IN' }));

    expect(trace.stageIndex).toBe(3);
    expect(trace.previousStage).toBeNull();
    expect(trace.nextStage).toBeNull();
  });

  it('maps startTimestamp/endTimestamp/durationMs from the real enteredAt/exitedAt/latencyMs fields', () => {
    const trace = normalizeRealtimePipelineTrace(realtimeTrace({ enteredAt: 1000, exitedAt: 1500, latencyMs: 500 }));

    expect(trace.startTimestamp).toBe(1000);
    expect(trace.endTimestamp).toBe(1500);
    expect(trace.durationMs).toBe(500);
  });

  it('derives status SUCCESS from a real succeeded: true, never NaN/undefined', () => {
    const trace = normalizeRealtimePipelineTrace(realtimeTrace({ succeeded: true }));
    expect(trace.status).toBe('SUCCESS');
  });

  it('derives status FAILURE from a real succeeded: false', () => {
    const trace = normalizeRealtimePipelineTrace(realtimeTrace({ succeeded: false }));
    expect(trace.status).toBe('FAILURE');
  });

  it('derives a real payloadSize from the real stagePayload, never a fabricated number', () => {
    const trace = normalizeRealtimePipelineTrace(realtimeTrace({ stagePayload: { a: 1 } }));
    expect(trace.payloadSize).toBe(JSON.stringify({ a: 1 }).length);
    expect(trace.payloadSize).toBeGreaterThan(0);
  });

  it('maps a real object stagePayload into metadata unchanged', () => {
    const trace = normalizeRealtimePipelineTrace(realtimeTrace({ stagePayload: { sensorType: 'GAS_SENSOR', value: 12 } }));
    expect(trace.metadata).toEqual({ sensorType: 'GAS_SENSOR', value: 12 });
  });

  it('a non-object stagePayload results in an honest empty metadata object, never fabricated content', () => {
    const trace = normalizeRealtimePipelineTrace(realtimeTrace({ stagePayload: 'raw string payload' as any }));
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
