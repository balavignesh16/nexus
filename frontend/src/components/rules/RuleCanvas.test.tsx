import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { ReactFlowProvider } from 'reactflow';
import type { Rule } from '../../api/ruleApi';

const createRule = vi.fn();
const updateRule = vi.fn();
const dryRun = vi.fn();
vi.mock('../../api/ruleApi', () => ({ ruleApi: { createRule: (...a: unknown[]) => createRule(...a), updateRule: (...a: unknown[]) => updateRule(...a), dryRun: (...a: unknown[]) => dryRun(...a) } }));
vi.mock('./RulePalette', () => ({ RulePalette: () => null }));
vi.mock('./RuleInspector', () => ({ RuleInspector: () => null }));
vi.mock('./RuleSimulator', () => ({ RuleSimulator: (props: { inlineDefinition?: { conditions: unknown[] } }) => <div data-testid="simulator">{props.inlineDefinition ? `inline:${props.inlineDefinition.conditions.length}` : 'none'}</div> }));

import { ApiError } from '../../api/apiClient';
import { RuleCanvas } from './RuleCanvas';

const stored: Rule = {
  ruleId: 'rule-1', name: 'Stored rule', enabled: true, priority: 4, metadata: { owner: 'ops' }, cooldownSeconds: 30, triggers: [{ type: 'ACCEPTED_TELEMETRY' }], repeatPolicy: 'EDGE_TRUE',
  lifecycle: 'ACTIVE', rowRevision: 7,
  conditions: [
    { field: 'DEVICE_ID', operator: 'EQ', expectedValue: 'dev-1' },
    { field: 'VALUE', operator: 'GT', expectedValue: '30', capabilityType: 'TEMPERATURE', unit: 'CELSIUS', conditionId: 'h1', hysteresis: { exit: '25' } },
    { field: 'SENSOR_TYPE', operator: 'EQ', expectedValue: '' },
  ],
  actions: [{ actionType: 'SEND_COMMAND', parameters: { value: false } }],
};

function mount(rule: Rule | null = stored, onSaved = vi.fn()) {
  vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} });
  render(<ReactFlowProvider><RuleCanvas initialRule={rule} onSaved={onSaved} /></ReactFlowProvider>);
  return onSaved;
}
const save = () => fireEvent.click(screen.getByRole('button', { name: /^save$/i }));

describe('RuleCanvas persists through the real backend and claims success only from its response', () => {
  beforeEach(() => { createRule.mockReset(); updateRule.mockReset(); dryRun.mockReset(); });
  afterEach(() => vi.unstubAllGlobals());

  it('saves an existing rule with its loaded revision, preserving every condition, action and rule-level setting', async () => {
    updateRule.mockResolvedValue({ ...stored, rowRevision: 8 });
    const onSaved = mount();
    expect(screen.getByText('ACTIVE')).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    save();
    await waitFor(() => expect(updateRule).toHaveBeenCalledTimes(1));
    const [id, payload] = updateRule.mock.calls[0] as [string, Rule];
    expect(id).toBe('rule-1');
    expect(payload.rowRevision).toBe(7);                          // the revision it was loaded at: a concurrent edit is refused, not overwritten
    expect(payload.conditions).toEqual(stored.conditions);        // capability type, unit, hysteresis, conditionId and the empty string all survive
    expect(payload.actions).toEqual(stored.actions);              // including the boolean false inside the parameters
    expect(payload).toMatchObject({ name: 'Stored rule', enabled: true, priority: 4, cooldownSeconds: 30, repeatPolicy: 'EDGE_TRUE', triggers: [{ type: 'ACCEPTED_TELEMETRY' }] });
    expect(payload.metadata.owner).toBe('ops');
    expect(createRule).not.toHaveBeenCalled();
    expect(await screen.findByRole('status')).toHaveTextContent('Saved by the backend (revision 8)');
    expect(onSaved).toHaveBeenCalledWith({ ...stored, rowRevision: 8 });
  });

  it('uses the NEW revision for the next save (the response, not a local guess)', async () => {
    updateRule.mockResolvedValueOnce({ ...stored, rowRevision: 8 }).mockResolvedValueOnce({ ...stored, rowRevision: 9 });
    mount();
    save();
    await waitFor(() => expect(updateRule).toHaveBeenCalledTimes(1));
    await screen.findByRole('status');
    save();
    await waitFor(() => expect(updateRule).toHaveBeenCalledTimes(2));
    expect((updateRule.mock.calls[1][1] as Rule).rowRevision).toBe(8);
  });

  it('shows no success while the save is pending or after it is rejected', async () => {
    let reject!: (e: unknown) => void;
    updateRule.mockReturnValue(new Promise((_, r) => { reject = r; }));
    const onSaved = mount();
    save();
    await waitFor(() => expect(updateRule).toHaveBeenCalled());
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^save$/i })).toBeDisabled();
    reject(new ApiError(400, 'Bad Request', { message: 'Rule validation failed', details: [{ path: 'conditions[1]', message: 'Unit mismatch' }] }));
    expect(await screen.findByText('Rule validation failed')).toBeInTheDocument();
    expect(screen.getByText('conditions[1]: Unit mismatch')).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(onSaved).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: /^save$/i })).toBeEnabled();
  });

  it('reports a stale revision (409) as not saved and keeps the loaded revision', async () => {
    updateRule.mockRejectedValue(new ApiError(409, 'Conflict', { message: 'Revision mismatch' }));
    const onSaved = mount();
    save();
    expect(await screen.findByText(/changed elsewhere \(stale revision\)\. Nothing was saved/)).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    expect(onSaved).not.toHaveBeenCalled();
    updateRule.mockResolvedValue({ ...stored, rowRevision: 8 });
    save();
    await waitFor(() => expect(updateRule).toHaveBeenCalledTimes(2));
    expect((updateRule.mock.calls[1][1] as Rule).rowRevision).toBe(7);
  });

  it('reports an outage as not saved', async () => {
    updateRule.mockRejectedValue(new Error('Network down'));
    mount();
    save();
    expect(await screen.findByText('Network down')).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('never calls the backend for a graph that is not valid (an empty canvas)', async () => {
    mount(null);
    expect(screen.getByText('UNSAVED')).toBeInTheDocument();
    save();
    expect(await screen.findByText('Graph is empty.')).toBeInTheDocument();
    expect(createRule).not.toHaveBeenCalled();
    expect(updateRule).not.toHaveBeenCalled();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('adds a starting trigger locally without saving or activating a rule', async () => {
    mount(null);
    expect(screen.getByText('Build your first rule')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button',{name:'Add event trigger'}));
    expect(screen.queryByText('Build your first rule')).not.toBeInTheDocument();
    expect(screen.getByText('On event')).toBeInTheDocument();
    expect(createRule).not.toHaveBeenCalled();
    expect(updateRule).not.toHaveBeenCalled();
    expect(dryRun).not.toHaveBeenCalled();
  });

  it('hands the simulator the unsaved graph as an inline definition, or tells the user to fix the graph', async () => {
    mount();
    fireEvent.click(screen.getByRole('button', { name: /simulate/i }));
    expect(await screen.findByTestId('simulator')).toHaveTextContent('inline:3');
  });

  it('says the simulator needs a valid graph when there is none', async () => {
    mount(null);
    fireEvent.click(screen.getByRole('button', { name: /simulate/i }));
    expect(await screen.findByRole('note')).toHaveTextContent(/Complete and validate the graph/);
    expect(screen.queryByTestId('simulator')).not.toBeInTheDocument();
  });
});
