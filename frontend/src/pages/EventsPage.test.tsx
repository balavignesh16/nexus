import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

const mockGetEvents = vi.fn();
vi.mock('../repositories/RepositoryProvider', () => ({
  useRepositories: () => ({ events: { getEvents: mockGetEvents } }),
}));

const mockUsePolling = vi.fn();
vi.mock('../hooks/usePolling', () => ({
  usePolling: (...args: any[]) => mockUsePolling(...args),
}));

const mockOpenInspector = vi.fn();
vi.mock('../components/ui/InspectorProvider', () => ({
  useInspector: () => ({ openInspector: mockOpenInspector }),
}));

const mockNavigate = vi.fn();
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom');
  return { ...actual, useNavigate: () => mockNavigate };
});

import EventsPage from './EventsPage';

function event(overrides: Record<string, any> = {}) {
  return {
    id: 'evt-1',
    type: 'SENSOR_VALUE_CHANGED',
    deviceId: 'device-1',
    timestamp: '2026-01-01T00:00:00Z',
    severity: 'INFO',
    message: 'Value changed',
    correlationId: null,
    ...overrides,
  };
}

describe('EventsPage - real correlation/pipeline trace links, no fabricated history', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('never passes a fabricated history array to the inspector', () => {
    mockUsePolling.mockReturnValue({ data: [event({ correlationId: 'corr-1' })], isLoading: false });

    render(<EventsPage />);
    fireEvent.click(screen.getByText('SENSOR_VALUE_CHANGED'));

    expect(mockOpenInspector).toHaveBeenCalled();
    const item = mockOpenInspector.mock.calls[0][0];
    expect(item.history).toBeUndefined();
  });

  it('never shows the old fabricated "Rule Triggered / Action Dispatched / Twin Updated" steps', () => {
    mockUsePolling.mockReturnValue({ data: [event({ correlationId: 'corr-1' })], isLoading: false });

    render(<EventsPage />);

    expect(screen.queryByText('Rule Triggered')).not.toBeInTheDocument();
    expect(screen.queryByText('Action Dispatched')).not.toBeInTheDocument();
    expect(screen.queryByText('Twin Updated')).not.toBeInTheDocument();
  });

  it('shows a real "View Trace" link only when a real correlationId exists', () => {
    mockUsePolling.mockReturnValue({ data: [event({ correlationId: null })], isLoading: false });

    render(<EventsPage />);

    expect(screen.queryByText(/View Trace/)).not.toBeInTheDocument();
  });

  it('navigates to the real pipeline trace when View Trace is clicked', () => {
    mockUsePolling.mockReturnValue({ data: [event({ correlationId: 'corr-real-123' })], isLoading: false });

    render(<EventsPage />);
    fireEvent.click(screen.getByText(/View Trace/));

    expect(mockNavigate).toHaveBeenCalledWith('/pipeline?correlationId=corr-real-123');
  });

  it('clicking View Trace does not also open the inspector', () => {
    mockUsePolling.mockReturnValue({ data: [event({ correlationId: 'corr-real-123' })], isLoading: false });

    render(<EventsPage />);
    fireEvent.click(screen.getByText(/View Trace/));

    expect(mockOpenInspector).not.toHaveBeenCalled();
  });

  it('shows an honest empty state with no events', () => {
    mockUsePolling.mockReturnValue({ data: [], isLoading: false });

    render(<EventsPage />);

    expect(screen.getByText('No events found matching your criteria.')).toBeInTheDocument();
  });

  it('the CRITICAL severity pill filters to only real critical events', () => {
    mockUsePolling.mockReturnValue({
      data: [event({ severity: 'CRITICAL', type: 'GAS_LEAK' }), event({ severity: 'INFO', type: 'HEARTBEAT' })],
      isLoading: false,
    });

    render(<EventsPage />);
    fireEvent.click(screen.getByRole('button', { name: 'CRITICAL' }));

    expect(screen.getByText('GAS_LEAK')).toBeInTheDocument();
    expect(screen.queryByText('HEARTBEAT')).not.toBeInTheDocument();
  });

  it('shows real summary counts computed from the loaded event set, not a fabricated total', () => {
    mockUsePolling.mockReturnValue({
      data: [
        event({ severity: 'CRITICAL' }),
        event({ severity: 'CRITICAL' }),
        event({ severity: 'WARNING' }),
        event({ severity: 'INFO' }),
      ],
      isLoading: false,
    });

    render(<EventsPage />);

    expect(screen.getByText('4')).toBeInTheDocument(); // total
    expect(screen.getByText('2')).toBeInTheDocument(); // critical
    expect(screen.getAllByText('1').length).toBe(2); // warning + info
  });

  it('never shows the dead "Advanced" filter control', () => {
    mockUsePolling.mockReturnValue({ data: [event()], isLoading: false });

    render(<EventsPage />);

    expect(screen.queryByText('Advanced')).not.toBeInTheDocument();
  });

  it('shows the real severity and device on each row, not just a bare UUID', () => {
    mockUsePolling.mockReturnValue({ data: [event({ severity: 'WARNING', deviceId: 'device-42' })], isLoading: false });

    render(<EventsPage />);

    expect(screen.getAllByText('WARNING').length).toBeGreaterThan(0);
    expect(screen.getByText('device-42')).toBeInTheDocument();
  });

  it('shows an honest "Unavailable" for device/correlationId rather than a silently empty row when genuinely absent', () => {
    mockUsePolling.mockReturnValue({ data: [event({ deviceId: null, correlationId: null })], isLoading: false });

    render(<EventsPage />);

    expect(screen.getAllByText('Unavailable').length).toBe(2);
  });
});
