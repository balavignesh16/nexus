import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

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
    capabilities: [],
    ...overrides,
  };
}

function capability(overrides: Record<string, any> = {}) {
  return {
    id: 'cap-1',
    deviceId: 'dev-1',
    capabilityType: 'LED',
    direction: 'WRITE',
    ...overrides,
  };
}

function emptyTwinState(overrides: Record<string, any> = {}) {
  return {
    connectivity: undefined,
    connectivityLoading: false,
    connectivityError: null,
    lastTelemetryAt: null,
    reportedFields: [],
    telemetryLoading: false,
    telemetryError: null,
    ...overrides,
  };
}

function openFirstCard() {
  screen.getByText('Gas Sensor 1').closest('div[class*="cursor-pointer"]')!.dispatchEvent(
    new MouseEvent('click', { bubbles: true })
  );
}

describe('TwinsPage - honest digital twin state, no fabrication', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockUseDeviceTwinState.mockReturnValue(emptyTwinState());
  });

  it('renders the real device identity', () => {
    mockUsePolling.mockReturnValue({ data: [device()], isLoading: false });

    render(<TwinsPage />);

    expect(screen.getByText('Gas Sensor 1')).toBeInTheDocument();
    expect(screen.getByText('dev-1')).toBeInTheDocument();
  });

  it.each(['REGISTERED', 'ACTIVE', 'OFFLINE', 'MAINTENANCE'])(
    'renders the real lifecycle status %s honestly, never collapsed',
    (status) => {
      mockUsePolling.mockReturnValue({ data: [device({ status })], isLoading: false });

      render(<TwinsPage />);

      expect(screen.getAllByText(status).length).toBeGreaterThan(0);
    }
  );

  it('never collapses MAINTENANCE or REGISTERED into a fabricated Active/Offline label', () => {
    mockUsePolling.mockReturnValue({
      data: [device({ id: 'dev-1', status: 'MAINTENANCE' }), device({ id: 'dev-2', name: 'Reg Sensor', status: 'REGISTERED' })],
      isLoading: false,
    });

    render(<TwinsPage />);

    expect(screen.queryByText('Active')).not.toBeInTheDocument();
    expect(screen.queryByText('Offline')).not.toBeInTheDocument();
  });

  it('renders real connectivity and last-telemetry age when available', () => {
    mockUsePolling.mockReturnValue({ data: [device()], isLoading: false });
    mockUseDeviceTwinState.mockReturnValue(emptyTwinState({
      connectivity: { deviceId: 'dev-1', status: 'ONLINE', lastChangedAt: null },
      lastTelemetryAt: new Date(Date.now() - 30000).toISOString(),
      reportedFields: ['temperature', 'humidity'],
    }));

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

  it('renders real capabilities from the already-fetched device response', () => {
    mockUsePolling.mockReturnValue({
      data: [device({ capabilities: [capability({ capabilityType: 'LED', direction: 'WRITE' }), capability({ id: 'cap-2', capabilityType: 'TEMPERATURE', direction: 'READ', unit: 'CELSIUS' })] })],
      isLoading: false,
    });

    render(<TwinsPage />);

    expect(screen.getByText('LED · WRITE')).toBeInTheDocument();
    expect(screen.getByText('TEMPERATURE · READ · CELSIUS')).toBeInTheDocument();
  });

  it('shows an honest "No capabilities reported" state when capabilities are empty', () => {
    mockUsePolling.mockReturnValue({ data: [device({ capabilities: [] })], isLoading: false });

    render(<TwinsPage />);

    expect(screen.getByText('No capabilities reported')).toBeInTheDocument();
  });

  it('never fetches capabilities separately per card - only uses the already-fetched device.capabilities', () => {
    mockUsePolling.mockReturnValue({ data: [device({ capabilities: [capability()] })], isLoading: false });

    render(<TwinsPage />);

    // Only the page-level ['all-devices'] query and per-card useDeviceTwinState
    // exist; no additional capability-fetching call is made anywhere.
    expect(mockUsePolling).toHaveBeenCalledTimes(1);
  });

  it('search filters by real device name', () => {
    mockUsePolling.mockReturnValue({
      data: [device({ id: 'dev-1', name: 'Gas Sensor 1' }), device({ id: 'dev-2', name: 'Motion Sensor 1' })],
      isLoading: false,
    });

    render(<TwinsPage />);
    fireEvent.change(screen.getByPlaceholderText('Search twins...'), { target: { value: 'Motion' } });

    expect(screen.getByText('Motion Sensor 1')).toBeInTheDocument();
    expect(screen.queryByText('Gas Sensor 1')).not.toBeInTheDocument();
  });

  it('search filters by real device ID', () => {
    mockUsePolling.mockReturnValue({
      data: [device({ id: 'dev-abc', name: 'Gas Sensor 1' }), device({ id: 'dev-xyz', name: 'Motion Sensor 1' })],
      isLoading: false,
    });

    render(<TwinsPage />);
    fireEvent.change(screen.getByPlaceholderText('Search twins...'), { target: { value: 'xyz' } });

    expect(screen.getByText('Motion Sensor 1')).toBeInTheDocument();
    expect(screen.queryByText('Gas Sensor 1')).not.toBeInTheDocument();
  });

  it('shows an honest no-results state when the search matches nothing', () => {
    mockUsePolling.mockReturnValue({ data: [device()], isLoading: false });

    render(<TwinsPage />);
    fireEvent.change(screen.getByPlaceholderText('Search twins...'), { target: { value: 'nonexistent-device-xyz' } });

    expect(screen.getByText('No twins match your search.')).toBeInTheDocument();
  });

  it('clearing the search restores the full real device list', () => {
    mockUsePolling.mockReturnValue({
      data: [device({ id: 'dev-1', name: 'Gas Sensor 1' }), device({ id: 'dev-2', name: 'Motion Sensor 1' })],
      isLoading: false,
    });

    render(<TwinsPage />);
    const input = screen.getByPlaceholderText('Search twins...');
    fireEvent.change(input, { target: { value: 'Motion' } });
    expect(screen.queryByText('Gas Sensor 1')).not.toBeInTheDocument();

    fireEvent.change(input, { target: { value: '' } });
    expect(screen.getByText('Gas Sensor 1')).toBeInTheDocument();
    expect(screen.getByText('Motion Sensor 1')).toBeInTheDocument();
  });

  it('never shows the dead "Filter" control', () => {
    mockUsePolling.mockReturnValue({ data: [device()], isLoading: false });

    render(<TwinsPage />);

    expect(screen.queryByLabelText(/filter/i)).not.toBeInTheDocument();
  });

  it('never shows a fabricated 99.9% twin quality value', () => {
    mockUsePolling.mockReturnValue({ data: [device()], isLoading: false });

    render(<TwinsPage />);

    expect(screen.queryByText('99.9%')).not.toBeInTheDocument();
    expect(screen.queryByText('Twin Quality')).not.toBeInTheDocument();
    expect(screen.queryByText('Live Sync')).not.toBeInTheDocument();
  });

  it('opening a twin card passes only real, non-fabricated inspector properties, and never a Topic field', () => {
    mockUsePolling.mockReturnValue({ data: [device()], isLoading: false });
    mockUseDeviceTwinState.mockReturnValue(emptyTwinState({
      connectivity: { deviceId: 'dev-1', status: 'ONLINE', lastChangedAt: null },
    }));

    render(<TwinsPage />);
    openFirstCard();

    expect(mockOpenInspector).toHaveBeenCalled();
    const item = mockOpenInspector.mock.calls[0][0];
    expect(item.properties.Topic).toBeUndefined();
    expect(item.properties.Firmware).toBeUndefined();
    expect(item.history).toBeUndefined();
    expect(JSON.stringify(item.properties)).not.toContain('undefined');
    expect(item.properties['Lifecycle Status']).toBe('ACTIVE');
    expect(item.properties['Connectivity']).toBe('ONLINE');
  });

  it('the inspector never receives a Topic property regardless of device shape (regression for the fixed mqttTopic bug)', () => {
    mockUsePolling.mockReturnValue({ data: [device({ mqttTopic: 'sensors/floor-1/dev-1' } as any)], isLoading: false });

    render(<TwinsPage />);
    openFirstCard();

    const item = mockOpenInspector.mock.calls[0][0];
    expect('Topic' in item.properties).toBe(false);
  });

  it('shows an honest empty state when there are no devices', () => {
    mockUsePolling.mockReturnValue({ data: [], isLoading: false });

    render(<TwinsPage />);

    expect(screen.getByText('No devices to represent as a digital twin.')).toBeInTheDocument();
  });

  it('shows a loading state before devices arrive', () => {
    mockUsePolling.mockReturnValue({ data: [], isLoading: true });

    const { container } = render(<TwinsPage />);

    expect(container.querySelector('[class*="animate"]')).toBeTruthy();
  });

  it('shows an honest error state when the device fetch fails', () => {
    mockUsePolling.mockReturnValue({ data: [], isLoading: false, error: new Error('Failed to fetch devices') });

    render(<TwinsPage />);

    expect(screen.getByText('Could not load devices.')).toBeInTheDocument();
  });
});
