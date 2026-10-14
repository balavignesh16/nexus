import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ruleApi } from './ruleApi';
import { apiClient } from './apiClient';

vi.mock('./apiClient', async () => {
  const actual = await vi.importActual<typeof import('./apiClient')>('./apiClient');
  return {
    ...actual,
    apiClient: { ...actual.apiClient, get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
  };
});

describe('ruleApi - device-scoped rule filtering', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('getRules with a deviceId requests the device-scoped endpoint', () => {
    ruleApi.getRules('device-a');
    expect(apiClient.get).toHaveBeenCalledWith('/rules?deviceId=device-a');
  });

  it('getRules without a deviceId requests the unfiltered endpoint - preserves existing callers', () => {
    ruleApi.getRules();
    expect(apiClient.get).toHaveBeenCalledWith('/rules');
  });

  it('getRules encodes a deviceId containing special characters', () => {
    ruleApi.getRules('device/a b');
    expect(apiClient.get).toHaveBeenCalledWith('/rules?deviceId=device%2Fa%20b');
  });

  it('createRule POSTs the rule payload to /rules', () => {
    const rule = {
      name: 'Test',
      enabled: true,
      priority: 1,
      conditions: [{ field: 'DEVICE_ID' as const, operator: 'EQ' as const, expectedValue: 'device-a' }],
      actions: [{ actionType: 'CREATE_ALERT', parameters: {} }],
      metadata: {},
    };
    ruleApi.createRule(rule);
    expect(apiClient.post).toHaveBeenCalledWith('/rules', rule);
  });
});

describe('ruleApi - backend dry-run', () => {
  it('POSTs the typed request to /rules/dry-run and returns the backend answer untouched', async () => {
    const answer = { schemaVersion: 1, hypothetical: true };
    vi.mocked(apiClient.post).mockResolvedValueOnce(answer);
    const request = { mode: 'SUPPLIED_STATE' as const, versions: [{ ruleId: 'r1' }], trigger: { type: 'ACCEPTED_TELEMETRY' as const }, simulationTime: '2026-10-14T10:00:00.000Z',
      supplied: { twins: [{ capabilityKey: 'motion', valueType: 'BOOLEAN' as const, value: false, unit: null, observedAt: '2026-10-14T10:00:00.000Z' }] } };
    await expect(ruleApi.dryRun(request)).resolves.toBe(answer);
    expect(apiClient.post).toHaveBeenCalledWith('/rules/dry-run', request);
    expect((vi.mocked(apiClient.post).mock.calls.at(-1)![1] as typeof request).supplied.twins[0].value).toBe(false);
  });
});
