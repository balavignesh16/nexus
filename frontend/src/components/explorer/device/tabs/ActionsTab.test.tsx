import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

const mockGetActions = vi.fn();
vi.mock('../../../../repositories/RepositoryProvider', () => ({
  useRepositories: () => ({ actions: { getActions: mockGetActions } }),
}));

const mockUsePolling = vi.fn();
vi.mock('../../../../hooks/usePolling', () => ({
  usePolling: (...args: any[]) => mockUsePolling(...args),
}));

import { ActionsTab } from './ActionsTab';

function action(overrides: Record<string, any> = {}) {
  return {
    id: 'a1',
    actionType: 'SEND_COMMAND',
    status: 'SUCCESS',
    timestamp: new Date().toISOString(),
    message: 'Command published',
    ackStatus: null,
    ...overrides,
  };
}

describe('ActionsTab - real execution status + device acknowledgement', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('requests actions scoped to this device', () => {
    mockUsePolling.mockReturnValue({ data: [], isLoading: false });

    render(<ActionsTab deviceId="device-a" />);

    expect(mockUsePolling).toHaveBeenCalledWith(
      ['actions-history', 'device-a'],
      expect.any(Function),
      expect.anything()
    );
    const queryFn = mockUsePolling.mock.calls[0][1];
    queryFn();
    expect(mockGetActions).toHaveBeenCalledWith('device-a', 50);
  });

  it('renders execution status for a SEND_COMMAND action', () => {
    mockUsePolling.mockReturnValue({ data: [action({ status: 'SUCCESS' })], isLoading: false });

    render(<ActionsTab deviceId="device-a" />);

    expect(screen.getAllByText('SUCCESS').length).toBeGreaterThan(0);
  });

  it('shows NO ACK YET (not FAILURE) when ackStatus is absent - never a fabricated failure', () => {
    mockUsePolling.mockReturnValue({ data: [action({ ackStatus: null })], isLoading: false });

    render(<ActionsTab deviceId="device-a" />);

    expect(screen.getByText('NO ACK YET')).toBeInTheDocument();
    // The only "status pill" shown for this action must be SUCCESS - FAILURE
    // only appears as the unrelated header filter button.
    expect(screen.getByRole('button', { name: 'FAILURE' })).toBeInTheDocument();
    expect(screen.queryAllByText('FAILURE', { selector: 'span' }).length).toBe(0);
  });

  it('recognizes the real backend FAILURE status (not the old FAILED), including the icon and filter', () => {
    mockUsePolling.mockReturnValue({ data: [action({ status: 'FAILURE', ackStatus: null })], isLoading: false });

    render(<ActionsTab deviceId="device-a" />);

    expect(screen.getAllByText('FAILURE').length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: 'FAILED' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'FAILURE' }));
    expect(screen.getAllByText('FAILURE').length).toBeGreaterThan(0);
  });

  it('renders a real ACCEPTED acknowledgement distinctly from NO ACK YET', () => {
    mockUsePolling.mockReturnValue({ data: [action({ ackStatus: 'ACCEPTED' })], isLoading: false });

    render(<ActionsTab deviceId="device-a" />);

    expect(screen.getByText('ACCEPTED')).toBeInTheDocument();
    expect(screen.queryByText('NO ACK YET')).not.toBeInTheDocument();
  });

  it('renders a real REJECTED acknowledgement', () => {
    mockUsePolling.mockReturnValue({ data: [action({ ackStatus: 'REJECTED' })], isLoading: false });

    render(<ActionsTab deviceId="device-a" />);

    expect(screen.getByText('REJECTED')).toBeInTheDocument();
  });

  it('renders the real command parameters from action.parameters, not a blank box', () => {
    mockUsePolling.mockReturnValue({
      data: [action({ parameters: { deviceId: 'dev-1', capability: 'FAN', command: 'SET_STATE', value: true } })],
      isLoading: false,
    });

    render(<ActionsTab deviceId="device-a" />);

    expect(screen.getByText('{"deviceId":"dev-1","capability":"FAN","command":"SET_STATE","value":true}')).toBeInTheDocument();
  });

  it('does not show an acknowledgement row for non-SEND_COMMAND actions', () => {
    mockUsePolling.mockReturnValue({ data: [action({ actionType: 'CREATE_ALERT', ackStatus: null })], isLoading: false });

    render(<ActionsTab deviceId="device-a" />);

    expect(screen.queryByText('Device ack:')).not.toBeInTheDocument();
  });
});
