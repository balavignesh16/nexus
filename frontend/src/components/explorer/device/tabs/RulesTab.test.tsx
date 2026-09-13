import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

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

describe('RulesTab - device-scoped rules', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('requests rules scoped to the current device, not the global rule set', () => {
    mockUsePolling.mockReturnValue({ data: [], isLoading: false, isError: false, refetch: vi.fn() });

    render(<RulesTab deviceId="device-a" />);

    expect(mockUsePolling).toHaveBeenCalledWith(
      ['rules', 'device-a'],
      expect.any(Function),
      expect.anything()
    );
    // The queryFn passed to usePolling must call getRules with this device's id.
    const queryFn = mockUsePolling.mock.calls[0][1];
    queryFn();
    expect(mockGetRules).toHaveBeenCalledWith('device-a');
  });

  it('renders rules returned for this device', () => {
    mockUsePolling.mockReturnValue({ data: [rule()], isLoading: false, isError: false, refetch: vi.fn() });

    render(<RulesTab deviceId="device-a" />);

    expect(screen.getByText('High Temp Alert')).toBeInTheDocument();
  });

  it('shows an honest empty state when this device genuinely has no rules', () => {
    mockUsePolling.mockReturnValue({ data: [], isLoading: false, isError: false, refetch: vi.fn() });

    render(<RulesTab deviceId="device-a" />);

    expect(screen.getByText('No Automations Bound')).toBeInTheDocument();
  });

  it('shows a distinct loading state, never an empty state mistaken for zero rules', () => {
    mockUsePolling.mockReturnValue({ data: undefined, isLoading: true, isError: false, refetch: vi.fn() });

    render(<RulesTab deviceId="device-a" />);

    expect(screen.queryByText('No Automations Bound')).not.toBeInTheDocument();
  });

  it('shows a distinct error state, never silently falling back to the empty state', () => {
    mockUsePolling.mockReturnValue({
      data: [],
      isLoading: false,
      isError: true,
      error: new Error('Failed to fetch rules: 500'),
      refetch: vi.fn(),
    });

    render(<RulesTab deviceId="device-a" />);

    expect(screen.getByTestId('rules-tab-error')).toBeInTheDocument();
    expect(screen.queryByText('No Automations Bound')).not.toBeInTheDocument();
  });

  it('refetches after a rule is successfully created, so it appears in this device scope', async () => {
    const refetch = vi.fn();
    mockUsePolling.mockReturnValue({ data: [], isLoading: false, isError: false, refetch });
    mockCreateRule.mockResolvedValue({ ruleId: 'new-rule' });

    render(<RulesTab deviceId="device-a" />);

    fireEvent.click(screen.getAllByRole('button', { name: /create rule/i })[0]);
    const nameInput = screen.getByPlaceholderText('e.g. High Temperature Alert');
    fireEvent.change(nameInput, { target: { value: 'New Rule' } });

    const createButtons = screen.getAllByRole('button', { name: /create rule/i });
    fireEvent.click(createButtons[createButtons.length - 1]);

    await vi.waitFor(() => expect(mockCreateRule).toHaveBeenCalled());
    await vi.waitFor(() => expect(refetch).toHaveBeenCalled());
  });
});
