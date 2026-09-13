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
