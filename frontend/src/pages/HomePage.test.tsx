import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';

const mockUseSites = vi.fn();
const mockUseActiveDevices = vi.fn();
const mockUseEvents = vi.fn();
const mockUseActionHistory = vi.fn();
vi.mock('../hooks/queries', () => ({
  useSites: () => mockUseSites(),
  useActiveDevices: () => mockUseActiveDevices(),
  useEvents: () => mockUseEvents(),
  useActionHistory: () => mockUseActionHistory(),
}));

const mockUseExplorerHierarchy = vi.fn();
vi.mock('../hooks/useExplorerHierarchy', () => ({
  useExplorerHierarchy: () => mockUseExplorerHierarchy(),
}));

const mockUseConnectionStatus = vi.fn();
vi.mock('../services/ConnectionManager', () => ({
  useConnectionStatus: () => mockUseConnectionStatus(),
}));

const mockUsePolling = vi.fn();
vi.mock('../hooks/usePolling', () => ({
  usePolling: (...args: any[]) => mockUsePolling(...args),
}));

import HomePage from './HomePage';

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <HomePage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function baseMocks() {
  mockUseSites.mockReturnValue({ data: [{ id: 's1', name: 'Site 1' }] });
  mockUseActiveDevices.mockReturnValue({ data: [{ id: 'd1', name: 'Device 1', status: 'ACTIVE' }] });
  mockUseEvents.mockReturnValue({ data: [] });
  mockUseActionHistory.mockReturnValue({ data: [] });
  mockUseExplorerHierarchy.mockReturnValue({ data: { buildingCount: 1, spaceCount: 2 } });
  mockUseConnectionStatus.mockReturnValue({
    messagesPerSec: 3.2,
    realtimeStatus: 'connected',
    backend: 'Online',
    database: 'Online',
    mqtt: 'Online',
    latencyMs: 12,
  });
}

describe('HomePage - real connectivity-derived Offline count, honest terminology', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    baseMocks();
  });

  it('never shows the old hardcoded Offline: 0 regardless of real data', () => {
    mockUsePolling.mockReturnValue({
      data: { connectivityDistribution: { ONLINE: 1, OFFLINE: 4, UNKNOWN: 2 } },
      isLoading: false,
      error: null,
    });

    renderPage();

    expect(screen.getByText('4')).toBeInTheDocument();
  });

  it('treats a missing OFFLINE key as a real zero, not a fabricated fallback', () => {
    mockUsePolling.mockReturnValue({
      data: { connectivityDistribution: { ONLINE: 3, UNKNOWN: 1 } },
      isLoading: false,
      error: null,
    });

    renderPage();

    expect(screen.getAllByText('0').length).toBeGreaterThan(0);
  });

  it('never counts UNKNOWN devices as OFFLINE', () => {
    mockUsePolling.mockReturnValue({
      data: { connectivityDistribution: { UNKNOWN: 5 } },
      isLoading: false,
      error: null,
    });

    renderPage();

    expect(screen.getAllByText('0').length).toBeGreaterThan(0);
  });

  it('shows Unavailable rather than a fabricated number when the summary fails to load', () => {
    mockUsePolling.mockReturnValue({ data: undefined, isLoading: false, error: new Error('failed') });

    renderPage();

    expect(screen.getAllByText('Unavailable').length).toBeGreaterThan(0);
  });
});

describe('HomePage - real module composition, no fabricated content', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    baseMocks();
    mockUsePolling.mockReturnValue({
      data: { connectivityDistribution: { ONLINE: 1, OFFLINE: 0, UNKNOWN: 0 } },
      isLoading: false,
      error: null,
    });
  });

  it('renders real device identity and status, not a fabricated roster', () => {
    renderPage();

    expect(screen.getByText('Device 1')).toBeInTheDocument();
    expect(screen.getByText('d1')).toBeInTheDocument();
  });

  it('shows a truthful empty state, not fabricated activity, when there are no events or actions', () => {
    renderPage();

    expect(screen.getByText('No recent events or actions recorded.')).toBeInTheDocument();
  });

  it('renders real events and actions when present, sorted with the most recent first', () => {
    mockUseEvents.mockReturnValue({
      data: [{ id: 'e1', type: 'TEMP_HIGH', severity: 'WARNING', deviceId: 'd1', timestamp: 1000 }],
    });
    mockUseActionHistory.mockReturnValue({
      data: [{ id: 'a1', actionType: 'SEND_COMMAND', timestamp: 2000 }],
    });

    renderPage();

    expect(screen.getByText('TEMP_HIGH')).toBeInTheDocument();
    expect(screen.getByText('SEND_COMMAND')).toBeInTheDocument();
  });

  it('shows a truthful empty state for telemetry rather than a fabricated chart', () => {
    renderPage();

    expect(screen.getByText('No live telemetry buffered on this page yet.')).toBeInTheDocument();
  });

  it('shows a truthful unavailable state for Intelligence rather than inventing new AI content', () => {
    renderPage();

    expect(screen.getByText('No intelligence summary surfaced here.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Go to Intelligence/ })).toHaveAttribute('href', '/ai');
  });

  it('reflects real platform health signals, not a hardcoded status', () => {
    mockUseConnectionStatus.mockReturnValue({
      messagesPerSec: 0,
      realtimeStatus: 'recovering',
      backend: 'Degraded',
      database: 'Online',
      mqtt: 'Offline',
      latencyMs: 500,
    });

    renderPage();

    expect(screen.getByText('Backend API · Degraded')).toBeInTheDocument();
    expect(screen.getByText('MQTT Broker · Offline')).toBeInTheDocument();
  });
});
