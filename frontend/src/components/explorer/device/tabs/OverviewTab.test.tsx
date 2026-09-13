import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const mockUseDevice = vi.fn();
vi.mock('../../../../hooks/queries', () => ({
  useDevice: (...args: any[]) => mockUseDevice(...args),
}));

const mockScenarioEngine = {
  getEvents: vi.fn(() => []),
  subscribe: vi.fn(() => () => {}),
  emitEvent: vi.fn(),
  setScenario: vi.fn(),
};
vi.mock('../../../../simulation/ScenarioEngine', () => ({
  ScenarioEngine: mockScenarioEngine,
}));

const mockGetTelemetryHistory = vi.fn();
const mockGetEvents = vi.fn();
vi.mock('../../../../repositories/RepositoryProvider', () => ({
  useRepositories: () => ({
    telemetry: { getTelemetryHistory: mockGetTelemetryHistory },
    events: { getEvents: mockGetEvents },
  }),
}));

const mockGetDeviceConnectivity = vi.fn();
vi.mock('../../../../api/deviceApi', () => ({
  deviceApi: { getDeviceConnectivity: (...args: any[]) => mockGetDeviceConnectivity(...args) },
}));

vi.mock('../../../../utils/csv', () => ({
  downloadCsv: vi.fn(),
}));

function device(overrides: Record<string, any> = {}) {
  return {
    id: 'dev-1',
    name: 'Gas Sensor 1',
    status: 'ACTIVE',
    metrics: [],
    ...overrides,
  };
}

function renderWithQueryClient(ui: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

describe('OverviewTab - truthful status fields and real quick actions', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.resetModules();
    mockGetEvents.mockResolvedValue([]);
    mockGetDeviceConnectivity.mockResolvedValue({ deviceId: 'dev-1', status: 'UNKNOWN', lastChangedAt: null });
  });

  async function loadWithDemoMode(demoMode: boolean) {
    vi.doMock('../../../../config/features', () => ({
      FEATURES: { DEMO_MODE: demoMode },
    }));
    const { OverviewTab } = await import('./OverviewTab');
    return OverviewTab;
  }

  it('shows Unavailable for Latency/Twin Age/Uptime/Firmware/Last Reboot instead of fabricated values', async () => {
    mockUseDevice.mockReturnValue({ data: device(), isLoading: false, refetch: vi.fn() });
    const OverviewTab = await loadWithDemoMode(false);

    renderWithQueryClient(<OverviewTab deviceId="dev-1" />);

    expect(screen.getAllByText('ACTIVE').length).toBeGreaterThan(0);
    expect(screen.queryByText(/^\d+ ms$/)).not.toBeInTheDocument();
    expect(screen.queryByText('2.1 s')).not.toBeInTheDocument();
    expect(screen.queryByText('2d 14h 36m')).not.toBeInTheDocument();
    expect(screen.queryByText('v1.2.3')).not.toBeInTheDocument();
    expect(screen.queryByText('2026-08-01 08:13:22')).not.toBeInTheDocument();
    // 5 fields (Latency, Twin Age, Uptime, Firmware, Last Reboot) plus the
    // AI Intelligence panel's own "Unavailable" DataSourceBadge.
    expect(screen.getAllByText('Unavailable').length).toBe(6);
  });

  it('LIVE mode: Jump to Rules/Events/Actions and View Raw JSON navigate to the right tab', async () => {
    mockUseDevice.mockReturnValue({ data: device(), isLoading: false, refetch: vi.fn() });
    const OverviewTab = await loadWithDemoMode(false);
    const onNavigateToTab = vi.fn();

    renderWithQueryClient(<OverviewTab deviceId="dev-1" onNavigateToTab={onNavigateToTab} />);

    fireEvent.click(screen.getByText('Jump to Rules'));
    expect(onNavigateToTab).toHaveBeenLastCalledWith('rules');

    fireEvent.click(screen.getByText('Jump to Events'));
    expect(onNavigateToTab).toHaveBeenLastCalledWith('events');

    fireEvent.click(screen.getByText('Jump to Actions'));
    expect(onNavigateToTab).toHaveBeenLastCalledWith('actions');

    fireEvent.click(screen.getByText('View Raw JSON'));
    expect(onNavigateToTab).toHaveBeenLastCalledWith('json');

    fireEvent.click(screen.getByText('More'));
    expect(onNavigateToTab).toHaveBeenLastCalledWith('diagnostics');
  });

  it('LIVE mode: Refresh Twin calls the real refetch, never ScenarioEngine', async () => {
    const refetch = vi.fn().mockResolvedValue({ isSuccess: true });
    mockUseDevice.mockReturnValue({ data: device(), isLoading: false, refetch });
    const OverviewTab = await loadWithDemoMode(false);

    renderWithQueryClient(<OverviewTab deviceId="dev-1" />);
    fireEvent.click(screen.getByText('Refresh Twin'));

    await waitFor(() => expect(refetch).toHaveBeenCalled());
    expect(mockScenarioEngine.emitEvent).not.toHaveBeenCalled();
    await waitFor(() => expect(screen.getByTestId('quick-action-result')).toHaveTextContent('refreshed from the backend'));
  });

  it('LIVE mode: Download Telemetry fetches real history and triggers a real CSV download', async () => {
    mockUseDevice.mockReturnValue({ data: device(), isLoading: false, refetch: vi.fn() });
    mockGetTelemetryHistory.mockResolvedValue([
      { timestamp: '2026-01-01T00:00:00Z', payload: { value: 22.5 } },
    ]);
    const { downloadCsv } = await import('../../../../utils/csv');
    const OverviewTab = await loadWithDemoMode(false);

    renderWithQueryClient(<OverviewTab deviceId="dev-1" />);
    fireEvent.click(screen.getByText('Download Telemetry'));

    await waitFor(() => expect(downloadCsv).toHaveBeenCalled());
    expect(mockScenarioEngine.emitEvent).not.toHaveBeenCalled();
  });

  it('LIVE mode: Test Connection reads the real backend connectivity endpoint - never fabricates latency', async () => {
    mockUseDevice.mockReturnValue({ data: device(), isLoading: false, refetch: vi.fn() });
    mockGetDeviceConnectivity.mockResolvedValue({ deviceId: 'dev-1', status: 'ONLINE', lastChangedAt: '2026-01-01T00:00:00Z' });
    const OverviewTab = await loadWithDemoMode(false);

    renderWithQueryClient(<OverviewTab deviceId="dev-1" />);
    fireEvent.click(screen.getByText('Test Connection'));

    await waitFor(() => expect(mockGetDeviceConnectivity).toHaveBeenCalledWith('dev-1'));
    expect(mockScenarioEngine.emitEvent).not.toHaveBeenCalled();
    await waitFor(() => expect(screen.getByTestId('quick-action-result')).toHaveTextContent('ONLINE'));
    expect(screen.getByTestId('quick-action-result')).not.toHaveTextContent('Latency 24ms');
  });

  it('DEMO mode: Test Connection preserves the existing simulated diagnostic behavior', async () => {
    mockUseDevice.mockReturnValue({ data: device(), isLoading: false, refetch: vi.fn() });
    const OverviewTab = await loadWithDemoMode(true);

    renderWithQueryClient(<OverviewTab deviceId="dev-1" />);
    fireEvent.click(screen.getByText('Test Connection'));

    expect(mockScenarioEngine.emitEvent).toHaveBeenCalledWith('Diagnostic', 'Ping successful. Latency 24ms', 'dev-1');
    expect(mockGetDeviceConnectivity).not.toHaveBeenCalled();
  });

  it('no quick action button is ever disabled or shows a fabricated "unavailable" label', async () => {
    mockUseDevice.mockReturnValue({ data: device(), isLoading: false, refetch: vi.fn() });
    const OverviewTab = await loadWithDemoMode(false);

    renderWithQueryClient(<OverviewTab deviceId="dev-1" />);

    expect(screen.queryByText('Not available in live mode')).not.toBeInTheDocument();
    for (const label of ['Refresh Twin', 'Download Telemetry', 'Test Connection', 'View Raw JSON']) {
      expect(screen.getByText(label).closest('button')).not.toBeDisabled();
    }
  });
});
