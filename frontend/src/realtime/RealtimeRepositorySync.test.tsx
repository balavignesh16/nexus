import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { RealtimeMessage, RuleMatchedPayload, TwinUpdatedPayload, PipelineTracePayload } from './RealtimeEvents';

// Mock RealtimeManager so tests can synthesize a message on any topic
// without a real STOMP/SockJS connection.
vi.mock('./RealtimeManager', () => {
  const handlers = new Map<string, Set<(msg: any) => void>>();
  return {
    TOPICS: {
      TELEMETRY: '/topic/telemetry',
      TWINS: '/topic/twins',
      EVENTS: '/topic/events',
      RULES: '/topic/rules',
      ACTIONS: '/topic/actions',
      HEALTH: '/topic/platform/health',
      PIPELINE: '/topic/pipeline',
    },
    RealtimeManager: {
      subscribe: vi.fn((topic: string, handler: (msg: any) => void) => {
        if (!handlers.has(topic)) handlers.set(topic, new Set());
        handlers.get(topic)!.add(handler);
        return () => handlers.get(topic)?.delete(handler);
      }),
      __emit: (topic: string, msg: any) => {
        handlers.get(topic)?.forEach(h => h(msg));
      },
    },
  };
});

// Imported after the mock so it binds to the mocked module.
import { RealtimeManager, TOPICS } from './RealtimeManager';
import { RealtimeRepositorySync } from './RealtimeRepositorySync';

function ruleMatchMessage(overrides: Partial<RuleMatchedPayload> = {}): RealtimeMessage<RuleMatchedPayload> {
  return {
    eventId: 'evt-1',
    eventType: 'RuleMatched',
    deviceId: 'device-1',
    correlationId: 'corr-1',
    sequenceNumber: 1,
    serverTimestamp: 1700000000000,
    source: 'BACKEND',
    version: 1,
    payload: {
      ruleId: 'rule-abc',
      ruleName: 'High Temperature',
      reason: 'value exceeded threshold',
      deviceId: 'device-1',
      ...overrides,
    },
  };
}

function pipelineTraceMessage(overrides: Partial<PipelineTracePayload> = {}): RealtimeMessage<PipelineTracePayload> {
  return {
    eventId: 'evt-pipe-1',
    eventType: 'PipelineTrace',
    deviceId: 'dev-1',
    correlationId: 'corr-pipe-1',
    sequenceNumber: 1,
    serverTimestamp: 1700000000000,
    source: 'BACKEND',
    version: 1,
    payload: {
      traceId: 'trace-1',
      correlationId: 'corr-pipe-1',
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
    },
  };
}

function twinUpdatedMessage(overrides: Partial<TwinUpdatedPayload> = {}): RealtimeMessage<TwinUpdatedPayload> {
  return {
    eventId: 'evt-twin-1',
    eventType: 'TwinUpdated',
    deviceId: 'dev-1',
    correlationId: 'corr-twin-1',
    sequenceNumber: 1,
    serverTimestamp: 1700000000000,
    source: 'BACKEND',
    version: 1,
    payload: {
      deviceId: 'dev-1',
      deltas: [{ property: 'value', oldValue: 20, newValue: 42, timestamp: 1700000000000 }],
      twinTimestamp: 1700000000000,
      ...overrides,
    },
  };
}

// Matches the authoritative backend shape: DigitalTwinResponse.java /
// GET /api/v1/twins - identified by `deviceId`, not `id`.
function realisticTwin(overrides: Record<string, any> = {}) {
  return {
    deviceId: 'dev-1',
    latestTimestamp: '2026-09-10T17:23:57.357686Z',
    latestSensorType: 'GAS_SENSOR',
    latestValue: 20,
    latestUnit: 'PPM',
    lastSeen: '2026-09-10T17:23:57.858832Z',
    quality: 'GOOD',
    metadata: {},
    ...overrides,
  };
}

// Matches the authoritative backend shape: GET /api/v1/devices/active -
// identified by `id`, not `deviceId`.
function realisticDevice(overrides: Record<string, any> = {}) {
  return {
    id: 'dev-1',
    spaceId: 'space-1',
    name: 'Gas Sensor 1',
    deviceType: 'GAS_SENSOR',
    status: 'ACTIVE',
    ...overrides,
  };
}

describe('RealtimeRepositorySync - TWINS topic', () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  });

  function renderSync() {
    return render(
      <QueryClientProvider client={queryClient}>
        <RealtimeRepositorySync />
      </QueryClientProvider>
    );
  }

  it('invalidates both ["twins", 12] and ["twins", 100] on any TwinUpdated event, so the next read is a real, fully-consistent refetch', () => {
    // The backend's TwinUpdated delta only ever reports "sensorType" and
    // "value" changes - it never includes "unit", "lastSeen", or
    // "latestTimestamp", even though those genuinely change too (a device
    // cycling between capabilities has a different unit per capability).
    // Hand-merging just sensorType/value onto a cached row therefore
    // produces a torn, internally-inconsistent record (observed in
    // production as e.g. sensorType "TEMPERATURE" paired with the
    // previous capability's leftover value and unit, like "1013.43
    // CELSIUS"). Invalidating and letting a real GET /twins refetch happen
    // is the only way every field on the row is guaranteed to agree.
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    renderSync();

    (RealtimeManager as any).__emit(TOPICS.TWINS, twinUpdatedMessage());

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['twins', 12] });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['twins', 100] });
  });

  it('never hand-writes latestSensorType/latestValue onto the cached twins array - no torn/partial row is ever produced client-side', () => {
    const before12 = [realisticTwin({ deviceId: 'dev-1', latestSensorType: 'PRESSURE', latestValue: 1013.9, latestUnit: 'HPA' })];
    const before100 = [realisticTwin({ deviceId: 'dev-1', latestSensorType: 'PRESSURE', latestValue: 1013.9, latestUnit: 'HPA' })];
    queryClient.setQueryData(['twins', 12], before12);
    queryClient.setQueryData(['twins', 100], before100);

    renderSync();
    (RealtimeManager as any).__emit(TOPICS.TWINS, twinUpdatedMessage({
      deviceId: 'dev-1',
      deltas: [{ property: 'sensorType', oldValue: 'PRESSURE', newValue: 'TEMPERATURE', timestamp: 1700000000000 }],
    }));

    // Cache data is untouched by this handler now - only invalidated.
    // React Query itself (mocked away here) is responsible for the actual
    // refetch; this handler must never locally patch a mismatched
    // sensorType/value/unit combination into place.
    expect(queryClient.getQueryData(['twins', 12])).toEqual(before12);
    expect(queryClient.getQueryData(['twins', 100])).toEqual(before100);
  });

  it('["activeDevices"] still uses id-based identity matching and is unaffected by the twin field mapping', () => {
    queryClient.setQueryData(['activeDevices'], [
      realisticDevice({ id: 'dev-1' }),
      realisticDevice({ id: 'dev-2' }),
    ]);

    renderSync();
    (RealtimeManager as any).__emit(TOPICS.TWINS, twinUpdatedMessage({
      deviceId: 'dev-1',
      deltas: [{ property: 'value', oldValue: 20, newValue: 42, timestamp: 1700000000000 }],
    }));

    const devices = queryClient.getQueryData<any[]>(['activeDevices'])!;
    const matched = devices.find(d => d.id === 'dev-1')!;
    const untouched = devices.find(d => d.id === 'dev-2')!;
    // Existing (unchanged) behavior: the raw delta property name is spread as-is.
    expect(matched.value).toBe(42);
    expect(untouched.value).toBeUndefined();
  });

  it('["all-devices"] still uses id-based identity matching and is unaffected by the twin field mapping', () => {
    queryClient.setQueryData(['all-devices'], [
      realisticDevice({ id: 'dev-1' }),
      realisticDevice({ id: 'dev-2' }),
    ]);

    renderSync();
    (RealtimeManager as any).__emit(TOPICS.TWINS, twinUpdatedMessage({
      deviceId: 'dev-1',
      deltas: [{ property: 'value', oldValue: 20, newValue: 42, timestamp: 1700000000000 }],
    }));

    const devices = queryClient.getQueryData<any[]>(['all-devices'])!;
    const matched = devices.find(d => d.id === 'dev-1')!;
    const untouched = devices.find(d => d.id === 'dev-2')!;
    expect(matched.value).toBe(42);
    expect(untouched.value).toBeUndefined();
  });

  it('invalidates twins whether or not the device was already present in the cache - a twin missing entirely is never silently dropped', () => {
    // No initial setQueryData for either key - simulates a page where
    // useLatestTwins hasn't fetched yet, or a device whose first-ever
    // update arrives before any REST snapshot caught it.
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries');
    renderSync();

    expect(() => (RealtimeManager as any).__emit(TOPICS.TWINS, twinUpdatedMessage({ deviceId: 'dev-1' }))).not.toThrow();

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['twins', 12] });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ['twins', 100] });
  });
});

describe('RealtimeRepositorySync - RULES topic', () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  });

  function renderSync() {
    return render(
      <QueryClientProvider client={queryClient}>
        <RealtimeRepositorySync />
      </QueryClientProvider>
    );
  }

  it('subscribes to the RULES topic', () => {
    renderSync();
    expect(RealtimeManager.subscribe).toHaveBeenCalledWith(TOPICS.RULES, expect.any(Function));
  });

  it('annotates the matching rule in the ["global-rules"] cache on a rule-match message', () => {
    queryClient.setQueryData(['global-rules'], [
      { ruleId: 'rule-abc', name: 'High Temperature', enabled: true, priority: 1 },
      { ruleId: 'rule-xyz', name: 'Low Battery', enabled: true, priority: 2 },
    ]);

    renderSync();

    (RealtimeManager as any).__emit(TOPICS.RULES, ruleMatchMessage());

    const rules = queryClient.getQueryData<any[]>(['global-rules'])!;
    const matched = rules.find(r => r.ruleId === 'rule-abc');
    const untouched = rules.find(r => r.ruleId === 'rule-xyz');

    expect(matched.lastMatchedAt).toBe(1700000000000);
    expect(matched.lastMatchReason).toBe('value exceeded threshold');
    expect(untouched.lastMatchedAt).toBeUndefined();
  });

  it('ignores a malformed rule-match payload without a ruleId', () => {
    queryClient.setQueryData(['global-rules'], [
      { ruleId: 'rule-abc', name: 'High Temperature', enabled: true, priority: 1 },
    ]);

    renderSync();

    (RealtimeManager as any).__emit(TOPICS.RULES, ruleMatchMessage({ ruleId: undefined as any }));

    const rules = queryClient.getQueryData<any[]>(['global-rules'])!;
    expect(rules[0].lastMatchedAt).toBeUndefined();
  });

  // Step 9B regression: RulesPage queries ['global-rules', deviceFilter]
  // (a device-filtered cache entry) while DiagnosticsTab queries the bare
  // ['global-rules']. Before this fix, only the exact bare key ever got
  // annotated - a filtered RulesPage instance never received the
  // realtime match annotation at all.
  it('annotates the matching rule in a device-filtered ["global-rules", deviceId] cache entry (unfiltered RulesPage)', () => {
    queryClient.setQueryData(['global-rules', ''], [
      { ruleId: 'rule-abc', name: 'High Temperature', enabled: true, priority: 1 },
    ]);

    renderSync();
    (RealtimeManager as any).__emit(TOPICS.RULES, ruleMatchMessage());

    const rules = queryClient.getQueryData<any[]>(['global-rules', ''])!;
    expect(rules[0].lastMatchedAt).toBe(1700000000000);
    expect(rules[0].lastMatchReason).toBe('value exceeded threshold');
  });

  it('annotates the matching rule in a real device-scoped ["global-rules", "dev-1"] cache entry (filtered RulesPage)', () => {
    queryClient.setQueryData(['global-rules', 'dev-1'], [
      { ruleId: 'rule-abc', name: 'High Temperature', enabled: true, priority: 1 },
    ]);

    renderSync();
    (RealtimeManager as any).__emit(TOPICS.RULES, ruleMatchMessage());

    const rules = queryClient.getQueryData<any[]>(['global-rules', 'dev-1'])!;
    expect(rules[0].lastMatchedAt).toBe(1700000000000);
  });

  it('annotates every currently-cached rules view at once - bare, unfiltered, and filtered - without duplicating data', () => {
    queryClient.setQueryData(['global-rules'], [{ ruleId: 'rule-abc', name: 'High Temperature', enabled: true, priority: 1 }]);
    queryClient.setQueryData(['global-rules', ''], [{ ruleId: 'rule-abc', name: 'High Temperature', enabled: true, priority: 1 }]);
    queryClient.setQueryData(['global-rules', 'dev-1'], [{ ruleId: 'rule-abc', name: 'High Temperature', enabled: true, priority: 1 }]);

    renderSync();
    (RealtimeManager as any).__emit(TOPICS.RULES, ruleMatchMessage());

    expect(queryClient.getQueryData<any[]>(['global-rules'])![0].lastMatchedAt).toBe(1700000000000);
    expect(queryClient.getQueryData<any[]>(['global-rules', ''])![0].lastMatchedAt).toBe(1700000000000);
    expect(queryClient.getQueryData<any[]>(['global-rules', 'dev-1'])![0].lastMatchedAt).toBe(1700000000000);
  });

  it('does not create a cache entry for a rules view that was never fetched', () => {
    renderSync();

    expect(() => (RealtimeManager as any).__emit(TOPICS.RULES, ruleMatchMessage())).not.toThrow();
    expect(queryClient.getQueryData(['global-rules', 'never-fetched'])).toBeUndefined();
  });
});

describe('RealtimeRepositorySync - PIPELINE topic (realtime trace normalization)', () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  });

  function renderSync() {
    return render(
      <QueryClientProvider client={queryClient}>
        <RealtimeRepositorySync />
      </QueryClientProvider>
    );
  }

  it('normalizes a realtime pipeline trace into the canonical REST shape before it enters the shared cache', () => {
    renderSync();
    (RealtimeManager as any).__emit(TOPICS.PIPELINE, pipelineTraceMessage({
      stage: 'TWIN', stageIndex: 1, previousStage: 'TELEMETRY', nextStage: 'ACTION',
    }));

    const traces = queryClient.getQueryData<any[]>(['global-pipeline-traces'])!;
    expect(traces).toHaveLength(1);

    const trace = traces[0];
    expect(trace.stageIndex).toBe(1);
    expect(trace.previousStage).toBe('TELEMETRY');
    expect(trace.nextStage).toBe('ACTION');
    expect(trace.startTimestamp).toBe(1700000000000);
    expect(trace.endTimestamp).toBe(1700000000123);
    expect(trace.durationMs).toBe(123);
    expect(trace.status).toBe('SUCCESS');
    expect(typeof trace.payloadSize).toBe('number');
    expect(Number.isNaN(trace.payloadSize)).toBe(false);
    expect(trace.metadata).toEqual({ sensorType: 'TEMPERATURE', value: 21.5 });
  });

  it('prepends a normalized trace onto real REST-seeded traces without breaking their existing shape', () => {
    const restTrace = {
      traceId: 'rest-trace-1',
      correlationId: 'corr-rest-1',
      deviceId: 'dev-2',
      stage: 'ACTION',
      stageIndex: 2,
      previousStage: 'TWIN',
      nextStage: null,
      startTimestamp: 1600000000000,
      endTimestamp: 1600000000050,
      durationMs: 50,
      status: 'SUCCESS',
      payloadSize: 42,
      metadata: { foo: 'bar' },
    };
    queryClient.setQueryData(['global-pipeline-traces'], [restTrace]);

    renderSync();
    (RealtimeManager as any).__emit(TOPICS.PIPELINE, pipelineTraceMessage());

    const traces = queryClient.getQueryData<any[]>(['global-pipeline-traces'])!;
    expect(traces).toHaveLength(2);
    expect(traces[0].traceId).toBe('trace-1'); // the new realtime trace, prepended
    expect(traces[1]).toEqual(restTrace); // the real REST trace, untouched
  });

  it('a FAILURE trace never renders as SUCCESS, undefined, or NaN', () => {
    renderSync();
    (RealtimeManager as any).__emit(TOPICS.PIPELINE, pipelineTraceMessage({ status: 'FAILURE' }));

    const trace = queryClient.getQueryData<any[]>(['global-pipeline-traces'])![0];
    expect(trace.status).toBe('FAILURE');
  });
});
