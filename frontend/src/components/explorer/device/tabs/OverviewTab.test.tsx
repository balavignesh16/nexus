import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

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

function device(overrides: Record<string, any> = {}) {
  return {
    id: 'dev-1',
    name: 'Gas Sensor 1',
    status: 'ACTIVE',
    metrics: [],
    ...overrides,
  };
}

describe('OverviewTab - truthful status fields and live-mode quick actions', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.resetModules();
  });

  async function loadWithDemoMode(demoMode: boolean) {
    vi.doMock('../../../../config/features', () => ({
      FEATURES: { DEMO_MODE: demoMode },
    }));
    const { OverviewTab } = await import('./OverviewTab');
    return OverviewTab;
  }

  it('shows Unavailable for Latency/Twin Age/Uptime/Firmware/Last Reboot instead of fabricated values', async () => {
    mockUseDevice.mockReturnValue({ data: device(), isLoading: false });
    const OverviewTab = await loadWithDemoMode(false);

    render(<OverviewTab deviceId="dev-1" />);

    // Real field is preserved
    expect(screen.getByText('ACTIVE')).toBeInTheDocument();

    // No fabricated random/static values remain
    expect(screen.queryByText(/^\d+ ms$/)).not.toBeInTheDocument();
    expect(screen.queryByText('2.1 s')).not.toBeInTheDocument();
    expect(screen.queryByText('2d 14h 36m')).not.toBeInTheDocument();
    expect(screen.queryByText('v1.2.3')).not.toBeInTheDocument();
    expect(screen.queryByText('2026-08-01 08:13:22')).not.toBeInTheDocument();

    // Honestly marked unavailable instead (5 fields: Latency, Twin Age, Uptime, Firmware, Last Reboot)
    expect(screen.getAllByText('Unavailable').length).toBe(5);
  });

  it('LIVE mode: quick actions never call ScenarioEngine.emitEvent, even if clicked', async () => {
    mockUseDevice.mockReturnValue({ data: device(), isLoading: false });
    const OverviewTab = await loadWithDemoMode(false);

    render(<OverviewTab deviceId="dev-1" />);

    const testConnectionBtn = screen.getByText('Test Connection').closest('button')!;
    expect(testConnectionBtn).toBeDisabled();
    fireEvent.click(testConnectionBtn);

    const refreshTwinBtn = screen.getByText('Refresh Twin').closest('button')!;
    expect(refreshTwinBtn).toBeDisabled();

    const downloadBtn = screen.getByText('Download Telemetry').closest('button')!;
    expect(downloadBtn).toBeDisabled();

    expect(mockScenarioEngine.emitEvent).not.toHaveBeenCalled();
  });

  it('LIVE mode: unavailable quick actions are visually distinguished, not merely inert', async () => {
    mockUseDevice.mockReturnValue({ data: device(), isLoading: false });
    const OverviewTab = await loadWithDemoMode(false);

    render(<OverviewTab deviceId="dev-1" />);

    expect(screen.getAllByText('Not available in live mode').length).toBeGreaterThan(0);
  });

  it('DEMO mode: quick actions preserve existing ScenarioEngine behavior', async () => {
    mockUseDevice.mockReturnValue({ data: device(), isLoading: false });
    const OverviewTab = await loadWithDemoMode(true);

    render(<OverviewTab deviceId="dev-1" />);

    const testConnectionBtn = screen.getByText('Test Connection').closest('button')!;
    expect(testConnectionBtn).not.toBeDisabled();
    fireEvent.click(testConnectionBtn);

    expect(mockScenarioEngine.emitEvent).toHaveBeenCalledWith('Diagnostic', 'Ping successful. Latency 24ms', 'dev-1');
  });

  it('DEMO mode: does not show the "Not available in live mode" label', async () => {
    mockUseDevice.mockReturnValue({ data: device(), isLoading: false });
    const OverviewTab = await loadWithDemoMode(true);

    render(<OverviewTab deviceId="dev-1" />);

    expect(screen.queryByText('Not available in live mode')).not.toBeInTheDocument();
  });
});
