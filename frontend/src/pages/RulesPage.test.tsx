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
vi.mock('../api/ruleApi', () => ({
  ruleApi: { createRule: (...args: any[]) => mockCreateRule(...args) },
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
  beforeEach(() => {
    vi.clearAllMocks();
    mockUseActiveDevices.mockReturnValue({ data: [{ id: 'dev-1', name: 'Gas Sensor 1' }] });
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
    const createButtons = screen.getAllByRole('button', { name: /create rule/i });
    fireEvent.click(createButtons[createButtons.length - 1]);

    await waitFor(() => expect(mockCreateRule).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'New Global Rule', conditions: [{ field: 'DEVICE_ID', operator: 'EQ', expectedValue: '' }] })
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
});
