import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { Rule } from '../api/ruleApi';

const createRule = vi.fn();
const updateRule = vi.fn();
vi.mock('../api/ruleApi', () => ({ ruleApi: { createRule: (...a: unknown[]) => createRule(...a), updateRule: (...a: unknown[]) => updateRule(...a) } }));

import { ApiError } from '../api/apiClient';
import { describeSaveFailure, persistDefinition } from './ruleSave';
import type { CompiledDefinition } from './workflowCompiler';

const definition: CompiledDefinition = { name: 'New', enabled: false, priority: 10, conditions: [{ field: 'VALUE', operator: 'EQ', expectedValue: '0' }], actions: [{ actionType: 'CREATE_ALERT', parameters: {} }], metadata: { type: 'workflow' } };

describe('persistDefinition', () => {
  beforeEach(() => { createRule.mockReset(); updateRule.mockReset(); });

  it('creates a new rule through the backend and returns exactly what the backend answered (no fabricated id)', async () => {
    createRule.mockResolvedValue({ ...definition, ruleId: 'server-id', rowRevision: 1, lifecycle: 'PAUSED' });
    const saved = await persistDefinition(null, definition);
    expect(createRule).toHaveBeenCalledWith(definition);
    expect(updateRule).not.toHaveBeenCalled();
    expect(saved.ruleId).toBe('server-id');
    expect(saved.rowRevision).toBe(1);
  });

  it('updates an existing rule at its loaded revision and lets a rejection propagate untouched', async () => {
    const existing = { ...definition, ruleId: 'r1', rowRevision: 3 } as Rule;
    updateRule.mockRejectedValue(new ApiError(409, 'Conflict', { message: 'stale' }));
    await expect(persistDefinition(existing, definition)).rejects.toMatchObject({ status: 409 });
    expect(updateRule).toHaveBeenCalledWith('r1', expect.objectContaining({ ruleId: 'r1', rowRevision: 3 }));
    expect(createRule).not.toHaveBeenCalled();
  });
});

describe('describeSaveFailure', () => {
  it('explains a stale revision, lists backend validation details and reports plain errors', () => {
    expect(describeSaveFailure(new ApiError(409, 'x', {}))[0]).toMatch(/changed elsewhere/);
    expect(describeSaveFailure(new ApiError(400, 'x', { message: 'Rule validation failed', details: [{ path: 'a', message: 'b' }, { message: 'c' }] }))).toEqual(['Rule validation failed', 'a: b', 'c']);
    expect(describeSaveFailure(new Error('boom'))).toEqual(['boom']);
    expect(describeSaveFailure('weird')[0]).toMatch(/nothing was saved/);
  });
});
