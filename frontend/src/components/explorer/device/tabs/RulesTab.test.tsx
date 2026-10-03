import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

const mockGetRules = vi.fn();
vi.mock('../../../../repositories/RepositoryProvider', () => ({
  useRepositories: () => ({ rules: { getRules: mockGetRules } }),
}));

const mockUsePolling = vi.fn();
vi.mock('../../../../hooks/usePolling', () => ({
  usePolling: (...args: any[]) => mockUsePolling(...args),
}));

const mockCreateRule = vi.fn();
const mockUpdateRule = vi.fn();
const mockEnableRule = vi.fn().mockResolvedValue(undefined);
const mockDisableRule = vi.fn().mockResolvedValue(undefined);
const mockDeleteRule = vi.fn().mockResolvedValue(undefined);
vi.mock('../../../../api/ruleApi', () => ({
  ruleApi: {
    createRule: (...args: any[]) => mockCreateRule(...args),
    updateRule: (...args: any[]) => mockUpdateRule(...args),
    enableRule: (...args: any[]) => mockEnableRule(...args),
    disableRule: (...args: any[]) => mockDisableRule(...args),
    deleteRule: (...args: any[]) => mockDeleteRule(...args),
  },
}));

const mockGetRuleMatchHistory = vi.fn();
vi.mock('../../../../api/aiApi', () => ({
  aiApi: { getRuleMatchHistory: (...args: any[]) => mockGetRuleMatchHistory(...args) },
}));

import { RulesTab } from './RulesTab';

function rule(overrides: Record<string, any> = {}) {
  return {
    ruleId: 'r1',
    id: 'r1',
    name: 'High Temp Alert',
    description: 'Triggers on high temperature',
    enabled: true,
    priority: 1,
    conditions: [{ field: 'VALUE', operator: 'GT', expectedValue: '40', capabilityType: 'TEMPERATURE' }],
    actions: [{ actionType: 'CREATE_ALERT', parameters: { severity: 'CRITICAL' } }],
    ...overrides,
  };
}

function match(overrides: Record<string, any> = {}) {
  return {
    matchId: 'match-1',
    ruleId: 'r1',
    ruleName: 'High Temp Alert',
    matchedAt: '2026-01-01T00:00:00Z',
    correlationId: 'corr-1',
    message: 'All conditions met',
    ...overrides,
  };
}

const emptyMatchesPolling = { data: [], isLoading: false, isError: false, refetch: vi.fn() };

const deviceWithCapabilities = {
  id: 'device-a',
  name: 'ESP32-SIM-001',
  capabilities: [
    { id: 'cap-gas', deviceId: 'device-a', capabilityType: 'GAS', direction: 'READ', unit: 'PPM' },
  ],
};

/** Sets the rules-query result; the rule-matches and activeDevices queries
 * (the latter consumed internally by CreateRuleModal) default to real,
 * honest results unless overridden. */
function mockPolling(rulesResult: any, matchesResult: any = emptyMatchesPolling) {
  mockUsePolling.mockImplementation((key: any) => {
    if (Array.isArray(key) && key[0] === 'rule-matches') return matchesResult;
    if (Array.isArray(key) && key[0] === 'activeDevices') return { data: [deviceWithCapabilities], isLoading: false, isError: false };
    return rulesResult;
  });
}

function renderTab(deviceId = 'device-a') {
  return render(
    <MemoryRouter>
      <RulesTab deviceId={deviceId} />
    </MemoryRouter>
  );
}

describe('RulesTab - device-scoped rules', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('requests rules scoped to the current device, not the global rule set', () => {
    mockPolling({ data: [], isLoading: false, isError: false, refetch: vi.fn() });

    renderTab();

    expect(mockUsePolling).toHaveBeenCalledWith(
      ['rules', 'device-a'],
      expect.any(Function),
      expect.anything()
    );
    const rulesCall = mockUsePolling.mock.calls.find((c) => c[0][0] === 'rules');
    rulesCall![1]();
    expect(mockGetRules).toHaveBeenCalledWith('device-a');
  });

  it('renders rules returned for this device', () => {
    mockPolling({ data: [rule()], isLoading: false, isError: false, refetch: vi.fn() });

    renderTab();

    expect(screen.getByText('High Temp Alert')).toBeInTheDocument();
  });

  it('renders the real condition and action content from rule.conditions/rule.actions, not blank boxes', () => {
    mockPolling({ data: [rule()], isLoading: false, isError: false, refetch: vi.fn() });

    renderTab();

    expect(screen.getByText('TEMPERATURE VALUE GT 40')).toBeInTheDocument();
    expect(screen.getByText('CREATE_ALERT')).toBeInTheDocument();
    expect(screen.getByText('{"severity":"CRITICAL"}')).toBeInTheDocument();
  });

  it('shows an honest "no conditions/actions defined" fallback when a rule genuinely has none', () => {
    mockPolling({ data: [rule({ conditions: [], actions: [] })], isLoading: false, isError: false, refetch: vi.fn() });

    renderTab();

    expect(screen.getByText('No conditions defined')).toBeInTheDocument();
    expect(screen.getByText('No actions defined')).toBeInTheDocument();
  });

  it('shows an honest empty state when this device genuinely has no rules', () => {
    mockPolling({ data: [], isLoading: false, isError: false, refetch: vi.fn() });

    renderTab();

    expect(screen.getByText('No automations bound.')).toBeInTheDocument();
  });

  it('shows a distinct loading state, never an empty state mistaken for zero rules', () => {
    mockPolling({ data: undefined, isLoading: true, isError: false, refetch: vi.fn() });

    renderTab();

    expect(screen.queryByText('No automations bound.')).not.toBeInTheDocument();
  });

  it('shows a distinct error state, never silently falling back to the empty state', () => {
    mockPolling({
      data: [],
      isLoading: false,
      isError: true,
      error: new Error('Failed to fetch rules: 500'),
      refetch: vi.fn(),
    });

    renderTab();

    expect(screen.getByTestId('rules-tab-error')).toBeInTheDocument();
    expect(screen.queryByText('No automations bound.')).not.toBeInTheDocument();
  });

  it('shows the real rule.enabled state instead of a hardcoded "Active" badge', () => {
    mockPolling({ data: [rule({ enabled: false })], isLoading: false, isError: false, refetch: vi.fn() });

    renderTab();

    expect(screen.getByText('Disabled')).toBeInTheDocument();
    expect(screen.getByText('Not evaluated')).toBeInTheDocument();
    expect(screen.queryByText('Monitored Live')).not.toBeInTheDocument();
  });

  it('shows Active/Monitored Live only when rule.enabled is genuinely true', () => {
    mockPolling({ data: [rule({ enabled: true })], isLoading: false, isError: false, refetch: vi.fn() });

    renderTab();

    expect(screen.getByText('Monitored Live')).toBeInTheDocument();
    expect(screen.queryByText('Disabled')).not.toBeInTheDocument();
  });

  it('refetches after a rule is successfully created, so it appears in this device scope', async () => {
    const refetch = vi.fn();
    mockPolling({ data: [], isLoading: false, isError: false, refetch });
    mockCreateRule.mockResolvedValue({ ruleId: 'new-rule' });

    renderTab();

    fireEvent.click(screen.getAllByRole('button', { name: /create rule/i })[0]);
    const nameInput = screen.getByPlaceholderText('e.g. High Temperature Alert');
    fireEvent.change(nameInput, { target: { value: 'New Rule' } });
    fireEvent.change(screen.getByTestId('condition-numeric-value-input'), { target: { value: '500' } });

    const createButtons = screen.getAllByRole('button', { name: /create rule/i });
    fireEvent.click(createButtons[createButtons.length - 1]);

    await vi.waitFor(() => expect(mockCreateRule).toHaveBeenCalled());
    await vi.waitFor(() => expect(refetch).toHaveBeenCalled());
  });

  // ---------- Recent Matches (Phase 3) ----------

  it('requests rule matches scoped to this device', () => {
    mockPolling({ data: [], isLoading: false, isError: false, refetch: vi.fn() });

    renderTab();

    const matchesCall = mockUsePolling.mock.calls.find((c) => c[0][0] === 'rule-matches');
    expect(matchesCall).toBeDefined();
    matchesCall![1]();
    expect(mockGetRuleMatchHistory).toHaveBeenCalledWith(20, undefined, undefined, 'device-a');
  });

  it('renders real recent matches for this device', () => {
    mockPolling(
      { data: [], isLoading: false, isError: false, refetch: vi.fn() },
      { data: [match({ ruleName: 'Gas Leak Detector' })], isLoading: false, isError: false }
    );

    renderTab();

    expect(screen.getByText('Gas Leak Detector')).toBeInTheDocument();
    expect(screen.queryByText('No rule matches recorded for this device yet.')).not.toBeInTheDocument();
  });

  it('shows an honest empty state when this device has no recent matches', () => {
    mockPolling({ data: [], isLoading: false, isError: false, refetch: vi.fn() });

    renderTab();

    expect(screen.getByText('No rule matches recorded for this device yet.')).toBeInTheDocument();
  });

  it('shows a distinct error state for the matches section, independent of the rules section', () => {
    mockPolling(
      { data: [rule()], isLoading: false, isError: false, refetch: vi.fn() },
      { data: [], isLoading: false, isError: true }
    );

    renderTab();

    expect(screen.getByTestId('recent-matches-error')).toBeInTheDocument();
    // The rules section itself must still render fine.
    expect(screen.getByText('High Temp Alert')).toBeInTheDocument();
  });

  it('links each recent match to the real AI explanation experience via matchId', () => {
    mockPolling(
      { data: [], isLoading: false, isError: false, refetch: vi.fn() },
      { data: [match({ matchId: 'match-xyz' })], isLoading: false, isError: false }
    );

    renderTab();

    const link = screen.getByText('High Temp Alert').closest('a');
    expect(link).toHaveAttribute('href', '/ai?matchId=match-xyz');
  });

  // ---------- Rule detail popup + edit-in-place ----------

  it('clicking a rule card opens a detail popup showing its real conditions and actions', () => {
    mockPolling({ data: [rule()], isLoading: false, isError: false, refetch: vi.fn() });

    renderTab();
    fireEvent.click(screen.getByText('High Temp Alert'));

    // Both the card (behind the popup) and the popup itself render this
    // condition/action text, so assert presence, not uniqueness.
    expect(screen.getAllByText(/TEMPERATURE VALUE GT 40/).length).toBeGreaterThan(0);
    expect(screen.getAllByText('CREATE_ALERT').length).toBeGreaterThan(0);
  });

  it('clicking the enable/disable or delete button does not open the detail popup', () => {
    mockPolling({ data: [rule()], isLoading: false, isError: false, refetch: vi.fn() });

    renderTab();
    fireEvent.click(screen.getByLabelText('Disable rule'));

    expect(screen.queryByRole('button', { name: /edit rule/i })).not.toBeInTheDocument();
  });

  it('opens the edit form pre-filled with the real rule from the detail popup, and saves via updateRule (PUT), not createRule (POST)', async () => {
    mockUpdateRule.mockResolvedValue({});
    const refetch = vi.fn();
    mockPolling({
      data: [rule({
        conditions: [{ field: 'EVENT_TYPE', operator: 'EQ', expectedValue: 'TWIN_UPDATED' }],
        actions: [{ actionType: 'CREATE_ALERT', parameters: { severity: 'CRITICAL' } }],
      })],
      isLoading: false,
      isError: false,
      refetch,
    });

    renderTab();
    fireEvent.click(screen.getByText('High Temp Alert'));
    fireEvent.click(screen.getByRole('button', { name: /edit rule/i }));

    expect(screen.getByText('Edit Rule')).toBeInTheDocument();
    expect(screen.getByDisplayValue('High Temp Alert')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /save changes/i }));

    await vi.waitFor(() => expect(mockUpdateRule).toHaveBeenCalledWith('r1', expect.objectContaining({
      name: 'High Temp Alert',
      conditions: [{ field: 'EVENT_TYPE', operator: 'EQ', expectedValue: 'TWIN_UPDATED' }],
      actions: [{ actionType: 'CREATE_ALERT', parameters: { severity: 'CRITICAL' } }],
    })));
    expect(mockCreateRule).not.toHaveBeenCalled();
    await vi.waitFor(() => expect(refetch).toHaveBeenCalled());
  });

  it('a rule with multiple actions cannot be edited from the detail popup, to avoid silently dropping data', () => {
    mockPolling({
      data: [rule({
        actions: [
          { actionType: 'SEND_COMMAND', parameters: { deviceId: 'device-a', capability: 'BUZZER', command: 'SET_STATE', value: true } },
          { actionType: 'SEND_COMMAND', parameters: { deviceId: 'device-a', capability: 'FAN', command: 'SET_STATE', value: true } },
        ],
      })],
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    });

    renderTab();
    fireEvent.click(screen.getByText('High Temp Alert'));

    expect(screen.getByRole('button', { name: /edit rule/i })).toBeDisabled();
  });
});
