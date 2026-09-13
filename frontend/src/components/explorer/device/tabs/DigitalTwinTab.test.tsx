import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';

// DiffEditor (Monaco) cannot render in jsdom (needs canvas/workers) - stub it.
vi.mock('@monaco-editor/react', () => ({
  DiffEditor: () => <div data-testid="diff-editor-stub" />,
}));

// Control the two data hooks DigitalTwinTab depends on, without needing a
// real QueryClient/backend - this is the canonical query mechanism
// (useLatestTwins from hooks/queries.ts) that replaced useLiveUpdates.
const mockUseDevice = vi.fn();
const mockUseLatestTwins = vi.fn();
vi.mock('../../../../hooks/queries', () => ({
  useDevice: (...args: any[]) => mockUseDevice(...args),
  useLatestTwins: (...args: any[]) => mockUseLatestTwins(...args),
}));

const mockUseDeviceTwinState = vi.fn();
vi.mock('../../../../hooks/useDeviceTwinState', () => ({
  useDeviceTwinState: (...args: any[]) => mockUseDeviceTwinState(...args),
}));

import { DigitalTwinTab } from './DigitalTwinTab';

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

describe('DigitalTwinTab - migrated onto useLatestTwins(100)', () => {
  beforeEach(() => {
    mockUseDeviceTwinState.mockReturnValue(emptyTwinState());
  });

  it('requests twins via useLatestTwins(100), matching the old ["twins", 100] capacity', () => {
    mockUseDevice.mockReturnValue({ data: { id: 'dev-1', name: 'Gas Sensor 1' } });
    mockUseLatestTwins.mockReturnValue({ data: [] });

    render(<DigitalTwinTab deviceId="dev-1" />);

    expect(mockUseLatestTwins).toHaveBeenCalledWith(100);
  });

  it('renders the selected device twin found in the useLatestTwins(100) result', () => {
    mockUseDevice.mockReturnValue({ data: { id: 'dev-1', name: 'Gas Sensor 1' } });
    mockUseLatestTwins.mockReturnValue({
      data: [
        { deviceId: 'dev-1', quality: 'GOOD', value: 42 },
        { deviceId: 'dev-2', quality: 'POOR', value: 7 },
      ],
    });

    render(<DigitalTwinTab deviceId="dev-1" />);

    expect(screen.getByText('GOOD')).toBeInTheDocument();
    expect(screen.getByText('42')).toBeInTheDocument();
    expect(screen.queryByText('POOR')).not.toBeInTheDocument();
  });

  it('shows the empty state when useLatestTwins(100) has no entry for this device', () => {
    mockUseDevice.mockReturnValue({ data: { id: 'dev-1', name: 'Gas Sensor 1' } });
    mockUseLatestTwins.mockReturnValue({ data: [] });

    render(<DigitalTwinTab deviceId="dev-1" />);

    expect(screen.getByText('No twin data reported yet')).toBeInTheDocument();
  });

  it('renders a loading skeleton (not the twin content) while the device itself has not loaded', () => {
    mockUseDevice.mockReturnValue({ data: undefined });
    mockUseLatestTwins.mockReturnValue({ data: [] });

    render(<DigitalTwinTab deviceId="dev-1" />);

    expect(screen.queryByText('Digital Twin')).not.toBeInTheDocument();
  });

  it('never shows a fabricated 99.9% twin quality or hardcoded firmware value', () => {
    mockUseDevice.mockReturnValue({ data: { id: 'dev-1', name: 'Gas Sensor 1' } });
    mockUseLatestTwins.mockReturnValue({ data: [] });

    render(<DigitalTwinTab deviceId="dev-1" />);

    expect(screen.queryByText('99.9%')).not.toBeInTheDocument();
    expect(screen.queryByText('v1.0.0')).not.toBeInTheDocument();
    expect(screen.queryByText('Twin Quality')).not.toBeInTheDocument();
  });

  it('renders real connectivity and last-telemetry signals when available', () => {
    mockUseDevice.mockReturnValue({ data: { id: 'dev-1', name: 'Gas Sensor 1' } });
    mockUseLatestTwins.mockReturnValue({ data: [] });
    mockUseDeviceTwinState.mockReturnValue({
      ...emptyTwinState(),
      connectivity: { deviceId: 'dev-1', status: 'ONLINE', lastChangedAt: '2026-01-01T00:00:00Z' },
      lastTelemetryAt: new Date(Date.now() - 5000).toISOString(),
      reportedFields: ['temperature', 'humidity'],
    });

    render(<DigitalTwinTab deviceId="dev-1" />);

    expect(screen.getByText('ONLINE')).toBeInTheDocument();
    expect(screen.getByText('5s ago')).toBeInTheDocument();
    expect(screen.getByText('temperature, humidity')).toBeInTheDocument();
  });

  it('honestly shows Unavailable when no real telemetry has ever been received - never a fabricated value', () => {
    mockUseDevice.mockReturnValue({ data: { id: 'dev-1', name: 'Gas Sensor 1' } });
    mockUseLatestTwins.mockReturnValue({ data: [] });
    mockUseDeviceTwinState.mockReturnValue(emptyTwinState());

    render(<DigitalTwinTab deviceId="dev-1" />);

    expect(screen.getByText('Unavailable')).toBeInTheDocument();
    expect(screen.getByText('None reported')).toBeInTheDocument();
    expect(screen.getByText('UNKNOWN')).toBeInTheDocument();
  });

  it('no synthetic twin history is generated', () => {
    mockUseDevice.mockReturnValue({ data: { id: 'dev-1', name: 'Gas Sensor 1' } });
    mockUseLatestTwins.mockReturnValue({
      data: [{ deviceId: 'dev-1', quality: 'GOOD', value: 42 }],
    });

    render(<DigitalTwinTab deviceId="dev-1" />);

    expect(screen.queryByText('Sync')).not.toBeInTheDocument();
    expect(screen.queryByText('Connect')).not.toBeInTheDocument();
    expect(screen.queryByText('Reported state updated')).not.toBeInTheDocument();
  });
});
