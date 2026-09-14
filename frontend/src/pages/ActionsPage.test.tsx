import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

const mockGetActions = vi.fn();
vi.mock('../repositories/RepositoryProvider', () => ({
  useRepositories: () => ({ actions: { getActions: mockGetActions } }),
}));

const mockUsePolling = vi.fn();
vi.mock('../hooks/usePolling', () => ({
  usePolling: (...args: any[]) => mockUsePolling(...args),
}));

const mockOpenInspector = vi.fn();
vi.mock('../components/ui/InspectorProvider', () => ({
  useInspector: () => ({ openInspector: mockOpenInspector }),
}));

import ActionsPage from './ActionsPage';

function action(overrides: Record<string, any> = {}) {
  return {
    executionId: 'exec-1',
    requestId: 'req-1',
    matchId: null,
    deviceId: 'dev-1',
    actionType: 'SEND_COMMAND',
    parameters: { deviceId: 'dev-1' },
    status: 'SUCCESS',
    timestamp: '2026-01-01T00:00:00Z',
    message: 'Command published to MQTT',
    ackStatus: null,
    ackReceivedAt: null,
    ackMessage: null,
    ...overrides,
  };
}

function renderPage() {
  return render(
    <MemoryRouter>
      <ActionsPage />
    </MemoryRouter>
  );
}

describe('ActionsPage - real action history, no fabrication', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  // ---------- Rendering ----------

  it('renders real action rows: type, device, timestamp, execution status', () => {
    mockUsePolling.mockReturnValue({ data: [action({ actionType: 'SEND_COMMAND', deviceId: 'dev-real-1' })], isLoading: false, error: null });

    renderPage();

    expect(screen.getByText('SEND_COMMAND')).toBeInTheDocument();
    expect(screen.getByText(/dev-real-1/)).toBeInTheDocument();
    expect(screen.getAllByText('SUCCESS').length).toBeGreaterThan(0);
  });

  it('requests the real bounded action history (100 records, no device filter)', () => {
    mockUsePolling.mockReturnValue({ data: [], isLoading: false, error: null });

    renderPage();

    expect(mockUsePolling).toHaveBeenCalledWith(
      ['global-actions-history'],
      expect.any(Function),
      expect.anything()
    );
    const queryFn = mockUsePolling.mock.calls[0][1];
    queryFn();
    expect(mockGetActions).toHaveBeenCalledWith(undefined, 100);
  });

  // ---------- Status semantics ----------

  it('renders real FAILURE status using the real backend spelling for comparison, humanized for display', () => {
    mockUsePolling.mockReturnValue({ data: [action({ status: 'FAILURE' })], isLoading: false, error: null });

    renderPage();

    const badge = screen.getByText('SEND_COMMAND').closest('[class*="cursor-pointer"]')!;
    expect(within(badge).getByText('Failed')).toBeInTheDocument();
  });

  it('the Failed filter pill matches real FAILURE actions (not a broken "FAILED" comparison)', () => {
    mockUsePolling.mockReturnValue({
      data: [action({ status: 'FAILURE', actionType: 'FAILING_ACTION' }), action({ status: 'SUCCESS', actionType: 'OK_ACTION' })],
      isLoading: false,
      error: null,
    });

    renderPage();
    fireEvent.click(screen.getByRole('button', { name: 'FAILURE' }));

    expect(screen.getByText('FAILING_ACTION')).toBeInTheDocument();
    expect(screen.queryByText('OK_ACTION')).not.toBeInTheDocument();
  });

  // ---------- ACK semantics ----------

  it('renders a real ACCEPTED acknowledgement', () => {
    mockUsePolling.mockReturnValue({ data: [action({ ackStatus: 'ACCEPTED' })], isLoading: false, error: null });

    renderPage();

    expect(screen.getByText('ACCEPTED')).toBeInTheDocument();
  });

  it('renders a real REJECTED acknowledgement', () => {
    mockUsePolling.mockReturnValue({ data: [action({ ackStatus: 'REJECTED' })], isLoading: false, error: null });

    renderPage();

    expect(screen.getByText('REJECTED')).toBeInTheDocument();
  });

  it('a null ackStatus renders NO ACK YET, never a fabricated failure', () => {
    mockUsePolling.mockReturnValue({ data: [action({ ackStatus: null })], isLoading: false, error: null });

    renderPage();

    expect(screen.getByText('NO ACK YET')).toBeInTheDocument();
    const row = screen.getByText('SEND_COMMAND').closest('[class*="cursor-pointer"]')!;
    expect(within(row).queryByText('Failed')).not.toBeInTheDocument();
  });

  it('does not show an acknowledgement row for non-SEND_COMMAND actions', () => {
    mockUsePolling.mockReturnValue({ data: [action({ actionType: 'CREATE_ALERT', ackStatus: null })], isLoading: false, error: null });

    renderPage();

    expect(screen.queryByText('Device ack:')).not.toBeInTheDocument();
  });

  // ---------- Rule Match ----------

  it('a real matchId renders a real navigation link to /ai?matchId=', () => {
    mockUsePolling.mockReturnValue({ data: [action({ matchId: 'match-real-123' })], isLoading: false, error: null });

    renderPage();

    const link = screen.getByText(/Rule Match/).closest('a');
    expect(link).toHaveAttribute('href', '/ai?matchId=match-real-123');
  });

  it('no matchId means no Rule Match link is rendered - never a fabricated one', () => {
    mockUsePolling.mockReturnValue({ data: [action({ matchId: null })], isLoading: false, error: null });

    renderPage();

    expect(screen.queryByText(/Rule Match/)).not.toBeInTheDocument();
  });

  it('opening the inspector for an action without a matchId shows a truthful N/A, never a fabricated match', () => {
    mockUsePolling.mockReturnValue({ data: [action({ matchId: null, actionType: 'SEND_COMMAND' })], isLoading: false, error: null });

    renderPage();
    fireEvent.click(screen.getByText('SEND_COMMAND'));

    expect(mockOpenInspector).toHaveBeenCalled();
    const item = mockOpenInspector.mock.calls[0][0];
    expect(item.properties['Rule Match ID']).toBe('N/A');
  });

  it('opening the inspector for an action with a real matchId passes it through untouched', () => {
    mockUsePolling.mockReturnValue({ data: [action({ matchId: 'match-abc' })], isLoading: false, error: null });

    renderPage();
    fireEvent.click(screen.getByText('SEND_COMMAND'));

    const item = mockOpenInspector.mock.calls[0][0];
    expect(item.properties['Rule Match ID']).toBe('match-abc');
  });

  // ---------- Controls ----------

  it('search filters by real action type', () => {
    mockUsePolling.mockReturnValue({
      data: [action({ actionType: 'SEND_COMMAND' }), action({ actionType: 'CREATE_ALERT', executionId: 'exec-2' })],
      isLoading: false,
      error: null,
    });

    renderPage();
    fireEvent.change(screen.getByPlaceholderText('Search actions...'), { target: { value: 'ALERT' } });

    expect(screen.getByText('CREATE_ALERT')).toBeInTheDocument();
    expect(screen.queryByText('SEND_COMMAND')).not.toBeInTheDocument();
  });

  it('search filters by real target device', () => {
    mockUsePolling.mockReturnValue({
      data: [action({ deviceId: 'dev-alpha', actionType: 'A' }), action({ deviceId: 'dev-beta', actionType: 'B', executionId: 'exec-2' })],
      isLoading: false,
      error: null,
    });

    renderPage();
    fireEvent.change(screen.getByPlaceholderText('Search actions...'), { target: { value: 'beta' } });

    expect(screen.getByText('B')).toBeInTheDocument();
    expect(screen.queryByText('A')).not.toBeInTheDocument();
  });

  it('never shows the dead "Advanced" control', () => {
    mockUsePolling.mockReturnValue({ data: [action()], isLoading: false, error: null });

    renderPage();

    expect(screen.queryByText('Advanced')).not.toBeInTheDocument();
  });

  // ---------- States ----------

  it('shows a loading state before actions arrive', () => {
    mockUsePolling.mockReturnValue({ data: [], isLoading: true, error: null });

    const { container } = renderPage();

    expect(container.querySelector('[class*="animate"]')).toBeTruthy();
  });

  it('shows an honest empty state - never a fabricated sample action - when there are genuinely no actions', () => {
    mockUsePolling.mockReturnValue({ data: [], isLoading: false, error: null });

    renderPage();

    expect(screen.getByText('No actions recorded yet.')).toBeInTheDocument();
  });

  it('shows a distinct "no matches" state when filters exclude every real action', () => {
    mockUsePolling.mockReturnValue({ data: [action({ actionType: 'SEND_COMMAND' })], isLoading: false, error: null });

    renderPage();
    fireEvent.change(screen.getByPlaceholderText('Search actions...'), { target: { value: 'nonexistent-xyz' } });

    expect(screen.getByText('No actions match your filters.')).toBeInTheDocument();
  });

  it('shows an explicit error state - never silently reinterpreted as an empty state - when the request fails', () => {
    mockUsePolling.mockReturnValue({ data: [], isLoading: false, error: new Error('Failed to fetch') });

    renderPage();

    expect(screen.getByText('Could not load action history.')).toBeInTheDocument();
    expect(screen.queryByText('No actions recorded yet.')).not.toBeInTheDocument();
  });

  // ---------- Honest stubs ----------

  it('Retry Queue and Scheduled remain clearly labeled as not implemented, never implying real functionality', () => {
    mockUsePolling.mockReturnValue({ data: [], isLoading: false, error: null });

    renderPage();

    expect(screen.getAllByText('Not yet implemented.').length).toBe(2);
  });

  // ---------- Real KPI summary ----------

  it('computes real KPI counts from the loaded action set, never a fabricated metric', () => {
    mockUsePolling.mockReturnValue({
      data: [action({ status: 'SUCCESS' }), action({ status: 'SUCCESS', executionId: 'exec-2' }), action({ status: 'FAILURE', executionId: 'exec-3' })],
      isLoading: false,
      error: null,
    });

    renderPage();

    expect(screen.getByText('3')).toBeInTheDocument(); // total
    expect(screen.getByText('66.7')).toBeInTheDocument(); // success rate
  });

  it('shows an honest unavailable success rate when there are no actions to calculate from', () => {
    mockUsePolling.mockReturnValue({ data: [], isLoading: false, error: null });

    renderPage();

    expect(screen.getByText('—')).toBeInTheDocument();
  });
});
