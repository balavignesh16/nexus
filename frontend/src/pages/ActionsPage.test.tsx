import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, within, waitFor } from '@testing-library/react';
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

const mockGetLifecycle = vi.fn();
const mockGetDeadLetters = vi.fn();
vi.mock('../api/actionApi', () => ({
  actionApi: {
    getLifecycle: (...args: any[]) => mockGetLifecycle(...args),
    getDeadLetters: (...args: any[]) => mockGetDeadLetters(...args),
  },
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
    mockGetDeadLetters.mockResolvedValue({ items: [] });
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

    const badge = screen.getByText('SEND_COMMAND').closest<HTMLElement>('[class*="cursor-pointer"]')!;
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
    const row = screen.getByText('SEND_COMMAND').closest<HTMLElement>('[class*="cursor-pointer"]')!;
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

  it('Retry Queue states backend retry behavior without implying a UI list, and Scheduled stays not implemented', () => {
    mockUsePolling.mockReturnValue({ data: [], isLoading: false, error: null });

    renderPage();

    expect(screen.getByText('No retry view yet.')).toBeInTheDocument();
    expect(screen.getByText(/proven not to have been sent/)).toBeInTheDocument();
    expect(screen.queryByText(/retries are not currently part of NEXUS/)).not.toBeInTheDocument();
    expect(screen.getAllByText('Not yet implemented.').length).toBe(1);
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

describe('ActionsPage - command lifecycle visibility (P4-102, P4-103, P4-104, decision D5)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockGetDeadLetters.mockResolvedValue({ items: [] });
  });

  it('labels a queued command by its delivery state instead of the legacy "Delivery unknown"', () => {
    mockUsePolling.mockReturnValue({
      data: [action({ status: 'ACCEPTED_FOR_DISPATCH', deliveryState: 'QUEUED', message: 'Durably accepted' })], isLoading: false, error: null,
    });
    renderPage();
    expect(screen.getAllByText('Queued for delivery').length).toBeGreaterThan(0);
    expect(screen.queryByText('Delivery unknown')).not.toBeInTheDocument();
  });

  it('an accepted row without any delivery record stays honestly "Delivery unknown"', () => {
    mockUsePolling.mockReturnValue({ data: [action({ status: 'ACCEPTED_FOR_DISPATCH' })], isLoading: false, error: null });
    renderPage();
    expect(screen.getAllByText('Delivery unknown').length).toBeGreaterThan(0);
  });

  it('a dead-lettered command shows its reason, and an uncertain one is not shown as a plain failure', () => {
    mockUsePolling.mockReturnValue({
      data: [
        action({ executionId: 'e-a', requestId: 'r-a', status: 'FAILURE', deliveryState: 'DEAD_LETTER', deliveryReason: 'RETRY_EXHAUSTED' }),
        action({ executionId: 'e-b', requestId: 'r-b', status: 'FAILURE', deliveryState: 'DEAD_LETTER', deliveryReason: 'UNCERTAIN_EXPIRED' }),
      ], isLoading: false, error: null,
    });
    renderPage();
    expect(screen.getAllByText(/Dead-lettered: every attempt failed/).length).toBeGreaterThan(0);
    expect(screen.getAllByText('Dead-lettered: outcome uncertain').length).toBeGreaterThan(0);
  });

  it('an unrecognised status or delivery state renders a neutral label and does not throw', () => {
    mockUsePolling.mockReturnValue({
      data: [action({ status: 'SOME_FUTURE_STATUS', deliveryState: 'SOME_FUTURE_STATE' }),
             action({ executionId: 'e-2', requestId: 'r-2', status: 'ACCEPTED_FOR_DISPATCH', deliveryState: 'SOME_FUTURE_STATE' })],
      isLoading: false, error: null,
    });
    expect(() => renderPage()).not.toThrow();
    expect(screen.getAllByText('Status not recognised').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Delivery state not recognised').length).toBeGreaterThan(0);
    expect(screen.queryByText(/SOME_FUTURE_STATE/)).not.toBeInTheDocument();
  });

  it('a live (realtime) row shows its status and a neutral time, never "Invalid Date" or a fabricated "NO ACK YET"', () => {
    mockUsePolling.mockReturnValue({
      data: [{ id: 'e-live', executionId: 'e-live', actionType: 'SEND_COMMAND', status: 'FAILURE', deviceId: 'dev-live', timestamp: undefined, message: 'live' }],
      isLoading: false, error: null,
    });
    renderPage();
    expect(screen.queryByText(/Invalid Date/)).not.toBeInTheDocument();
    expect(screen.getAllByText('Failed').length).toBeGreaterThan(0);
    expect(screen.queryByText('NO ACK YET')).not.toBeInTheDocument();
  });

  it('opens the lifecycle of one command and shows queued, published, acknowledged and verified as SEPARATE labelled lines', async () => {
    mockUsePolling.mockReturnValue({ data: [action({ requestId: 'req-7', status: 'SUCCESS', deliveryState: 'PUBLISHED' })], isLoading: false, error: null });
    mockGetLifecycle.mockResolvedValue({
      commandId: 'req-7', actionType: 'SEND_COMMAND',
      delivery: { state: 'PUBLISHED', attemptCount: 1, maxAttempts: 3, acceptedAt: '2026-01-01T00:00:00Z', firstPublishConfirmedAt: '2026-01-01T00:00:01Z' },
      ack: { status: 'NONE', timedOut: false },
      latency: { acceptedToPublishedMs: 1000 },
      explanation: ['Published to the MQTT broker. Publication does not prove that the device received, accepted or executed the command.'],
      facts: [{ sequence: 1, fact: 'ACCEPTED', attempt: 0, source: 'ACCEPTANCE', occurredAt: '2026-01-01T00:00:00Z' }],
      factsTruncated: false,
    });
    renderPage();

    fireEvent.click(screen.getByText('Lifecycle'));
    await waitFor(() => expect(screen.getByTestId('command-lifecycle-panel')).toBeInTheDocument());
    expect(mockGetLifecycle).toHaveBeenCalledWith('req-7');
    expect(within(screen.getByTestId('lifecycle-queued')).getByText('Accepted for delivery')).toBeInTheDocument();
    expect(within(screen.getByTestId('lifecycle-published')).getByText('Published to MQTT')).toBeInTheDocument();
    expect(within(screen.getByTestId('lifecycle-acknowledged')).getByText('No acknowledgement recorded')).toBeInTheDocument();
    expect(within(screen.getByTestId('lifecycle-verified')).getByText('Not applicable')).toBeInTheDocument();
    expect(screen.getByTestId('lifecycle-no-physical-claim')).toHaveTextContent('do not establish that the device physically acted');
    expect(screen.getByTestId('lifecycle-latency')).toHaveTextContent('accepted → published 1.0 s');
    const panel = screen.getByTestId('command-lifecycle-panel').textContent!.toLowerCase();
    expect(panel).not.toMatch(/exactly[- ]once|is on\b|is off\b/);
  });

  it('a timed-out acknowledgement is shown as an acknowledgement fact on a published command, not as a failure', async () => {
    mockUsePolling.mockReturnValue({ data: [action({ requestId: 'req-8' })], isLoading: false, error: null });
    mockGetLifecycle.mockResolvedValue({
      commandId: 'req-8', actionType: 'SEND_COMMAND', delivery: { state: 'PUBLISHED', attemptCount: 1, maxAttempts: 3 },
      ack: { status: 'NONE', timedOut: true }, explanation: [], facts: [], factsTruncated: false,
    });
    renderPage();
    fireEvent.click(screen.getByText('Lifecycle'));
    await waitFor(() => expect(screen.getByTestId('lifecycle-acknowledged')).toBeInTheDocument());
    expect(within(screen.getByTestId('lifecycle-acknowledged')).getByText('No acknowledgement before the deadline')).toBeInTheDocument();
    expect(within(screen.getByTestId('lifecycle-published')).getByText('Published to MQTT')).toBeInTheDocument();
  });

  it('a lifecycle that cannot be loaded says so and does not throw', async () => {
    mockUsePolling.mockReturnValue({ data: [action({ requestId: 'req-9' })], isLoading: false, error: null });
    mockGetLifecycle.mockRejectedValue(new Error('boom'));
    renderPage();
    fireEvent.click(screen.getByText('Lifecycle'));
    await waitFor(() => expect(screen.getByTestId('lifecycle-unavailable')).toBeInTheDocument());
  });

  it('the lifecycle is fetched only when it is opened, never for every row', () => {
    mockUsePolling.mockReturnValue({ data: [action({ requestId: 'req-1' }), action({ executionId: 'e2', requestId: 'req-2' })], isLoading: false, error: null });
    renderPage();
    expect(mockGetLifecycle).not.toHaveBeenCalled();
  });

  it('lists recent dead letters read-only, with no retry or requeue control', async () => {
    mockUsePolling.mockReturnValue({ data: [], isLoading: false, error: null });
    mockGetDeadLetters.mockResolvedValue({
      items: [{ commandId: 'c1', actionType: 'SEND_COMMAND', terminalReason: 'UNCERTAIN_EXPIRED', attemptCount: 2, maxAttempts: 3, terminalAt: '2026-01-01T00:00:00Z', uncertain: true, acknowledged: false }],
    });
    renderPage();
    await waitFor(() => expect(screen.getByTestId('dead-letters-list')).toBeInTheDocument());
    expect(screen.getByTestId('dead-letters-list')).toHaveTextContent('Dead-lettered: outcome uncertain');
    expect(screen.getByTestId('dead-letters-list')).toHaveTextContent('2/3 attempts');
    expect(within(screen.getByTestId('dead-letters-list')).queryAllByRole('button')).toHaveLength(0);
    expect(mockGetDeadLetters).toHaveBeenCalledWith(5);
  });

  it('an unavailable dead-letter list says so and the page still renders', async () => {
    mockUsePolling.mockReturnValue({ data: [action()], isLoading: false, error: null });
    mockGetDeadLetters.mockRejectedValue(new Error('down'));
    renderPage();
    await waitFor(() => expect(screen.getByTestId('dead-letters-unavailable')).toBeInTheDocument());
    expect(screen.getByText('SEND_COMMAND')).toBeInTheDocument();
  });
});
