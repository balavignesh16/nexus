import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

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

vi.mock('../components/widgets/PlatformHealthWidget', () => ({ PlatformHealthWidget: () => null }));
vi.mock('../components/ui/LiveAreaChart', () => ({ LiveAreaChart: () => null }));
vi.mock('../components/ui/ActivityTimeline', () => ({ ActivityTimeline: () => null }));

import HomePage from './HomePage';

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <HomePage />
    </QueryClientProvider>
  );
}

function baseMocks() {
  mockUseSites.mockReturnValue({ data: [{ id: 's1', name: 'Site 1' }] });
  mockUseActiveDevices.mockReturnValue({ data: [{ id: 'd1', name: 'Device 1', status: 'ACTIVE' }] });
  mockUseEvents.mockReturnValue({ data: [] });
  mockUseActionHistory.mockReturnValue({ data: [] });
  mockUseExplorerHierarchy.mockReturnValue({ data: { buildingCount: 1, spaceCount: 2 } });
  mockUseConnectionStatus.mockReturnValue({ messagesPerSec: 3.2, realtimeStatus: 'connected' });
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

    expect(screen.getByText('0')).toBeInTheDocument();
  });

  it('never counts UNKNOWN devices as OFFLINE', () => {
    mockUsePolling.mockReturnValue({
      data: { connectivityDistribution: { UNKNOWN: 5 } },
      isLoading: false,
      error: null,
    });

    renderPage();

    expect(screen.getByText('0')).toBeInTheDocument();
  });

  it('shows Unavailable rather than a fabricated number when the summary fails to load', () => {
    mockUsePolling.mockReturnValue({ data: undefined, isLoading: false, error: new Error('failed') });

    renderPage();

    expect(screen.getAllByText('Unavailable').length).toBeGreaterThan(0);
  });

  it('uses the honest "Devices" heading instead of "Active Digital Twins"', () => {
    mockUsePolling.mockReturnValue({ data: { connectivityDistribution: {} }, isLoading: false, error: null });

    renderPage();

    expect(screen.getByText('Devices')).toBeInTheDocument();
    expect(screen.queryByText('Active Digital Twins')).not.toBeInTheDocument();
  });
});
