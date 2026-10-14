import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import type { DryRunRequest, DryRunResponse, Rule } from '../../api/ruleApi';

const dryRun = vi.fn();
vi.mock('../../api/ruleApi', () => ({ ruleApi: { dryRun: (...args: unknown[]) => dryRun(...args) } }));

import { ApiError } from '../../api/apiClient';
import { RuleSimulator } from './RuleSimulator';

const DEVICE = '11111111-1111-1111-1111-111111111111';
const rule: Rule = {
  ruleId: 'rule-1', name: 'High temperature', enabled: true, priority: 1, metadata: {}, triggers: [{ type: 'ACCEPTED_TELEMETRY' }],
  conditions: [{ field: 'DEVICE_ID', operator: 'EQ', expectedValue: DEVICE }, { field: 'VALUE', operator: 'GT', expectedValue: '10', capabilityType: 'TEMPERATURE', unit: 'CELSIUS' }],
  actions: [{ actionType: 'SEND_COMMAND', parameters: {} }],
};

const response = (patch: Partial<DryRunResponse> = {}): DryRunResponse => ({
  schemaVersion: 1, mode: 'SUPPLIED_STATE', hypothetical: true, simulatedAt: '2026-10-14T10:00:00Z', timeSource: 'SUPPLIED', scopeDeviceId: DEVICE,
  versions: [{
    simulatedVersionId: 'sim-v1', ruleId: 'rule-1', versionId: 'v1', revision: 1, executionEpoch: 1, name: 'High temperature', priority: 1, source: 'STORED_ACTIVE', triggerEligible: true,
    outcome: 'MATCHED', matched: true, message: 'All conditions met', lane: 0, contractDiagnostics: [], cooldown: null, repeat: null,
    ownership: { lifecycle: 'ACTIVE', activeVersion: true, reconciliation: 'PASSED', executableInProduction: true },
    conditions: [{ field: 'VALUE', operator: 'GT', expectedValue: '10', actualValue: 25, conditionMet: true, capabilityKey: 'temperature', unit: 'CELSIUS', stateQuality: 'GOOD', qualityReason: 'WITHIN_FRESHNESS_THRESHOLD', eligible: true }],
  }],
  candidates: [{ simulatedId: 'sim-l0-c1', lane: 0, ruleId: 'rule-1', versionId: 'v1', ordinal: 0, actionType: 'SEND_COMMAND', targetKey: 'dev:led', priority: 1, disposition: 'SELECTED', reason: 'INDEPENDENT_TARGET', winnerId: null, wouldBeSelected: true, exclusionGroups: [], parameters: {}, acceptanceAuthorization: 'NOT_EVALUATED' }],
  hypotheticalEffects: [], diagnostics: [],
  notice: 'Hypothetical result: nothing was matched, planned, dispatched, published, acknowledged or persisted. A selected candidate is a proposal, not an accepted command and not physical actuation.',
  ...patch,
});

const lastRequest = (): DryRunRequest => dryRun.mock.calls.at(-1)![0] as DryRunRequest;
const run = async () => { fireEvent.click(screen.getByRole('button', { name: /run dry-run/i })); await waitFor(() => expect(dryRun).toHaveBeenCalled()); };
const setValue = (label: string | RegExp, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });

beforeEach(() => { dryRun.mockReset(); dryRun.mockResolvedValue(response()); });

describe('RuleSimulator is a thin client of the backend engine (scenarios 61, 63, 65, 70)', () => {
  it('evaluates nothing itself: a stored rule is simulated by id with the typed value the user entered', async () => {
    render(<RuleSimulator rule={rule} />);
    expect(screen.getByLabelText('Scope device id')).toHaveValue(DEVICE);
    expect(screen.getByLabelText('Trigger')).toHaveValue('ACCEPTED_TELEMETRY');
    setValue('Value 1', '25');
    await run();
    const request = lastRequest();
    expect(request.mode).toBe('SUPPLIED_STATE');
    expect(request.versions).toEqual([{ ruleId: 'rule-1' }]);
    expect(request.supplied!.twins).toEqual([expect.objectContaining({ capabilityKey: 'temperature', valueType: 'NUMBER', value: 25, unit: 'CELSIUS', deviceId: DEVICE })]);
    expect(request.simulationTime).toMatch(/Z$/);
    expect(await screen.findByTestId('dry-run-result')).toBeInTheDocument();
    expect(screen.getByTestId('version-outcome')).toHaveTextContent('Would match');
  });

  it('sends 0 as a number, false as a boolean, the empty string as a string and an explicit null as null - never dropping or coercing them', async () => {
    render(<RuleSimulator rule={rule} />);
    setValue('Value 1', '0');
    await run();
    expect(lastRequest().supplied!.twins![0]).toMatchObject({ value: 0, valueType: 'NUMBER' });

    setValue('Value kind 1', 'BOOLEAN'); setValue('Value 1', 'false');
    fireEvent.click(screen.getByRole('button', { name: /run dry-run/i }));
    await waitFor(() => expect(dryRun).toHaveBeenCalledTimes(2));
    expect(lastRequest().supplied!.twins![0]).toMatchObject({ value: false, valueType: 'BOOLEAN' });

    setValue('Value kind 1', 'STRING'); setValue('Value 1', '');
    fireEvent.click(screen.getByRole('button', { name: /run dry-run/i }));
    await waitFor(() => expect(dryRun).toHaveBeenCalledTimes(3));
    expect(lastRequest().supplied!.twins![0]).toMatchObject({ value: '', valueType: 'STRING' });

    setValue('Value kind 1', 'NULL');
    fireEvent.click(screen.getByRole('button', { name: /run dry-run/i }));
    await waitFor(() => expect(dryRun).toHaveBeenCalledTimes(4));
    expect(lastRequest().supplied!.twins![0].value).toBeNull();
  });

  it('omits a not-reported capability and models unavailable receipt as null, not now', async () => {
    render(<RuleSimulator rule={rule} />);
    setValue('Value kind 1', 'MISSING');
    await run();
    expect(lastRequest().supplied!.twins).toEqual([]);
    setValue('Value kind 1', 'NUMBER'); setValue('Value 1', '25'); setValue('Receipt 1', 'UNAVAILABLE');
    fireEvent.click(screen.getByRole('button', { name: /run dry-run/i }));
    await waitFor(() => expect(dryRun).toHaveBeenCalledTimes(2));
    expect(lastRequest().supplied!.twins![0].receivedAt).toBeNull();
  });

  it('blocks an invalid or empty number locally with a message and sends nothing', async () => {
    render(<RuleSimulator rule={rule} />);
    fireEvent.click(screen.getByRole('button', { name: /run dry-run/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/Enter a number \(0 is a valid number; empty is not\)/);
    expect(dryRun).not.toHaveBeenCalled();
    setValue('Value 1', 'abc');
    fireEvent.click(screen.getByRole('button', { name: /run dry-run/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/"abc" is not a number/);
    expect(dryRun).not.toHaveBeenCalled();
  });

  it('CURRENT_STATE sends no browser time and no supplied values: the backend clock and snapshot are the authority', async () => {
    render(<RuleSimulator rule={rule} />);
    setValue('Mode', 'CURRENT_STATE');
    expect(screen.queryByLabelText('Simulation time')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Value 1')).not.toBeInTheDocument();
    await run();
    const request = lastRequest();
    expect(request.mode).toBe('CURRENT_STATE');
    expect(request.simulationTime).toBeUndefined();
    expect(request.supplied).toBeUndefined();
  });

  it('shows the typed rejection of an unsupported historical replay and no decision', async () => {
    dryRun.mockRejectedValue(new ApiError(422, 'x', { code: 'HISTORICAL_REPLAY_UNSUPPORTED', message: 'HISTORICAL_REPLAY is not supported. No historical state was fabricated and nothing was evaluated.' }));
    render(<RuleSimulator rule={rule} />);
    setValue('Mode', 'HISTORICAL_REPLAY');
    await run();
    const rejection = await screen.findByTestId('dry-run-rejection');
    expect(rejection).toHaveTextContent('HISTORICAL_REPLAY_UNSUPPORTED');
    expect(rejection).toHaveTextContent(/nothing was evaluated/);
    expect(screen.queryByTestId('dry-run-result')).not.toBeInTheDocument();
  });

  it('shows validation limits and outages as rejections, never as a successful simulation', async () => {
    dryRun.mockRejectedValue(new ApiError(413, 'x', { code: 'TOO_MANY_VERSIONS', message: 'At most 100 versions per request (got 101)' }));
    render(<RuleSimulator rule={rule} />);
    setValue('Value 1', '25');
    await run();
    expect(await screen.findByTestId('dry-run-rejection')).toHaveTextContent('TOO_MANY_VERSIONS');
    dryRun.mockRejectedValue(new Error('Network down'));
    fireEvent.click(screen.getByRole('button', { name: /run dry-run/i }));
    await waitFor(() => expect(screen.getByTestId('dry-run-rejection')).toHaveTextContent('Network down'));
    expect(screen.queryByTestId('dry-run-result')).not.toBeInTheDocument();
  });

  it('labels every result hypothetical and never presents a selected candidate as executed, published, acknowledged or actuated', async () => {
    render(<RuleSimulator rule={rule} />);
    setValue('Value 1', '25');
    await run();
    const result = await screen.findByTestId('dry-run-result');
    expect(within(result).getByRole('note')).toHaveTextContent(/Hypothetical result - nothing was executed/);
    expect(within(result).getByRole('note')).toHaveTextContent(/not an accepted command and not physical actuation/);
    const candidate = within(result).getByTestId('candidate');
    expect(candidate).toHaveTextContent('would be selected by arbitration (acceptance and authorization are not evaluated)');
    expect(candidate).toHaveTextContent(/A proposal only: guarded acceptance and authorization were not run, and nothing was accepted, published, acknowledged or actuated/);
    expect(candidate.textContent).not.toMatch(/executed|dispatched/i);
    expect(screen.getByText(/Supplied \(hypothetical\) state/)).toBeInTheDocument();
  });

  it('renders false, 0, the empty string and a missing observation distinctly, with suppressed candidates and their reasons', async () => {
    dryRun.mockResolvedValue(response({
      versions: [{
        ...response().versions[0], outcome: 'NOT_MATCHED', matched: false, message: 'Condition not met: VALUE',
        conditions: [
          { field: 'VALUE', operator: 'EQ', expectedValue: 'false', actualValue: false, conditionMet: false, capabilityKey: 'motion', stateQuality: 'GOOD', eligible: false },
          { field: 'VALUE', operator: 'GT', expectedValue: '10', actualValue: 0, conditionMet: false, capabilityKey: 'temperature', stateQuality: 'GOOD', eligible: true },
          { field: 'SENSOR_TYPE', operator: 'EQ', expectedValue: '', actualValue: '', conditionMet: true },
          { field: 'VALUE', operator: 'GT', expectedValue: '1', actualValue: 'MISSING', conditionMet: false },
          { field: 'VALUE', operator: 'GT', expectedValue: '1', actualValue: 25, conditionMet: false, capabilityKey: 'temperature', stateQuality: 'UNKNOWN', qualityReason: 'RECEIPT_TIME_UNAVAILABLE', eligible: false },
        ],
      }],
      candidates: [
        { simulatedId: 'sim-l0-c1', lane: 0, ruleId: 'rule-1', versionId: 'v1', ordinal: 0, actionType: 'SEND_COMMAND', targetKey: 'dev:led', priority: 1, disposition: 'SELECTED', reason: 'PRIORITY_WINNER', winnerId: null, wouldBeSelected: true, exclusionGroups: [], parameters: {}, acceptanceAuthorization: 'NOT_EVALUATED' },
        { simulatedId: 'sim-l0-c2', lane: 0, ruleId: 'rule-2', versionId: 'v2', ordinal: 0, actionType: 'SEND_COMMAND', targetKey: 'dev:led', priority: 2, disposition: 'SUPPRESSED_PRIORITY', reason: 'LOWER_PRIORITY_CONFLICT', winnerId: 'sim-l0-c1', wouldBeSelected: false, exclusionGroups: [], parameters: {}, acceptanceAuthorization: 'NOT_EVALUATED' },
        { simulatedId: 'sim-l0-c3', lane: 0, ruleId: 'rule-3', versionId: 'v3', ordinal: 0, actionType: 'SEND_COMMAND', targetKey: 'dev:fan', priority: 1, disposition: 'SUPPRESSED_EXCLUSION', reason: 'EXCLUSION_LOWER_RANK_WINNER_EXCLUDED', winnerId: null, wouldBeSelected: false, exclusionGroups: [{ groupId: 'g', rank: 2 }], parameters: {}, acceptanceAuthorization: 'NOT_EVALUATED' },
      ],
      diagnostics: [{ code: 'SUPPLIED_EXPLICIT_NULL', severity: 'INFO', message: 'An explicit null is not a value', path: 'supplied.twins[2]' }],
    }));
    render(<RuleSimulator rule={rule} />);
    setValue('Value 1', '25');
    await run();
    const shown = (await screen.findAllByTestId('actual-value')).map((n) => n.textContent);
    expect(shown[0]).toBe('false');
    expect(shown[1]).toBe('0');
    expect(shown[2]).toBe('"" (empty string)');
    expect(shown[3]).toBe('no observation');
    expect(shown[4]).toMatch(/^25/);
    expect(screen.getByText(/UNKNOWN \(RECEIPT_TIME_UNAVAILABLE\) - not eligible/)).toBeInTheDocument();
    const candidates = screen.getAllByTestId('candidate');
    expect(candidates[1]).toHaveTextContent('would lose to a higher-priority command');
    expect(candidates[1]).toHaveTextContent('winner sim-l0-c1');
    expect(candidates[2]).toHaveTextContent('would be suppressed by a mutual-exclusion group');
    expect(candidates[2]).toHaveTextContent('EXCLUSION_LOWER_RANK_WINNER_EXCLUDED');
    expect(candidates[2]).not.toHaveTextContent('winner sim');
    expect(screen.getByText(/SUPPLIED_EXPLICIT_NULL/)).toBeInTheDocument();
  });

  it('simulates an unsaved definition inline (no rule id, no stored runtime seeds)', async () => {
    render(<RuleSimulator inlineDefinition={{ name: 'Draft', enabled: false, priority: 3, conditions: [{ field: 'SENSOR_TYPE', operator: 'EQ', expectedValue: '' }], actions: [{ actionType: 'CREATE_ALERT', parameters: {} }] }} />);
    expect(screen.queryByText(/Runtime state seeds/)).not.toBeInTheDocument();
    setValue('Event payload', '{"sensorType": ""}');
    await run();
    const request = lastRequest();
    expect(request.versions).toHaveLength(1);
    expect(request.versions[0].ruleId).toBeUndefined();
    expect(request.versions[0].definition).toMatchObject({ name: 'Draft', conditions: [{ expectedValue: '' }] });
    expect(request.trigger).toMatchObject({ type: 'LEGACY_DOMAIN_EVENT', event: { eventType: 'TWIN_UPDATED', payload: { sensorType: '' } } });
    setValue('Event payload', '[1]');
    fireEvent.click(screen.getByRole('button', { name: /run dry-run/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/must be a JSON object/);
  });

  it('seeds the production gates the user states (edge latch, last match, hysteresis band) and nothing else', async () => {
    const banded: Rule = { ...rule, conditions: [...rule.conditions.slice(0, 1), { field: 'VALUE', operator: 'GT', expectedValue: '30', capabilityType: 'TEMPERATURE', conditionId: 'h1', hysteresis: { exit: '25' } }] };
    render(<RuleSimulator rule={banded} />);
    setValue('Value 1', '28');
    fireEvent.click(screen.getByLabelText('Edge latch held'));
    fireEvent.click(screen.getByLabelText('Hysteresis latched h1'));
    setValue('Last matched at', '2026-10-14T09:59:30');
    await run();
    const supplied = lastRequest().supplied!;
    expect(supplied.edgeLatches).toEqual([{ ruleId: 'rule-1', latched: true }]);
    expect(supplied.hysteresisLatches).toEqual([{ ruleId: 'rule-1', conditionId: 'h1', latched: true }]);
    expect(supplied.cooldowns).toHaveLength(1);
  });

  it('tells the user when production could not execute the simulated version at all, and says nothing for an executable or unsaved one', async () => {
    dryRun.mockResolvedValue(response({ versions: [{ ...response().versions[0], ownership: { lifecycle: 'PAUSED', activeVersion: true, reconciliation: 'PASSED', executableInProduction: false } }] }));
    render(<RuleSimulator rule={rule} />);
    setValue('Value 1', '25');
    await run();
    const warning = await screen.findByTestId('not-executable');
    expect(warning).toHaveTextContent(/Production would not execute this version: the rule is PAUSED/);
    expect(warning).toHaveTextContent(/Arbitration below shows only what it would pick if the version were executable/);
    dryRun.mockResolvedValue(response({ versions: [{ ...response().versions[0], ownership: null, source: 'INLINE' }] }));
    fireEvent.click(screen.getByRole('button', { name: /run dry-run/i }));
    await waitFor(() => expect(dryRun).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.queryByTestId('not-executable')).not.toBeInTheDocument());
  });

  it('shows the typed diagnostics for an unsupported hypothetical device and for temporal progression that is not simulated', async () => {
    dryRun.mockResolvedValue(response({ diagnostics: [
      { code: 'DEVICE_NOT_CONFIGURED', severity: 'WARNING', message: 'hypothetical devices and hypothetical installed contracts are not supported.', path: 'scope' },
      { code: 'TEMPORAL_PROGRESSION_NOT_SIMULATED', severity: 'INFO', message: 'Arming, counting and advancing temporal state from telemetry is not simulated.', path: 'versions' },
    ] }));
    render(<RuleSimulator rule={rule} />);
    setValue('Value 1', '25');
    await run();
    expect(await screen.findByText(/DEVICE_NOT_CONFIGURED/)).toBeInTheDocument();
    expect(screen.getByText(/TEMPORAL_PROGRESSION_NOT_SIMULATED/)).toBeInTheDocument();
  });
});
