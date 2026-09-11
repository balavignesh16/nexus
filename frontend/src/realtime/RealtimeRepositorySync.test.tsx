import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { RealtimeMessage, RuleMatchedPayload } from './RealtimeEvents';

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
});
