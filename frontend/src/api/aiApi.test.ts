import { describe, it, expect, vi, beforeEach } from 'vitest';
import { aiApi } from './aiApi';
import { apiClient } from './apiClient';

vi.mock('./apiClient', async () => {
  const actual = await vi.importActual<typeof import('./apiClient')>('./apiClient');
  return {
    ...actual,
    apiClient: { ...actual.apiClient, get: vi.fn() },
  };
});

describe('aiApi - real backend endpoints only, no fabricated data', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('getRuleMatchHistory calls the real durable rule-match history endpoint with the given limit', () => {
    aiApi.getRuleMatchHistory(25);
    expect(apiClient.get).toHaveBeenCalledWith('/rule-matches/history?limit=25');
  });

  it('getRuleMatchHistory defaults to limit=50', () => {
    aiApi.getRuleMatchHistory();
    expect(apiClient.get).toHaveBeenCalledWith('/rule-matches/history?limit=50');
  });

  it('explainRuleMatch calls the real evidence-grounded explanation endpoint for the given matchId', () => {
    aiApi.explainRuleMatch('match-123');
    expect(apiClient.get).toHaveBeenCalledWith('/ai/explanations/rule-match/match-123');
  });
});
