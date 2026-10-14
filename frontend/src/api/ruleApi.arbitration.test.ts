import { describe, it, expect, vi, beforeEach } from 'vitest';

const get = vi.fn(); const post = vi.fn(); const put = vi.fn(); const del = vi.fn();
vi.mock('./apiClient', () => ({ apiClient: { get: (...a: unknown[]) => get(...a), post: (...a: unknown[]) => post(...a), put: (...a: unknown[]) => put(...a), delete: (...a: unknown[]) => del(...a) } }));

import { ruleApi } from './ruleApi';

describe('P3.5 arbitration and exclusion-group API', () => {
  beforeEach(() => vi.clearAllMocks());

  it('reads the persisted arbitration evidence of one match', () => {
    void ruleApi.getArbitration('m-1');
    expect(get).toHaveBeenCalledWith('/rule-matches/history/m-1/arbitration');
  });

  it('configures groups through the additive endpoints with explicit ranks', () => {
    void ruleApi.listExclusionGroups();
    void ruleApi.createExclusionGroup('Climate');
    void ruleApi.setExclusionMember('g-1', 'r-1', 0);
    void ruleApi.removeExclusionMember('g-1', 'r-1');
    void ruleApi.deleteExclusionGroup('g-1');
    expect(get).toHaveBeenCalledWith('/rule-exclusion-groups');
    expect(post).toHaveBeenCalledWith('/rule-exclusion-groups', { name: 'Climate' });
    expect(put).toHaveBeenCalledWith('/rule-exclusion-groups/g-1/members/r-1', { rank: 0 });
    expect(del).toHaveBeenCalledWith('/rule-exclusion-groups/g-1/members/r-1');
    expect(del).toHaveBeenCalledWith('/rule-exclusion-groups/g-1');
  });
});
