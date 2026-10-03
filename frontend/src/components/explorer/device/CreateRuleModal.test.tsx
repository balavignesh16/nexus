import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { CreateRuleModal } from './CreateRuleModal';
import { ruleApi, type Rule } from '../../../api/ruleApi';

vi.mock('../../../api/ruleApi', async () => {
  const actual = await vi.importActual<typeof import('../../../api/ruleApi')>('../../../api/ruleApi');
  return {
    ...actual,
    ruleApi: { ...actual.ruleApi, createRule: vi.fn(), updateRule: vi.fn() },
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
});
