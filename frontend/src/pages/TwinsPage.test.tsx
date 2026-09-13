import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';

const mockGetDevices = vi.fn();
vi.mock('../repositories/RepositoryProvider', () => ({
  useRepositories: () => ({ device: { getDevices: mockGetDevices } }),
}));

const mockUsePolling = vi.fn();
vi.mock('../hooks/usePolling', () => ({
  usePolling: (...args: any[]) => mockUsePolling(...args),
}));

const mockOpenInspector = vi.fn();
vi.mock('../components/ui/InspectorProvider', () => ({
  useInspector: () => ({ openInspector: mockOpenInspector }),
}));

const mockUseDeviceTwinState = vi.fn();
vi.mock('../hooks/useDeviceTwinState', () => ({
  useDeviceTwinState: (...args: any[]) => mockUseDeviceTwinState(...args),
}));

import TwinsPage from './TwinsPage';

function device(overrides: Record<string, any> = {}) {
  return {
    id: 'dev-1',
    name: 'Gas Sensor 1',
    status: 'ACTIVE',
    deviceType: 'GAS_SENSOR',
    mqttTopic: 'sensors/floor-1/dev-1',
    ...overrides,
  };
}

function emptyTwinState() {
  return {
    connectivity: undefined,
    connectivityLoading: false,
    connectivityError: null,
    lastTelemetryAt: null,
    reportedFields: [],
    telemetryLoading: false,
    telemetryError: null,
  };
}

describe('TwinsPage - honest digital twin state, no fabrication', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUseDeviceTwinState.mockReturnValue(emptyTwinState());
  });

  it('never shows a fabricated 99.9% twin quality value', () => {
    mockUsePolling.mockReturnValue({ data: [device()], isLoading: false });

    render(<TwinsPage />);

    expect(screen.queryByText('99.9%')).not.toBeInTheDocument();
    expect(screen.queryByText('Twin Quality')).not.toBeInTheDocument();
    expect(screen.queryByText('Live Sync')).not.toBeInTheDocument();
  });

  it('renders real connectivity and last-telemetry age when available', () => {
    mockUsePolling.mockReturnValue({ data: [device()], isLoading: false });
    mockUseDeviceTwinState.mockReturnValue({
      ...emptyTwinState(),
      connectivity: { deviceId: 'dev-1', status: 'ONLINE', lastChangedAt: null },
      lastTelemetryAt: new Date(Date.now() - 30000).toISOString(),
      reportedFields: ['temperature', 'humidity'],
    });

    render(<TwinsPage />);

    expect(screen.getByText('ONLINE')).toBeInTheDocument();
    expect(screen.getByText('30s ago')).toBeInTheDocument();
    expect(screen.getByText(/Reporting 2 field\(s\)/)).toBeInTheDocument();
  });

  it('honestly shows Unavailable for a device with no telemetry ever received', () => {
    mockUsePolling.mockReturnValue({ data: [device()], isLoading: false });

    render(<TwinsPage />);

    expect(screen.getByText('Unavailable')).toBeInTheDocument();
    expect(screen.getByText('No telemetry fields reported yet')).toBeInTheDocument();
    expect(screen.getByText('UNKNOWN')).toBeInTheDocument();
  });

  it('opening a twin card passes only real, non-fabricated inspector properties', () => {
    mockUsePolling.mockReturnValue({ data: [device()], isLoading: false });
    mockUseDeviceTwinState.mockReturnValue({
      ...emptyTwinState(),
      connectivity: { deviceId: 'dev-1', status: 'ONLINE', lastChangedAt: null },
    });

    render(<TwinsPage />);
    screen.getByText('Gas Sensor 1').closest('div[class*="cursor-pointer"]')!.dispatchEvent(
      new MouseEvent('click', { bubbles: true })
    );

    expect(mockOpenInspector).toHaveBeenCalled();
    const item = mockOpenInspector.mock.calls[0][0];
    expect(item.properties.Firmware).toBeUndefined();
    expect(item.history).toBeUndefined();
  });

  it('shows an honest empty state when there are no devices', () => {
    mockUsePolling.mockReturnValue({ data: [], isLoading: false });

    render(<TwinsPage />);

    expect(screen.getByText('No devices to represent as a digital twin.')).toBeInTheDocument();
  });
});
