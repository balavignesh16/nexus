import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { CreateRuleModal } from './CreateRuleModal';
import { ruleApi, type Rule } from '../../../api/ruleApi';

vi.mock('../../../api/ruleApi', async () => {
  const actual = await vi.importActual<typeof import('../../../api/ruleApi')>('../../../api/ruleApi');
  return {
    ...actual,
    ruleApi: { ...actual.ruleApi, createRule: vi.fn(), updateRule: vi.fn(), saveDraft: vi.fn(), createDraft: vi.fn() },
  };
});

const mockUseActiveDevices = vi.fn();
vi.mock('../../../hooks/queries', () => ({
  useActiveDevices: () => mockUseActiveDevices(),
}));

const gasDevice = {
  id: 'dev-1',
  name: 'ESP32-SIM-001',
  capabilities: [
    { id: 'cap-gas', deviceId: 'dev-1', capabilityType: 'GAS', direction: 'READ', unit: 'PPM' },
    { id: 'cap-motion', deviceId: 'dev-1', capabilityType: 'MOTION', direction: 'READ', unit: 'BOOLEAN' },
    { id: 'cap-buzzer', deviceId: 'dev-1', capabilityType: 'BUZZER', direction: 'WRITE', unit: 'BOOLEAN' },
    { id: 'cap-relay', deviceId: 'dev-1', capabilityType: 'RELAY', direction: 'WRITE', unit: 'BOOLEAN', metadata: { channels: [1, 2, 3, 4] } },
  ],
};

describe('CreateRuleModal - capability-aware rule creation, no fabrication', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUseActiveDevices.mockReturnValue({ data: [gasDevice] });
  });

  it('renders nothing when closed', () => {
    render(<CreateRuleModal deviceId="dev-1" isOpen={false} onClose={vi.fn()} onCreated={vi.fn()} />);
    expect(screen.queryByText('Create Rule')).not.toBeInTheDocument();
  });

  it('pre-selects and locks the trigger device when launched with a deviceId', () => {
    render(<CreateRuleModal deviceId="dev-1" isOpen={true} onClose={vi.fn()} onCreated={vi.fn()} />);
    const deviceSelect = screen.getByTestId('trigger-device-select') as HTMLSelectElement;
    expect(deviceSelect.value).toBe('dev-1');
    expect(deviceSelect).toBeDisabled();
  });

  it('with no deviceId (global Rules page), no device is pre-selected and the legacy generic condition is used', () => {
    render(<CreateRuleModal isOpen={true} onClose={vi.fn()} onCreated={vi.fn()} />);
    const deviceSelect = screen.getByTestId('trigger-device-select') as HTMLSelectElement;
    expect(deviceSelect.value).toBe('');
    expect(deviceSelect).not.toBeDisabled();
  });

  it('requires a name before submitting', async () => {
    render(<CreateRuleModal deviceId="dev-1" isOpen={true} onClose={vi.fn()} onCreated={vi.fn()} />);
    fireEvent.click(screen.getByText('Create Rule', { selector: 'button' }));

    await waitFor(() => expect(screen.getByTestId('create-rule-error')).toHaveTextContent('Name is required'));
    expect(ruleApi.createRule).not.toHaveBeenCalled();
  });

  it('a device-scoped rule builds a DEVICE_ID condition plus a capability-typed VALUE condition - no raw JSON', async () => {
    vi.mocked(ruleApi.createRule).mockResolvedValue({
      ruleId: 'rule-1', name: 'Gas Alert', enabled: true, priority: 1, conditions: [], actions: [], metadata: {},
    });
    const onCreated = vi.fn();
    const onClose = vi.fn();

    render(<CreateRuleModal deviceId="dev-1" isOpen={true} onClose={onClose} onCreated={onCreated} />);

    fireEvent.change(screen.getByPlaceholderText('e.g. High Temperature Alert'), { target: { value: 'Gas Alert' } });
    // Default seeded condition is GAS (first READ capability) with a blank numeric value - fill it in.
    fireEvent.change(screen.getByTestId('condition-numeric-value-input'), { target: { value: '800' } });
    fireEvent.change(screen.getByTestId('condition-operator-select'), { target: { value: 'GT' } });

    fireEvent.click(screen.getAllByText('Create Rule').find((el) => el.tagName === 'BUTTON')!);

    await waitFor(() => expect(ruleApi.createRule).toHaveBeenCalledWith(expect.objectContaining({
      name: 'Gas Alert',
      conditions: [
        { field: 'DEVICE_ID', operator: 'EQ', expectedValue: 'dev-1' },
        { field: 'VALUE', operator: 'GT', expectedValue: '800', capabilityType: 'GAS' },
      ],
    })));
    await waitFor(() => expect(onCreated).toHaveBeenCalled());
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  it('supports adding a second condition for a multi-capability AND rule (GAS > 800 AND MOTION == true)', async () => {
    vi.mocked(ruleApi.createRule).mockResolvedValue({
      ruleId: 'rule-1', name: 'Combo', enabled: true, priority: 1, conditions: [], actions: [], metadata: {},
    });

    render(<CreateRuleModal deviceId="dev-1" isOpen={true} onClose={vi.fn()} onCreated={vi.fn()} />);

    fireEvent.change(screen.getByPlaceholderText('e.g. High Temperature Alert'), { target: { value: 'Combo' } });
    fireEvent.change(screen.getByTestId('condition-numeric-value-input'), { target: { value: '800' } });
    fireEvent.change(screen.getByTestId('condition-operator-select'), { target: { value: 'GT' } });

    fireEvent.click(screen.getByText('Add condition'));

    const capabilitySelects = screen.getAllByTestId('condition-capability-select');
    expect(capabilitySelects).toHaveLength(2);
    fireEvent.change(capabilitySelects[1], { target: { value: 'MOTION' } });
    // Boolean capability -> a boolean select, defaulting to 'true'.
    const boolValueSelects = screen.getAllByTestId('condition-boolean-value-select');
    expect(boolValueSelects).toHaveLength(1);

    fireEvent.click(screen.getAllByText('Create Rule').find((el) => el.tagName === 'BUTTON')!);

    await waitFor(() => expect(ruleApi.createRule).toHaveBeenCalledWith(expect.objectContaining({
      conditions: [
        { field: 'DEVICE_ID', operator: 'EQ', expectedValue: 'dev-1' },
        { field: 'VALUE', operator: 'GT', expectedValue: '800', capabilityType: 'GAS' },
        { field: 'VALUE', operator: 'EQ', expectedValue: 'true', capabilityType: 'MOTION' },
      ],
    })));
  });

  it('a SEND_COMMAND action for RELAY produces a typed {channel,state} value with no raw JSON', async () => {
    vi.mocked(ruleApi.createRule).mockResolvedValue({
      ruleId: 'rule-1', name: 'Relay Rule', enabled: true, priority: 1, conditions: [], actions: [], metadata: {},
    });

    render(<CreateRuleModal deviceId="dev-1" isOpen={true} onClose={vi.fn()} onCreated={vi.fn()} />);

    fireEvent.change(screen.getByPlaceholderText('e.g. High Temperature Alert'), { target: { value: 'Relay Rule' } });
    fireEvent.change(screen.getByTestId('condition-numeric-value-input'), { target: { value: '800' } });

    fireEvent.change(screen.getByTestId('action-type-select'), { target: { value: 'SEND_COMMAND' } });
    fireEvent.change(screen.getByTestId('action-capability-select'), { target: { value: 'RELAY' } });
    fireEvent.change(screen.getByTestId('command-relay-channel-select'), { target: { value: '2' } });
    fireEvent.change(screen.getByTestId('command-relay-state-select'), { target: { value: 'true' } });

    fireEvent.click(screen.getAllByText('Create Rule').find((el) => el.tagName === 'BUTTON')!);

    await waitFor(() => expect(ruleApi.createRule).toHaveBeenCalledWith(expect.objectContaining({
      actions: [{
        actionType: 'SEND_COMMAND',
        parameters: { deviceId: 'dev-1', capability: 'RELAY', command: 'SET_STATE', value: { channel: 2, state: true } },
      }],
    })));
  });

  it('sends cooldownSeconds when set, and null when left blank', async () => {
    vi.mocked(ruleApi.createRule).mockResolvedValue({
      ruleId: 'rule-1', name: 'X', enabled: true, priority: 1, conditions: [], actions: [], metadata: {},
    });

    render(<CreateRuleModal deviceId="dev-1" isOpen={true} onClose={vi.fn()} onCreated={vi.fn()} />);
    fireEvent.change(screen.getByPlaceholderText('e.g. High Temperature Alert'), { target: { value: 'X' } });
    fireEvent.change(screen.getByTestId('condition-numeric-value-input'), { target: { value: '1' } });
    fireEvent.change(screen.getByTestId('cooldown-input'), { target: { value: '60' } });

    fireEvent.click(screen.getAllByText('Create Rule').find((el) => el.tagName === 'BUTTON')!);

    await waitFor(() => expect(ruleApi.createRule).toHaveBeenCalledWith(expect.objectContaining({ cooldownSeconds: 60 })));
  });

  it('legacy (no trigger device) rule still creates a plain EVENT_TYPE/SEVERITY condition, unchanged', async () => {
    vi.mocked(ruleApi.createRule).mockResolvedValue({
      ruleId: 'rule-1', name: 'Legacy', enabled: true, priority: 1, conditions: [], actions: [], metadata: {},
    });

    render(<CreateRuleModal isOpen={true} onClose={vi.fn()} onCreated={vi.fn()} />);

    fireEvent.change(screen.getByPlaceholderText('e.g. High Temperature Alert'), { target: { value: 'Legacy' } });
    const legacyValueInputs = screen.getAllByRole('textbox').filter((el) => el.className.includes('font-mono') && el.tagName === 'INPUT');
    fireEvent.change(legacyValueInputs[0], { target: { value: 'TWIN_UPDATED' } });

    fireEvent.click(screen.getAllByText('Create Rule').find((el) => el.tagName === 'BUTTON')!);

    await waitFor(() => expect(ruleApi.createRule).toHaveBeenCalledWith(expect.objectContaining({
      conditions: [{ field: 'EVENT_TYPE', operator: 'EQ', expectedValue: 'TWIN_UPDATED' }],
      cooldownSeconds: null,
    })));
  });

  it('shows a truthful error and does not close on invalid action-parameters JSON (CREATE_ALERT path)', async () => {
    render(<CreateRuleModal deviceId="dev-1" isOpen={true} onClose={vi.fn()} onCreated={vi.fn()} />);

    fireEvent.change(screen.getByPlaceholderText('e.g. High Temperature Alert'), { target: { value: 'X' } });
    fireEvent.change(screen.getByTestId('condition-numeric-value-input'), { target: { value: '1' } });
    fireEvent.change(screen.getByPlaceholderText('{"severity":"INFO"}'), { target: { value: '{not json' } });
    fireEvent.click(screen.getAllByText('Create Rule').find((el) => el.tagName === 'BUTTON')!);

    await waitFor(() => expect(screen.getByTestId('create-rule-error')).toHaveTextContent('valid JSON'));
    expect(ruleApi.createRule).not.toHaveBeenCalled();
  });

  it('shows the truthful backend error message when creation fails - never fabricates success', async () => {
    vi.mocked(ruleApi.createRule).mockRejectedValue(new Error('Server error'));
    const onCreated = vi.fn();

    render(<CreateRuleModal deviceId="dev-1" isOpen={true} onClose={vi.fn()} onCreated={onCreated} />);
    fireEvent.change(screen.getByPlaceholderText('e.g. High Temperature Alert'), { target: { value: 'X' } });
    fireEvent.change(screen.getByTestId('condition-numeric-value-input'), { target: { value: '1' } });
    fireEvent.click(screen.getAllByText('Create Rule').find((el) => el.tagName === 'BUTTON')!);

    await waitFor(() => expect(screen.getByTestId('create-rule-error')).toHaveTextContent('Server error'));
    expect(onCreated).not.toHaveBeenCalled();
  });

  // ---------- Edit mode: pre-fill from a real rule, submit via PUT ----------

  const relayRule: Rule = {
    ruleId: 'rule-99',
    name: 'Critical Gas -> Relay',
    enabled: false,
    priority: 7,
    cooldownSeconds: 30,
    conditions: [
      { field: 'DEVICE_ID', operator: 'EQ', expectedValue: 'dev-1' },
      { field: 'VALUE', operator: 'GT', expectedValue: '700', capabilityType: 'GAS' },
    ],
    actions: [
      { actionType: 'SEND_COMMAND', parameters: { deviceId: 'dev-1', capability: 'RELAY', command: 'SET_STATE', value: { channel: 2, state: true } } },
    ],
    metadata: {},
  };
  it('pins the opening revision despite polling and preserves entered data on stale conflict', async () => {
    const openRule = { ...relayRule, rowRevision: 4, lifecycle: 'ACTIVE' as const };
    vi.mocked(ruleApi.saveDraft).mockRejectedValue(new Error('Stale rule revision; reload explicitly'));
    const onCreated = vi.fn();
    const props = { deviceId: 'dev-1', isOpen: true, onClose: vi.fn(), onCreated };
    const view = render(<CreateRuleModal {...props} editingRule={openRule} />);
    fireEvent.change(screen.getByDisplayValue(relayRule.name), { target: { value: 'Unsaved edit' } });
    view.rerender(<CreateRuleModal {...props} editingRule={{ ...openRule, name: 'Polled edit', rowRevision: 5 }} />);
    expect(screen.getByDisplayValue('Unsaved edit')).toBeInTheDocument();
    fireEvent.click(screen.getByText('Save Draft'));
    await waitFor(() => expect(ruleApi.saveDraft).toHaveBeenCalledWith('rule-99', expect.objectContaining({ name: 'Unsaved edit' }), 4));
    await waitFor(() => expect(screen.getByTestId('create-rule-error')).toHaveTextContent('Stale rule revision'));
    expect(screen.getByDisplayValue('Unsaved edit')).toBeInTheDocument();
    expect(onCreated).not.toHaveBeenCalled();
    expect(ruleApi.updateRule).not.toHaveBeenCalled();
    expect(props.onClose).not.toHaveBeenCalled();
    expect(ruleApi.saveDraft).toHaveBeenCalledTimes(1);
    view.rerender(<CreateRuleModal {...props} editingRule={{ ...openRule, rowRevision: 6 }} />);
    expect(ruleApi.saveDraft).toHaveBeenCalledTimes(1);
    expect(screen.getByDisplayValue('Unsaved edit')).toBeInTheDocument();
  });
  it('successfully creates a modern draft without activating or calling legacy create', async () => {
    vi.mocked(ruleApi.createDraft).mockResolvedValue(relayRule);
    const onClose = vi.fn(); const onCreated = vi.fn();
    render(<CreateRuleModal deviceId="dev-1" isOpen onClose={onClose} onCreated={onCreated} />);
    fireEvent.change(screen.getByPlaceholderText('e.g. High Temperature Alert'), { target: { value: 'New draft' } });
    fireEvent.change(screen.getByTestId('condition-numeric-value-input'), { target: { value: '35' } });
    fireEvent.click(screen.getByRole('button', { name: 'Create Draft' }));
    await waitFor(() => expect(ruleApi.createDraft).toHaveBeenCalledWith(expect.objectContaining({ name: 'New draft', enabled: false })));
    expect(ruleApi.createRule).not.toHaveBeenCalled(); expect(onCreated).toHaveBeenCalledTimes(1); expect(onClose).toHaveBeenCalledTimes(1);
  });
  it('successfully saves only the working draft with the opening revision', async () => {
    vi.mocked(ruleApi.saveDraft).mockResolvedValue(relayRule);
    const onClose = vi.fn(); const onCreated = vi.fn();
    render(<CreateRuleModal deviceId="dev-1" editingRule={{ ...relayRule, enabled: true, rowRevision: 7 }} isOpen onClose={onClose} onCreated={onCreated} />);
    fireEvent.change(screen.getByDisplayValue(relayRule.name), { target: { value: 'Working V2' } }); fireEvent.click(screen.getByRole('button', { name: 'Save Draft' }));
    await waitFor(() => expect(ruleApi.saveDraft).toHaveBeenCalledWith('rule-99', expect.objectContaining({ name: 'Working V2' }), 7));
    expect(ruleApi.updateRule).not.toHaveBeenCalled(); expect(ruleApi.createRule).not.toHaveBeenCalled(); expect(onCreated).toHaveBeenCalledTimes(1); expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('edit mode shows "Edit Rule" / "Save Changes" instead of the create-mode labels', () => {
    render(<CreateRuleModal deviceId="dev-1" editingRule={relayRule} isOpen={true} onClose={vi.fn()} onCreated={vi.fn()} />);

    expect(screen.getByText('Edit Rule')).toBeInTheDocument();
    expect(screen.getByText('Save Changes')).toBeInTheDocument();
    expect(screen.queryByText('Create Rule', { selector: 'h3' })).not.toBeInTheDocument();
  });

  it('edit mode pre-fills name, priority, enabled and cooldown from the real rule', () => {
    render(<CreateRuleModal deviceId="dev-1" editingRule={relayRule} isOpen={true} onClose={vi.fn()} onCreated={vi.fn()} />);

    expect(screen.getByDisplayValue('Critical Gas -> Relay')).toBeInTheDocument();
    expect(screen.getByDisplayValue('7')).toBeInTheDocument();
    expect(screen.getByDisplayValue('30')).toBeInTheDocument();
    expect(screen.getByRole('checkbox')).not.toBeChecked();
  });

  it('edit mode pre-fills a capability-aware condition (GAS GT 700) into the condition builder', () => {
    render(<CreateRuleModal deviceId="dev-1" editingRule={relayRule} isOpen={true} onClose={vi.fn()} onCreated={vi.fn()} />);

    expect(screen.getByTestId('condition-capability-select')).toHaveValue('GAS');
    expect(screen.getByTestId('condition-operator-select')).toHaveValue('GT');
    expect(screen.getByTestId('condition-numeric-value-input')).toHaveValue(700);
  });

  it('edit mode pre-fills a RELAY SEND_COMMAND action, including its real channel and state', () => {
    render(<CreateRuleModal deviceId="dev-1" editingRule={relayRule} isOpen={true} onClose={vi.fn()} onCreated={vi.fn()} />);

    expect(screen.getByTestId('action-capability-select')).toHaveValue('RELAY');
    expect(screen.getByTestId('command-relay-channel-select')).toHaveValue('2');
    expect(screen.getByTestId('command-relay-state-select')).toHaveValue('true');
  });

  it('saving in edit mode calls updateRule with the real ruleId, never createRule', async () => {
    vi.mocked(ruleApi.updateRule).mockResolvedValue(relayRule as any);
    const onCreated = vi.fn();
    const onClose = vi.fn();

    render(<CreateRuleModal deviceId="dev-1" editingRule={relayRule} isOpen={true} onClose={onClose} onCreated={onCreated} />);
    fireEvent.click(screen.getByText('Save Changes'));

    await waitFor(() => expect(ruleApi.updateRule).toHaveBeenCalledWith('rule-99', expect.objectContaining({
      ruleId: 'rule-99',
      name: 'Critical Gas -> Relay',
      conditions: relayRule.conditions,
      actions: relayRule.actions,
    })));
    expect(ruleApi.createRule).not.toHaveBeenCalled();
    expect(onCreated).toHaveBeenCalled();
  });

  it('shows the truthful backend error message when an update fails - never fabricates success', async () => {
    vi.mocked(ruleApi.updateRule).mockRejectedValue(new Error('Conflict: rule was modified'));

    render(<CreateRuleModal deviceId="dev-1" editingRule={relayRule} isOpen={true} onClose={vi.fn()} onCreated={vi.fn()} />);
    fireEvent.click(screen.getByText('Save Changes'));

    await waitFor(() => expect(screen.getByTestId('create-rule-error')).toHaveTextContent('Conflict: rule was modified'));
  });
  describe('Phase 3 triggers and timing', () => {
    const open = () => fireEvent.click(screen.getByTestId('trigger-config-toggle'));
    const submit = () => fireEvent.click(screen.getAllByText('Create Rule').find((el) => el.tagName === 'BUTTON')!);

    it('keeps the advanced section collapsed and out of the way by default', () => {
      render(<CreateRuleModal deviceId="dev-1" isOpen={true} onClose={vi.fn()} onCreated={vi.fn()} />);
      expect(screen.queryByTestId('trigger-list')).not.toBeInTheDocument();
      expect(screen.getByTestId('trigger-config-toggle')).toHaveAttribute('aria-expanded', 'false');
    });

    it('sends explicit trigger subscriptions with their filters and the repeat policy', async () => {
      vi.mocked(ruleApi.createRule).mockResolvedValue({ ruleId: 'r', name: 'T', enabled: true, priority: 1, conditions: [], actions: [], metadata: {} });
      render(<CreateRuleModal deviceId="dev-1" isOpen={true} onClose={vi.fn()} onCreated={vi.fn()} />);
      fireEvent.change(screen.getByPlaceholderText('e.g. High Temperature Alert'), { target: { value: 'Triggered' } });
      fireEvent.change(screen.getByTestId('condition-numeric-value-input'), { target: { value: '800' } });
      open();
      fireEvent.click(screen.getByTestId('trigger-CONNECTIVITY_CHANGED'));
      fireEvent.change(screen.getByTestId('filter-CONNECTIVITY_CHANGED'), { target: { value: 'OFFLINE' } });
      fireEvent.click(screen.getByTestId('trigger-MANUAL'));
      fireEvent.change(screen.getByTestId('repeat-policy'), { target: { value: 'EDGE_TRUE' } });
      submit();
      await waitFor(() => expect(ruleApi.createRule).toHaveBeenCalledWith(expect.objectContaining({
        triggers: [{ type: 'CONNECTIVITY_CHANGED', filters: { connectivity: 'OFFLINE' } }, { type: 'MANUAL' }],
        repeatPolicy: 'EDGE_TRUE',
      })));
    });

    it('a temporal condition attaches to the capability condition and subscribes to TEMPORAL_DUE only', async () => {
      vi.mocked(ruleApi.createRule).mockResolvedValue({ ruleId: 'r', name: 'T', enabled: true, priority: 1, conditions: [], actions: [], metadata: {} });
      render(<CreateRuleModal deviceId="dev-1" isOpen={true} onClose={vi.fn()} onCreated={vi.fn()} />);
      fireEvent.change(screen.getByPlaceholderText('e.g. High Temperature Alert'), { target: { value: 'Sustained gas' } });
      fireEvent.change(screen.getByTestId('condition-numeric-value-input'), { target: { value: '800' } });
      fireEvent.change(screen.getByTestId('condition-operator-select'), { target: { value: 'GT' } });
      open();
      fireEvent.click(screen.getByTestId('temporal-enabled'));
      fireEvent.change(screen.getByTestId('temporal-duration'), { target: { value: '120' } });
      submit();
      await waitFor(() => expect(ruleApi.createRule).toHaveBeenCalledWith(expect.objectContaining({
        triggers: [{ type: 'TEMPORAL_DUE' }],
        conditions: [
          { field: 'DEVICE_ID', operator: 'EQ', expectedValue: 'dev-1' },
          { field: 'VALUE', operator: 'GT', expectedValue: '800', capabilityType: 'GAS', conditionId: 'c1', temporal: { operator: 'FOR_DURATION', durationSeconds: 120 } },
        ],
      })));
    });

    it('blocks an incomplete temporal configuration without calling the API', async () => {
      render(<CreateRuleModal deviceId="dev-1" isOpen={true} onClose={vi.fn()} onCreated={vi.fn()} />);
      fireEvent.change(screen.getByPlaceholderText('e.g. High Temperature Alert'), { target: { value: 'Incomplete' } });
      fireEvent.change(screen.getByTestId('condition-numeric-value-input'), { target: { value: '800' } });
      open();
      fireEvent.click(screen.getByTestId('temporal-enabled'));
      submit();
      await waitFor(() => expect(screen.getByTestId('create-rule-error')).toHaveTextContent('positive duration'));
      expect(ruleApi.createRule).not.toHaveBeenCalled();
    });

    it('editing a rule with a temporal condition reloads it and the saved draft keeps it', async () => {
      vi.mocked(ruleApi.saveDraft).mockResolvedValue({ ruleId: 'r1', name: 'Existing', enabled: true, priority: 1, conditions: [], actions: [], metadata: {} });
      const existing: Rule = {
        ruleId: 'r1', name: 'Existing', enabled: true, priority: 1, metadata: {}, rowRevision: 4,
        triggers: [{ type: 'TEMPORAL_DUE' }],
        conditions: [
          { field: 'DEVICE_ID', operator: 'EQ', expectedValue: 'dev-1' },
          { field: 'VALUE', operator: 'GT', expectedValue: '800', capabilityType: 'GAS', conditionId: 'c1', temporal: { operator: 'FOR_DURATION', durationSeconds: 90 } },
        ],
        actions: [{ actionType: 'CREATE_ALERT', parameters: { severity: 'INFO' } }],
      };
      render(<CreateRuleModal deviceId="dev-1" editingRule={existing} isOpen={true} onClose={vi.fn()} onCreated={vi.fn()} />);
      expect(screen.getByTestId('temporal-duration')).toHaveValue(90);
      fireEvent.click(screen.getByText('Save Draft'));
      await waitFor(() => expect(ruleApi.saveDraft).toHaveBeenCalledWith('r1', expect.objectContaining({
        triggers: [{ type: 'TEMPORAL_DUE' }],
        conditions: expect.arrayContaining([expect.objectContaining({ conditionId: 'c1', temporal: { operator: 'FOR_DURATION', durationSeconds: 90 } })]),
      }), 4));
    });
  });
  describe('editing preserves every existing definition detail (no silent loss, no version churn)', () => {
    const saved = () => vi.mocked(ruleApi.saveDraft).mockResolvedValue({ ruleId: 'r1', name: 'x', enabled: true, priority: 1, conditions: [], actions: [], metadata: {} });
    const edit = (rule: Rule, props: { deviceId?: string } = { deviceId: 'dev-1' }) => render(<CreateRuleModal {...props} editingRule={rule} isOpen={true} onClose={vi.fn()} onCreated={vi.fn()} />);
    const base = (conditions: Rule['conditions'], extra: Partial<Rule> = {}): Rule => ({
      ruleId: 'r1', name: 'Existing', enabled: true, priority: 1, metadata: {}, rowRevision: 7, conditions,
      actions: [{ actionType: 'CREATE_ALERT', parameters: { severity: 'INFO' } }], ...extra,
    });
    const body = () => vi.mocked(ruleApi.saveDraft).mock.calls[0][1];

    it('a generic capabilityKey condition with an explicit unit, a stable id and a temporal spec round-trips unchanged and in order', async () => {
      saved();
      const conditions: Rule['conditions'] = [
        { field: 'DEVICE_ID', operator: 'EQ', expectedValue: 'dev-1' },
        { field: 'VALUE', operator: 'GT', expectedValue: '30', capabilityKey: 'temperature', unit: 'CELSIUS', conditionId: 'c1', temporal: { operator: 'FOR_DURATION', durationSeconds: 90 } },
        { field: 'SEVERITY', operator: 'EQ', expectedValue: 'INFO' },
      ];
      edit(base(conditions, { triggers: [{ type: 'TEMPORAL_DUE' }], repeatPolicy: 'EDGE_TRUE' }));
      expect(screen.getByTestId('condition-capability-key')).toHaveTextContent('temperature (CELSIUS)');
      expect(screen.getByTestId('preserved-conditions')).toHaveTextContent('1 existing condition');
      fireEvent.click(screen.getByText('Save Draft'));
      await waitFor(() => expect(ruleApi.saveDraft).toHaveBeenCalled());
      expect(body().conditions).toEqual(conditions);
      expect(body().triggers).toEqual([{ type: 'TEMPORAL_DUE' }]);
      expect(body().repeatPolicy).toBe('EDGE_TRUE');
    });

    it('a value edit changes only the value; the key, unit and position stay', async () => {
      saved();
      const conditions: Rule['conditions'] = [
        { field: 'DEVICE_ID', operator: 'EQ', expectedValue: 'dev-1' },
        { field: 'VALUE', operator: 'GT', expectedValue: '30', capabilityKey: 'temperature', unit: 'CELSIUS' },
      ];
      edit(base(conditions));
      fireEvent.change(screen.getByTestId('condition-text-value-input'), { target: { value: '35' } });
      fireEvent.click(screen.getByText('Save Draft'));
      await waitFor(() => expect(ruleApi.saveDraft).toHaveBeenCalled());
      expect(body().conditions).toEqual([conditions[0], { ...conditions[1], expectedValue: '35' }]);
    });

    it('choosing a different capability drops the stale generic key and unit instead of mixing them', async () => {
      saved();
      edit(base([
        { field: 'DEVICE_ID', operator: 'EQ', expectedValue: 'dev-1' },
        { field: 'VALUE', operator: 'GT', expectedValue: '30', capabilityType: 'GAS', capabilityKey: 'gas', unit: 'PPM' },
      ]));
      fireEvent.change(screen.getByTestId('condition-capability-select'), { target: { value: 'MOTION' } });
      fireEvent.click(screen.getByText('Save Draft'));
      await waitFor(() => expect(ruleApi.saveDraft).toHaveBeenCalled());
      expect(body().conditions[1]).toEqual({ field: 'VALUE', operator: 'EQ', expectedValue: 'true', capabilityType: 'MOTION' });
    });

    it('an OFFLINE_FOR rule with no capability row is saved without inventing a default condition', async () => {
      saved();
      const conditions: Rule['conditions'] = [
        { field: 'DEVICE_ID', operator: 'EQ', expectedValue: 'dev-1' },
        { field: 'VALUE', operator: 'EQ', expectedValue: 'OFFLINE', conditionId: 'c1', temporal: { operator: 'OFFLINE_FOR', durationSeconds: 120 } },
      ];
      edit(base(conditions, { triggers: [{ type: 'TEMPORAL_DUE' }] }));
      expect(screen.queryByTestId('condition-capability-select')).not.toBeInTheDocument();
      fireEvent.click(screen.getByText('Save Draft'));
      await waitFor(() => expect(ruleApi.saveDraft).toHaveBeenCalled());
      expect(body().conditions).toEqual(conditions);
    });

    it('a platform-wide rule with several or non-editable legacy conditions is kept exactly as stored', async () => {
      saved();
      const conditions: Rule['conditions'] = [
        { field: 'EVENT_TYPE', operator: 'EQ', expectedValue: 'TWIN_UPDATED' },
        { field: 'SENSOR_TYPE', operator: 'EQ', expectedValue: 'TEMPERATURE' },
        { field: 'VALUE', operator: 'GT', expectedValue: '40' },
      ];
      edit(base(conditions), {});
      expect(screen.getByTestId('preserved-conditions')).toHaveTextContent('3 conditions');
      fireEvent.click(screen.getByText('Save Draft'));
      await waitFor(() => expect(ruleApi.saveDraft).toHaveBeenCalled());
      expect(body().conditions).toEqual(conditions);
    });

    it('a single editable legacy condition keeps its original behavior', async () => {
      saved();
      edit(base([{ field: 'EVENT_TYPE', operator: 'EQ', expectedValue: 'TWIN_UPDATED' }]), {});
      expect(screen.queryByTestId('preserved-conditions')).not.toBeInTheDocument();
      fireEvent.click(screen.getByText('Save Draft'));
      await waitFor(() => expect(ruleApi.saveDraft).toHaveBeenCalled());
      expect(body().conditions).toEqual([{ field: 'EVENT_TYPE', operator: 'EQ', expectedValue: 'TWIN_UPDATED' }]);
    });

    it('a hysteresis band with its stable condition id round-trips unchanged and the exit threshold is editable', async () => {
      saved();
      const conditions: Rule['conditions'] = [
        { field: 'DEVICE_ID', operator: 'EQ', expectedValue: 'dev-1' },
        { field: 'VALUE', operator: 'GT', expectedValue: '30', capabilityType: 'GAS', conditionId: 'h1', hysteresis: { exit: '25' } },
      ];
      edit(base(conditions));
      expect((screen.getByTestId('condition-hysteresis-exit') as HTMLInputElement).value).toBe('25');
      fireEvent.click(screen.getByText('Save Draft'));
      await waitFor(() => expect(ruleApi.saveDraft).toHaveBeenCalled());
      expect(body().conditions).toEqual(conditions);
    });

    it('editing the exit threshold changes only the exit', async () => {
      saved();
      const conditions: Rule['conditions'] = [
        { field: 'DEVICE_ID', operator: 'EQ', expectedValue: 'dev-1' },
        { field: 'VALUE', operator: 'GT', expectedValue: '30', capabilityType: 'GAS', conditionId: 'h1', hysteresis: { exit: '25' } },
      ];
      edit(base(conditions));
      fireEvent.change(screen.getByTestId('condition-hysteresis-exit'), { target: { value: '20' } });
      fireEvent.click(screen.getByText('Save Draft'));
      await waitFor(() => expect(ruleApi.saveDraft).toHaveBeenCalled());
      expect(body().conditions[1]).toEqual({ ...conditions[1], hysteresis: { exit: '20' } });
    });

    it('clearing the exit removes the band but never the stable condition id; setting one on a plain condition creates an id', async () => {
      saved();
      const withBand: Rule['conditions'] = [
        { field: 'DEVICE_ID', operator: 'EQ', expectedValue: 'dev-1' },
        { field: 'VALUE', operator: 'GT', expectedValue: '30', capabilityType: 'GAS', conditionId: 'h1', hysteresis: { exit: '25' } },
      ];
      edit(base(withBand));
      fireEvent.change(screen.getByTestId('condition-hysteresis-exit'), { target: { value: '' } });
      fireEvent.click(screen.getByText('Save Draft'));
      await waitFor(() => expect(ruleApi.saveDraft).toHaveBeenCalled());
      expect(body().conditions[1]).toEqual({ field: 'VALUE', operator: 'GT', expectedValue: '30', capabilityType: 'GAS', conditionId: 'h1' });
    });

    it('a plain numeric condition has no band until the user types an exit threshold', async () => {
      saved();
      edit(base([
        { field: 'DEVICE_ID', operator: 'EQ', expectedValue: 'dev-1' },
        { field: 'VALUE', operator: 'GT', expectedValue: '30', capabilityType: 'GAS' },
      ]));
      fireEvent.click(screen.getByText('Save Draft'));
      await waitFor(() => expect(ruleApi.saveDraft).toHaveBeenCalled());
      expect(body().conditions[1]).toEqual({ field: 'VALUE', operator: 'GT', expectedValue: '30', capabilityType: 'GAS' });
    });

    it('typing an exit threshold on a plain condition creates a stable band id', async () => {
      saved();
      edit(base([
        { field: 'DEVICE_ID', operator: 'EQ', expectedValue: 'dev-1' },
        { field: 'VALUE', operator: 'GT', expectedValue: '30', capabilityType: 'GAS' },
      ]));
      fireEvent.change(screen.getByTestId('condition-hysteresis-exit'), { target: { value: '22' } });
      fireEvent.click(screen.getByText('Save Draft'));
      await waitFor(() => expect(ruleApi.saveDraft).toHaveBeenCalled());
      expect(body().conditions[1].hysteresis).toEqual({ exit: '22' });
      expect(body().conditions[1].conditionId).toMatch(/^hy-/);
    });

    it('a boolean capability offers no hysteresis control', () => {
      saved();
      edit(base([
        { field: 'DEVICE_ID', operator: 'EQ', expectedValue: 'dev-1' },
        { field: 'VALUE', operator: 'EQ', expectedValue: 'true', capabilityType: 'MOTION' },
      ]));
      expect(screen.queryByTestId('condition-hysteresis-exit')).not.toBeInTheDocument();
    });

    it('an explicit per-scope cooldown survives editing and a default rule never sends a scope', async () => {
      saved();
      edit(base([{ field: 'DEVICE_ID', operator: 'EQ', expectedValue: 'dev-1' }, { field: 'VALUE', operator: 'GT', expectedValue: '30', capabilityType: 'GAS' }],
        { cooldownSeconds: 60, cooldownScope: 'PER_SCOPE' }));
      expect((screen.getByTestId('cooldown-scope') as HTMLSelectElement).value).toBe('PER_SCOPE');
      fireEvent.click(screen.getByText('Save Draft'));
      await waitFor(() => expect(ruleApi.saveDraft).toHaveBeenCalled());
      expect(body().cooldownScope).toBe('PER_SCOPE');
      expect(body().cooldownSeconds).toBe(60);
    });

    it('a rule that never opted in sends no cooldown scope at all', async () => {
      saved();
      edit(base([{ field: 'DEVICE_ID', operator: 'EQ', expectedValue: 'dev-1' }, { field: 'VALUE', operator: 'GT', expectedValue: '30', capabilityType: 'GAS' }], { cooldownSeconds: 60 }));
      fireEvent.click(screen.getByTestId('trigger-config-toggle'));
      expect((screen.getByTestId('cooldown-scope') as HTMLSelectElement).value).toBe('GLOBAL');
      fireEvent.click(screen.getByText('Save Draft'));
      await waitFor(() => expect(ruleApi.saveDraft).toHaveBeenCalled());
      expect(body()).not.toHaveProperty('cooldownScope');
    });
  });
});
