import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

const mockGetRules = vi.fn();
vi.mock('../repositories/RepositoryProvider', () => ({
  useRepositories: () => ({ rules: { getRules: mockGetRules } }),
}));

const mockUseActiveDevices = vi.fn();
vi.mock('../hooks/queries', () => ({
  useActiveDevices: () => mockUseActiveDevices(),
}));

const mockUsePolling = vi.fn();
vi.mock('../hooks/usePolling', () => ({
  usePolling: (...args: any[]) => mockUsePolling(...args),
}));

const mockCreateRule = vi.fn();
const mockUpdateRule = vi.fn();
const mockEnableRule = vi.fn().mockResolvedValue(undefined);
const mockDisableRule = vi.fn().mockResolvedValue(undefined);
const mockDeleteRule = vi.fn().mockResolvedValue(undefined);
const mockTransition = vi.fn().mockResolvedValue(null);
vi.mock('../api/ruleApi', () => ({
  ruleApi: {
    createRule: (...args: any[]) => mockCreateRule(...args),
    updateRule: (...args: any[]) => mockUpdateRule(...args),
    enableRule: (...args: any[]) => mockEnableRule(...args),
    disableRule: (...args: any[]) => mockDisableRule(...args),
    deleteRule: (...args: any[]) => mockDeleteRule(...args),
    transition: (...args: any[]) => mockTransition(...args),
  },
}));

const mockGetRuleMatchHistory = vi.fn();
vi.mock('../api/aiApi', () => ({
  aiApi: { getRuleMatchHistory: (...args: any[]) => mockGetRuleMatchHistory(...args) },
}));

import { RulesPage } from './RulesPage';

function rule(overrides: Record<string, any> = {}) {
  return {
    ruleId: 'r1',
    id: 'r1',
    name: 'High Temp Alert',
    description: 'Triggers on high temperature',
    enabled: true,
    priority: 1,
    condition: 'temperature > 40',
    conditions: [{ field: 'VALUE', operator: 'GT', expectedValue: '40' }],
    actions: [{ actionType: 'CREATE_ALERT', payload: { severity: 'CRITICAL' } }],
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

const emptyMatchesPolling = { data: [], isLoading: false, isError: false };

function mockPolling(rulesResult: any, matchesResult: any = emptyMatchesPolling) {
  mockUsePolling.mockImplementation((key: any) => {
    if (Array.isArray(key) && key[0] === 'rule-matches') return matchesResult;
    return { refetch: vi.fn(), ...rulesResult };
  });
}

function renderPage() {
  return render(
    <MemoryRouter>
      <RulesPage />
    </MemoryRouter>
  );
}

describe('RulesPage - real rules, real matches, no fabrication', () => {
  it('activates a new DRAFT with revision zero rather than trying to resume it', async () => {
    mockPolling({ data: [rule({ enabled: false, lifecycle: 'DRAFT', rowRevision: 0, workingVersionState: 'DRAFT' })], isLoading: false });
    renderPage();
    fireEvent.click(screen.getByLabelText('Enable rule'));
    await waitFor(() => expect(mockTransition).toHaveBeenCalledWith('r1', 'activate', 0));
    expect(mockEnableRule).not.toHaveBeenCalled();
  });
  beforeEach(() => {
    vi.clearAllMocks();
    mockTransition.mockReset().mockResolvedValue(null);
    mockUseActiveDevices.mockReturnValue({ data: [{ id: 'dev-1', name: 'Gas Sensor 1' }] });
  });
  it('shows active V1 separately from working V2 and truthful reconciliation diagnostics', () => {
    mockPolling({ data: [rule({ lifecycle: 'ACTIVE', rowRevision: 5, activeVersionId: 'active-v1', workingVersionId: 'draft-v2', workingVersionState: 'DRAFT', reconciliation: 'REQUIRES_REVIEW', originalEnabled: true, diagnostics: [{ code: 'UNIT_MISMATCH', message: 'Expected CELSIUS' }] })], isLoading: false });
    renderPage();
    expect(screen.getByText(/Active: active-v1.*Working: draft-v2/)).toBeInTheDocument();
    expect(screen.getByText('Reconciliation: REQUIRES_REVIEW')).toBeInTheDocument();
    expect(screen.getByText('Imported originally enabled: true')).toBeInTheDocument();
    expect(screen.getByText('UNIT_MISMATCH: Expected CELSIUS')).toBeInTheDocument();
  });
  it.each([
    ['validate', 'Validate draft'], ['activate', 'Activate working version'],
  ])('explicitly invokes %s with the displayed revision', async (operation, label) => {
    const refetch = vi.fn(); mockPolling({ data: [rule({ lifecycle: 'ACTIVE', rowRevision: 5 })], isLoading: false, refetch }); renderPage();
    fireEvent.click(screen.getByRole('button', { name: label }));
    await waitFor(() => expect(mockTransition).toHaveBeenCalledWith('r1', operation, 5)); expect(refetch).toHaveBeenCalled();
  });
  it.each([
    [true, 'ACTIVE', 'Disable rule', 'pause'], [false, 'PAUSED', 'Enable rule', 'resume'], [true, 'ACTIVE', 'Delete rule', 'archive'],
  ])('uses tokened lifecycle controls for %s %s', async (enabled, lifecycle, label, operation) => {
    const refetch = vi.fn(); mockPolling({ data: [rule({ enabled, lifecycle, rowRevision: 9 })], isLoading: false, refetch }); renderPage();
    fireEvent.click(screen.getByRole('button', { name: label as string }));
    await waitFor(() => expect(mockTransition).toHaveBeenCalledWith('r1', operation, 9)); expect(refetch).toHaveBeenCalled(); expect(mockEnableRule).not.toHaveBeenCalled(); expect(mockDisableRule).not.toHaveBeenCalled(); expect(mockDeleteRule).not.toHaveBeenCalled();
  });
  it('shows lifecycle validation failure without inventing activation or retrying', async () => {
    mockTransition.mockRejectedValue(new Error('UNIT_MISMATCH: Expected CELSIUS'));
    mockPolling({ data: [rule({ lifecycle: 'ACTIVE', rowRevision: 5 })], isLoading: false }); renderPage(); fireEvent.click(screen.getByRole('button', { name: 'Activate working version' }));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('UNIT_MISMATCH'));
    expect(mockTransition).toHaveBeenCalledTimes(1);
  });

  it('renders real rules with a real enabled state, never a hardcoded Active', () => {
    mockPolling({ data: [rule({ enabled: true }), rule({ ruleId: 'r2', id: 'r2', name: 'Offline Rule', enabled: false })], isLoading: false });

    renderPage();

    expect(screen.getByText('High Temp Alert')).toBeInTheDocument();
    expect(screen.getByText('Offline Rule')).toBeInTheDocument();
    expect(screen.getAllByText('Disabled').length).toBeGreaterThan(0);
    expect(screen.getByText('Not evaluated')).toBeInTheDocument();
  });

  it('shows real total/enabled/disabled counts calculated from the loaded set', () => {
    mockPolling({
      data: [rule({ enabled: true }), rule({ ruleId: 'r2', id: 'r2', enabled: true }), rule({ ruleId: 'r3', id: 'r3', enabled: false })],
      isLoading: false,
    });

    renderPage();

    expect(screen.getByText('3')).toBeInTheDocument(); // total
    expect(screen.getByText('2')).toBeInTheDocument(); // enabled
    expect(screen.getByText('1')).toBeInTheDocument(); // disabled
  });

  it('shows an honest empty state when no rules exist', () => {
    mockPolling({ data: [], isLoading: false });

    renderPage();

    expect(screen.getByText('No automation rules found.')).toBeInTheDocument();
  });

  it('the device filter calls getRules(deviceId) - a real, existing backend contract', async () => {
    mockPolling({ data: [], isLoading: false });

    renderPage();

    fireEvent.change(screen.getByDisplayValue('All devices'), { target: { value: 'dev-1' } });

    await waitFor(() => {
      const rulesCall = mockUsePolling.mock.calls.find((c) => c[0][0] === 'global-rules' && c[0][1] === 'dev-1');
      expect(rulesCall).toBeDefined();
      rulesCall![1]();
    });
    expect(mockGetRules).toHaveBeenCalledWith('dev-1');
  });

  it('search filters rules by name/description', () => {
    mockPolling({ data: [rule(), rule({ ruleId: 'r2', id: 'r2', name: 'Gas Leak Detector', description: 'Gas alert' })], isLoading: false });

    renderPage();
    fireEvent.change(screen.getByPlaceholderText('Search rules...'), { target: { value: 'Gas' } });

    expect(screen.getByText('Gas Leak Detector')).toBeInTheDocument();
    expect(screen.queryByText('High Temp Alert')).not.toBeInTheDocument();
  });

  it('never shows the dead "Open Studio" control', () => {
    mockPolling({ data: [rule()], isLoading: false });

    renderPage();

    expect(screen.queryByText('Open Studio')).not.toBeInTheDocument();
  });

  it('never renders legacy blue primary styling on the enabled badge', () => {
    mockPolling({ data: [rule({ enabled: true })], isLoading: false });

    renderPage();

    const badge = screen.getByText('Active').closest('div');
    expect(badge?.getAttribute('style') || '').not.toContain('blue');
  });

  it('Create Rule opens the real CreateRuleModal without a device context, and POSTs a real rule', async () => {
    const refetch = vi.fn();
    mockPolling({ data: [rule()], isLoading: false, refetch });
    mockCreateRule.mockResolvedValue({ ruleId: 'new-rule' });

    renderPage();

    fireEvent.click(screen.getByRole('button', { name: /create rule/i }));
    expect(screen.getByText('Create Rule', { selector: 'h3' })).toBeInTheDocument();

    fireEvent.change(screen.getByPlaceholderText('e.g. High Temperature Alert'), { target: { value: 'New Global Rule' } });
    // No trigger device selected -> legacy platform-wide condition path;
    // fill in the generic condition value (Phase 13 requires a non-blank one).
    const legacyValueInputs = screen.getAllByRole('textbox').filter((el) => el.className.includes('font-mono') && el.tagName === 'INPUT');
    fireEvent.change(legacyValueInputs[0], { target: { value: 'TWIN_UPDATED' } });
    const createButtons = screen.getAllByRole('button', { name: /create rule/i });
    fireEvent.click(createButtons[createButtons.length - 1]);

    await waitFor(() => expect(mockCreateRule).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'New Global Rule', conditions: [{ field: 'EVENT_TYPE', operator: 'EQ', expectedValue: 'TWIN_UPDATED' }] })
    ));
    await waitFor(() => expect(refetch).toHaveBeenCalled());
  });

  it('renders real recent rule matches and links to the real AI explanation via matchId', () => {
    mockPolling({ data: [], isLoading: false }, { data: [match({ ruleName: 'Gas Leak Detector' })], isLoading: false, isError: false });

    renderPage();

    expect(screen.getByText('Gas Leak Detector')).toBeInTheDocument();
    const link = screen.getByText('Gas Leak Detector').closest('a');
    expect(link).toHaveAttribute('href', '/ai?matchId=match-1');
  });

  it('shows an honest empty state when there are no recent matches', () => {
    mockPolling({ data: [], isLoading: false });

    renderPage();

    expect(screen.getByText('No rule matches recorded yet.')).toBeInTheDocument();
  });

  it('shows a distinct error state for matches, independent of the rules section', () => {
    mockPolling({ data: [rule()], isLoading: false }, { data: [], isLoading: false, isError: true });

    renderPage();

    expect(screen.getByTestId('recent-matches-error')).toBeInTheDocument();
    expect(screen.getByText('High Temp Alert')).toBeInTheDocument();
  });

  it('requests the global recent-matches history using the same real, established API as AiInsightsPage', () => {
    mockPolling({ data: [], isLoading: false });

    renderPage();

    const matchesCall = mockUsePolling.mock.calls.find((c) => c[0][0] === 'rule-matches');
    expect(matchesCall).toBeDefined();
    matchesCall![1]();
    expect(mockGetRuleMatchHistory).toHaveBeenCalledWith(50);
  });

  // ---------- Rule detail popup + edit-in-place ----------

  it('clicking a rule card opens a detail popup showing its real conditions and actions', () => {
    mockUseActiveDevices.mockReturnValue({ data: [] });
    mockPolling({
      data: [rule({
        conditions: [
          { field: 'DEVICE_ID', operator: 'EQ', expectedValue: 'dev-1' },
          { field: 'VALUE', operator: 'GT', expectedValue: '35', capabilityType: 'TEMPERATURE' },
        ],
        actions: [{ actionType: 'SEND_COMMAND', parameters: { deviceId: 'dev-1', capability: 'FAN', command: 'SET_STATE', value: true } }],
      })],
      isLoading: false,
    });

    renderPage();
    fireEvent.click(screen.getByText('High Temp Alert'));

    expect(screen.getByText(/TEMPERATURE/)).toBeInTheDocument();
    expect(screen.getAllByText('SEND_COMMAND').length).toBeGreaterThan(0);
    expect(screen.getByText('Set FAN to ON on dev-1')).toBeInTheDocument();
  });

  it('clicking the enable/disable or delete button does not open the detail popup', () => {
    mockUseActiveDevices.mockReturnValue({ data: [] });
    mockPolling({ data: [rule()], isLoading: false });

    renderPage();
    fireEvent.click(screen.getByLabelText('Disable rule'));

    expect(screen.queryByRole('button', { name: /edit rule/i })).not.toBeInTheDocument();
  });

  it('opens the edit form pre-filled with the real rule from the detail popup, and saves via updateRule (PUT), not createRule (POST)', async () => {
    mockUpdateRule.mockResolvedValue({});
    mockUseActiveDevices.mockReturnValue({ data: [] });
    const refetch = vi.fn();
    mockPolling({
      data: [rule({
        conditions: [{ field: 'EVENT_TYPE', operator: 'EQ', expectedValue: 'TWIN_UPDATED' }],
        actions: [{ actionType: 'CREATE_ALERT', parameters: { severity: 'CRITICAL' } }],
      })],
      isLoading: false,
      refetch,
    });

    renderPage();
    fireEvent.click(screen.getByText('High Temp Alert'));
    fireEvent.click(screen.getByRole('button', { name: /edit rule/i }));

    expect(screen.getByText('Edit Rule')).toBeInTheDocument();
    expect(screen.getByDisplayValue('High Temp Alert')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /save changes/i }));

    await waitFor(() => expect(mockUpdateRule).toHaveBeenCalledWith('r1', expect.objectContaining({
      name: 'High Temp Alert',
      conditions: [{ field: 'EVENT_TYPE', operator: 'EQ', expectedValue: 'TWIN_UPDATED' }],
      actions: [{ actionType: 'CREATE_ALERT', parameters: { severity: 'CRITICAL' } }],
    })));
    expect(mockCreateRule).not.toHaveBeenCalled();
    await waitFor(() => expect(refetch).toHaveBeenCalled());
  });

  it('a rule with multiple actions cannot be edited from the detail popup, to avoid silently dropping data', () => {
    mockUseActiveDevices.mockReturnValue({ data: [] });
    mockPolling({
      data: [rule({
        actions: [
          { actionType: 'SEND_COMMAND', parameters: { deviceId: 'dev-1', capability: 'BUZZER', command: 'SET_STATE', value: true } },
          { actionType: 'SEND_COMMAND', parameters: { deviceId: 'dev-1', capability: 'FAN', command: 'SET_STATE', value: true } },
        ],
      })],
      isLoading: false,
    });

    renderPage();
    fireEvent.click(screen.getByText('High Temp Alert'));

    expect(screen.getByRole('button', { name: /edit rule/i })).toBeDisabled();
  });
});
