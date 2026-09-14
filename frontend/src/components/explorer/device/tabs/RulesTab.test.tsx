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
vi.mock('../../../../api/ruleApi', () => ({
  ruleApi: { createRule: (...args: any[]) => mockCreateRule(...args) },
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
    condition: 'temperature > 40',
    actions: [{ type: 'CREATE_ALERT', payload: { severity: 'CRITICAL' } }],
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

/** Sets the rules-query result; the rule-matches query defaults to a real, honest empty result unless overridden. */
function mockPolling(rulesResult: any, matchesResult: any = emptyMatchesPolling) {
  mockUsePolling.mockImplementation((key: any) => {
    if (Array.isArray(key) && key[0] === 'rule-matches') return matchesResult;
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
});
