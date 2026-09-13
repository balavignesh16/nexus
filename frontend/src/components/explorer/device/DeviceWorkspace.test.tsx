import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';

const mockUseDevice = vi.fn();
vi.mock('../../../hooks/queries', () => ({
  useDevice: (...args: any[]) => mockUseDevice(...args),
}));

const mockUseDeviceLocationPath = vi.fn();
vi.mock('../../../hooks/useDeviceLocationPath', () => ({
  useDeviceLocationPath: (...args: any[]) => mockUseDeviceLocationPath(...args),
}));

const mockUseDeviceTwinState = vi.fn();
vi.mock('../../../hooks/useDeviceTwinState', () => ({
  useDeviceTwinState: (...args: any[]) => mockUseDeviceTwinState(...args),
}));

vi.mock('../../../realtime/streams/useDeviceConnectivityStream', () => ({
  useDeviceConnectivityStream: () => undefined,
}));

vi.mock('../../DeviceCapabilitiesList', () => ({ DeviceCapabilitiesList: () => null }));
vi.mock('../../DeviceCommandControls', () => ({ DeviceCommandControls: () => null }));

vi.mock('./tabs/OverviewTab', () => ({ OverviewTab: () => <div data-testid="overview-tab" /> }));
vi.mock('./tabs/TelemetryTab', () => ({ TelemetryTab: () => null }));
vi.mock('./tabs/DigitalTwinTab', () => ({ DigitalTwinTab: () => null }));
vi.mock('./tabs/EventsTab', () => ({ EventsTab: () => null }));
vi.mock('./tabs/RulesTab', () => ({ RulesTab: () => null }));
vi.mock('./tabs/ActionsTab', () => ({ ActionsTab: () => null }));
vi.mock('./tabs/DiagnosticsTab', () => ({ DiagnosticsTab: () => null }));
vi.mock('./tabs/RawJsonTab', () => ({ RawJsonTab: () => null }));

import { DeviceWorkspace } from './DeviceWorkspace';

function device(overrides: Record<string, any> = {}) {
  return {
    id: 'dev-1',
    name: 'Gas Sensor 1',
    status: 'ACTIVE',
    deviceType: 'GAS_SENSOR',
    mqttTopic: 'sensors/floor-1/dev-1',
    lastSeen: 'Never',
    signalStrength: null,
    capabilities: [],
    spaceId: 'space-1',
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

describe('DeviceWorkspace - real hierarchy breadcrumb, no fabricated Health/Twin Quality', () => {
  beforeEach(() => {
    mockUseDeviceTwinState.mockReturnValue(emptyTwinState());
  });

  it('renders the real Site > Building > Space path when resolved', () => {
    mockUseDevice.mockReturnValue({ data: device(), isLoading: false, error: null });
    mockUseDeviceLocationPath.mockReturnValue({
      data: { site: 'North Campus', building: 'Plant 2', space: 'Cold Storage' },
      isLoading: false,
    });

    render(<DeviceWorkspace deviceId="dev-1" />);

    expect(screen.getByText('North Campus')).toBeInTheDocument();
    expect(screen.getByText('Plant 2')).toBeInTheDocument();
    expect(screen.getByText('Cold Storage')).toBeInTheDocument();
    expect(screen.queryByText('Test Site')).not.toBeInTheDocument();
    expect(screen.queryByText('Main Building')).not.toBeInTheDocument();
    expect(screen.queryByText('Floor 1')).not.toBeInTheDocument();
  });

  it('shows "Location unavailable" honestly instead of a placeholder location when hierarchy cannot be resolved', () => {
    mockUseDevice.mockReturnValue({ data: device({ spaceId: undefined }), isLoading: false, error: null });
    mockUseDeviceLocationPath.mockReturnValue({ data: undefined, isLoading: false });

    render(<DeviceWorkspace deviceId="dev-1" />);

    expect(screen.getByTestId('breadcrumb-unavailable')).toHaveTextContent('Location unavailable');
    expect(screen.queryByText('Test Site')).not.toBeInTheDocument();
  });

  it('never shows a fabricated Health Score or Twin Quality gauge', () => {
    mockUseDevice.mockReturnValue({ data: device(), isLoading: false, error: null });
    mockUseDeviceLocationPath.mockReturnValue({ data: undefined, isLoading: false });

    render(<DeviceWorkspace deviceId="dev-1" />);

    expect(screen.queryByText('Health Score')).not.toBeInTheDocument();
    expect(screen.queryByText('Twin Quality')).not.toBeInTheDocument();
    expect(screen.queryByText('Excellent')).not.toBeInTheDocument();
    expect(screen.queryByText('Poor')).not.toBeInTheDocument();
  });

  it('renders real last-telemetry age in the hero when available', () => {
    mockUseDevice.mockReturnValue({ data: device(), isLoading: false, error: null });
    mockUseDeviceLocationPath.mockReturnValue({ data: undefined, isLoading: false });
    mockUseDeviceTwinState.mockReturnValue({
      ...emptyTwinState(),
      lastTelemetryAt: new Date(Date.now() - 12000).toISOString(),
      reportedFields: ['temperature'],
    });

    render(<DeviceWorkspace deviceId="dev-1" />);

    expect(screen.getByText('12s ago')).toBeInTheDocument();
    expect(screen.getByText('1 field(s) reporting')).toBeInTheDocument();
  });
});
