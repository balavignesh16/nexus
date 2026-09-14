import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';

const mockGetEvents = vi.fn();
vi.mock('../../../../repositories/RepositoryProvider', () => ({
  useRepositories: () => ({ events: { getEvents: mockGetEvents } }),
}));

const mockUsePolling = vi.fn();
vi.mock('../../../../hooks/usePolling', () => ({
  usePolling: (...args: any[]) => mockUsePolling(...args),
}));

import { EventsTab } from './EventsTab';

function renderTab(deviceId = 'device-a') {
  return render(
    <MemoryRouter>
      <EventsTab deviceId={deviceId} />
    </MemoryRouter>
  );
}

function event(overrides: Record<string, any> = {}) {
  return {
    id: 'evt-1',
    type: 'SENSOR_VALUE_CHANGED',
    severity: 'INFO',
    timestamp: '2026-01-01T00:00:00Z',
    message: 'Value changed',
    ...overrides,
  };
}

describe('EventsTab - device-scoped events (Phase 3 backend fix)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('requests events scoped to this device, not the global event stream', () => {
    mockUsePolling.mockReturnValue({ data: [], isLoading: false });

    renderTab('device-a');

    expect(mockUsePolling).toHaveBeenCalledWith(
      ['events-history', 'device-a'],
      expect.any(Function),
      expect.anything()
    );
    const queryFn = mockUsePolling.mock.calls[0][1];
    queryFn();
    expect(mockGetEvents).toHaveBeenCalledWith('device-a', 100);
  });

  it('renders real events returned for this device', () => {
    mockUsePolling.mockReturnValue({ data: [event()], isLoading: false });

    renderTab();

    expect(screen.getByText('SENSOR_VALUE_CHANGED')).toBeInTheDocument();
  });

  it('shows an honest empty state when this device has no events', () => {
    mockUsePolling.mockReturnValue({ data: [], isLoading: false });

    renderTab();

    expect(screen.getByText('Event pipeline listening.')).toBeInTheDocument();
  });

  it('the CRITICAL filter (not the old ERROR) matches real critical events', () => {
    mockUsePolling.mockReturnValue({
      data: [event({ severity: 'CRITICAL', type: 'GAS_LEAK' }), event({ severity: 'INFO', type: 'HEARTBEAT' })],
      isLoading: false,
    });

    renderTab();

    expect(screen.queryByRole('button', { name: 'ERROR' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'CRITICAL' }));

    expect(screen.getByText('GAS_LEAK')).toBeInTheDocument();
    expect(screen.queryByText('HEARTBEAT')).not.toBeInTheDocument();
  });

  it('renders a real View Trace link using the event\'s real correlationId', () => {
    mockUsePolling.mockReturnValue({
      data: [event({ correlationId: 'corr-abc-123' })],
      isLoading: false,
    });

    renderTab();

    const link = screen.getByText('View Trace').closest('a');
    expect(link).toHaveAttribute('href', '/pipeline?correlationId=corr-abc-123');
  });

  it('does not render View Trace when the event has no real correlationId', () => {
    mockUsePolling.mockReturnValue({ data: [event({ correlationId: undefined })], isLoading: false });

    renderTab();

    expect(screen.queryByText('View Trace')).not.toBeInTheDocument();
  });
});
