import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';

const mockGetDevices = vi.fn();
const mockGetTelemetryHistory = vi.fn();
vi.mock('../repositories/RepositoryProvider', () => ({
  useRepositories: () => ({
    device: { getDevices: mockGetDevices },
    telemetry: { getTelemetryHistory: mockGetTelemetryHistory },
  }),
}));

const mockUsePolling = vi.fn();
vi.mock('../hooks/usePolling', () => ({
  usePolling: (...args: any[]) => mockUsePolling(...args),
}));

const mockUseConnectionStatus = vi.fn();
vi.mock('../services/ConnectionManager', () => ({
  useConnectionStatus: () => mockUseConnectionStatus(),
}));

const mockDownloadCsv = vi.fn();
vi.mock('../utils/csv', () => ({
  downloadCsv: (...args: any[]) => mockDownloadCsv(...args),
}));

import TelemetryPage from './TelemetryPage';

function device(overrides: Record<string, any> = {}) {
  return { id: 'dev-1', name: 'Gas Sensor 1', ...overrides };
}

function reading(sensorValue: number, timestamp: number, sensorType = 'TEMPERATURE') {
  return { id: `t-${timestamp}`, deviceId: 'dev-1', timestamp, payload: { [sensorType]: sensorValue } };
}

/** Routes usePolling calls by query key: 'all-devices' -> devices, 'telemetry-page' -> telemetry. */
function mockPolling(devicesResult: any, telemetryResult: any) {
  mockUsePolling.mockImplementation((key: any) => {
    if (Array.isArray(key) && key[0] === 'all-devices') return devicesResult;
    if (Array.isArray(key) && key[0] === 'telemetry-page') return telemetryResult;
    return { data: undefined, isLoading: false };
  });
}

describe('TelemetryPage - real telemetry, no fabricated chart data', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUseConnectionStatus.mockReturnValue({ realtimeStatus: 'disconnected' });
  });

  it('renders real telemetry points on the chart and KPI cards', () => {
    mockPolling(
      { data: [device()], isLoading: false },
      { data: [reading(20, 1000), reading(40, 2000), reading(35, 3000)], isLoading: false }
    );

    render(<TelemetryPage />);

    expect(screen.getByText('20.0')).toBeInTheDocument(); // min
    expect(screen.getByText('40.0')).toBeInTheDocument(); // max
    expect(screen.getByText('35.0')).toBeInTheDocument(); // current
    expect(screen.getByText('31.7')).toBeInTheDocument(); // avg
  });

  it('shows an honest empty state - never a fabricated chart - when there is no telemetry', () => {
    mockPolling(
      { data: [device()], isLoading: false },
      { data: [], isLoading: false }
    );

    render(<TelemetryPage />);

    expect(screen.getByText('No telemetry data available for this metric.')).toBeInTheDocument();
    // KPI cards show an honest dash, never a fabricated 0/NaN.
    expect(screen.getAllByText('—').length).toBe(4);
  });

  it('shows a loading state before devices arrive', () => {
    mockPolling(
      { data: [], isLoading: true },
      { data: [], isLoading: true }
    );

    const { container } = render(<TelemetryPage />);
    expect(container.querySelector('[class*="animate"]')).toBeTruthy();
  });

  it('requests telemetry for the selected device via the exact unchanged query key/contract', () => {
    mockPolling(
      { data: [device({ id: 'dev-1' }), device({ id: 'dev-2', name: 'Motion Sensor 1' })], isLoading: false },
      { data: [], isLoading: false }
    );

    render(<TelemetryPage />);

    const telemetryCall = mockUsePolling.mock.calls.find((c) => c[0][0] === 'telemetry-page');
    expect(telemetryCall![0]).toEqual(['telemetry-page', 'dev-1', 'TEMPERATURE']);
  });

  it('switching the metric selector changes the filtered chart data', () => {
    mockPolling(
      { data: [device()], isLoading: false },
      { data: [reading(20, 1000, 'TEMPERATURE'), reading(55, 2000, 'HUMIDITY')], isLoading: false }
    );

    render(<TelemetryPage />);
    expect(screen.getAllByText('20.0').length).toBeGreaterThan(0);

    fireEvent.change(screen.getByDisplayValue('Temperature'), { target: { value: 'HUMIDITY' } });

    expect(screen.getAllByText('55.0').length).toBeGreaterThan(0);
    expect(screen.queryByText('20.0')).not.toBeInTheDocument();
  });

  it('the window selector only offers real bounds (10/25/50), never misleading large values', () => {
    mockPolling(
      { data: [device()], isLoading: false },
      { data: [], isLoading: false }
    );

    render(<TelemetryPage />);

    const windowSelect = screen.getByDisplayValue('Last 25');
    const options = Array.from(windowSelect.querySelectorAll('option')).map((o) => o.textContent);
    expect(options).toEqual(['Last 10', 'Last 25', 'Last 50']);
    expect(screen.queryByText(/Last 200|Last 500|Last 1000|Last 5000/)).not.toBeInTheDocument();
  });

  it('window selection limits how many of the real fetched points are displayed', () => {
    const points = Array.from({ length: 30 }, (_, i) => reading(i, 1000 + i * 1000));
    mockPolling(
      { data: [device()], isLoading: false },
      { data: points, isLoading: false }
    );

    render(<TelemetryPage />);
    expect(screen.getByText(/25 of 30 fetched point/)).toBeInTheDocument();

    fireEvent.change(screen.getByDisplayValue('Last 25'), { target: { value: '10' } });
    expect(screen.getByText(/10 of 30 fetched point/)).toBeInTheDocument();
  });

  it('shows the real "Live" status only when realtime is genuinely connected', () => {
    mockUseConnectionStatus.mockReturnValue({ realtimeStatus: 'connected' });
    mockPolling(
      { data: [device()], isLoading: false },
      { data: [], isLoading: false }
    );

    render(<TelemetryPage />);
    const { getByText } = within(screen.getByTestId('realtime-status'));
    expect(getByText('Live')).toBeInTheDocument();
  });

  it('shows an honest "Polling every 5s" status - never a fake Live claim - when realtime is not connected', () => {
    mockUseConnectionStatus.mockReturnValue({ realtimeStatus: 'disconnected' });
    mockPolling(
      { data: [device()], isLoading: false },
      { data: [], isLoading: false }
    );

    render(<TelemetryPage />);
    const status = within(screen.getByTestId('realtime-status'));
    expect(status.getByText('Polling every 5s')).toBeInTheDocument();
    expect(status.queryByText('Live')).not.toBeInTheDocument();
  });

  it('Export CSV is disabled with no telemetry, and exports real rows when data exists', () => {
    mockPolling(
      { data: [device()], isLoading: false },
      { data: [reading(20, 1000)], isLoading: false }
    );

    render(<TelemetryPage />);

    const exportButton = screen.getByText('Export CSV').closest('button');
    expect(exportButton).not.toBeDisabled();

    fireEvent.click(exportButton!);

    expect(mockDownloadCsv).toHaveBeenCalledWith(
      expect.stringContaining('telemetry-dev-1-TEMPERATURE-'),
      ['timestamp', 'sensorType', 'value'],
      [[new Date(1000).toISOString(), 'TEMPERATURE', '20']]
    );
  });

  it('renders real recent-reading rows in the table, no fake rows', () => {
    mockPolling(
      { data: [device()], isLoading: false },
      { data: [reading(20, 1000), reading(25, 2000)], isLoading: false }
    );

    render(<TelemetryPage />);

    expect(screen.getByText('20')).toBeInTheDocument();
    expect(screen.getByText('25')).toBeInTheDocument();
  });
});
