import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';

const mockGetEvents = vi.fn();
vi.mock('../../../../repositories/RepositoryProvider', () => ({
  useRepositories: () => ({ events: { getEvents: mockGetEvents } }),
}));

const mockUsePolling = vi.fn();
vi.mock('../../../../hooks/usePolling', () => ({
  usePolling: (...args: any[]) => mockUsePolling(...args),
}));

import { EventsTab } from './EventsTab';

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

    render(<EventsTab deviceId="device-a" />);

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

    render(<EventsTab deviceId="device-a" />);

    expect(screen.getByText('SENSOR_VALUE_CHANGED')).toBeInTheDocument();
  });

  it('shows an honest empty state when this device has no events', () => {
    mockUsePolling.mockReturnValue({ data: [], isLoading: false });

    render(<EventsTab deviceId="device-a" />);

    expect(screen.getByText('Event Pipeline Listening')).toBeInTheDocument();
  });
});
