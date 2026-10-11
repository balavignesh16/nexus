import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';

const mockInvalidateQueries = vi.fn();
vi.mock('@tanstack/react-query', async () => ({
  ...(await vi.importActual<typeof import('@tanstack/react-query')>('@tanstack/react-query')),
  useQueryClient: () => ({ invalidateQueries: mockInvalidateQueries }),
}));

const mockGetHistory = vi.fn();
vi.mock('../../../../api/twinApi', () => ({
  twinApi: { getHistory: (...args: any[]) => mockGetHistory(...args) },
}));

// DiffEditor (Monaco) cannot render in jsdom (needs canvas/workers) - stub it.
vi.mock('@monaco-editor/react', () => ({
  DiffEditor: ({ original, modified }: { original?: string; modified?: string }) => <div data-testid="diff-editor-stub">
    <pre data-testid="diff-original">{original}</pre><pre data-testid="diff-modified">{modified}</pre>
  </div>,
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
    vi.clearAllMocks();
    mockUseDeviceTwinState.mockReturnValue(emptyTwinState());
    mockGetHistory.mockResolvedValue({ items: [], nextCursor: null });
  });

  afterEach(() => vi.useRealTimers());

  it('keeps ACK, qualified readback, and stale last verification separate', () => {
    mockUseDevice.mockReturnValue({ data: { id: 'dev-1', name: 'Simulated actuator' } });
    mockUseLatestTwins.mockReturnValue({ data: [{ deviceId: 'dev-1', reportedState: {},
      desiredState: { 'binding:write/channel:2': { typedValue: { channel: 2, state: true },
        commandId: 'command-2', deliveryStatus: 'SUCCESS', ackStatus: 'ACCEPTED' } },
      verification: { 'binding:write/channel:2': { status: 'CONVERGED', readbackEvidence: 'COMMAND_ECHO',
        readbackValue: true, comparisonResult: 'MATCH', readbackStateQuality: { state: 'STALE' } } },
    }] });
    render(<DigitalTwinTab deviceId="dev-1" />);
    expect(screen.getByText('Desired intent · binding:write/channel:2')).toBeInTheDocument();
    expect(screen.getByText('MQTT publication: Published')).toBeInTheDocument();
    expect(screen.getByText('Device ACK: Device ACK accepted')).toBeInTheDocument();
    expect(screen.getByText('Qualified readback: COMMAND_ECHO')).toBeInTheDocument();
    expect(screen.getByText('Verification: Last verified converged · current readback stale')).toBeInTheDocument();
  });

  it.each([
    ['UNVERIFIED', /Readback unavailable.*unverified/],
    ['PENDING', /Awaiting qualified readback/],
    ['DIVERGED', /Observed state drifted from desired state/],
    ['TIMED_OUT', /Verification timed out/],
    ['CONVERGED', /Readback matched.*converged/],
  ])('keeps publication and ACK distinct from %s verification', (status, expected) => {
    mockUseDevice.mockReturnValue({ data: { id: 'dev-1', name: 'Actuator' } });
    mockUseLatestTwins.mockReturnValue({ data: [{ deviceId: 'dev-1', reportedState: {},
      desiredState: { 'binding:write/channel:2': { typedValue: { channel: 2, state: true },
        commandId: 'command-2', deliveryStatus: 'SUCCESS', ackStatus: 'ACCEPTED' } },
      verification: { 'binding:write/channel:2': { status } },
    }] });
    render(<DigitalTwinTab deviceId="dev-1" />);
    expect(screen.getByText(/Desired intent.*binding:write\/channel:2/)).toBeInTheDocument();
    expect(screen.getByText('MQTT publication: Published')).toBeInTheDocument();
    expect(screen.getByText('Device ACK: Device ACK accepted')).toBeInTheDocument();
    expect(screen.getByText(expected)).toBeInTheDocument();
    if (status !== 'CONVERGED') expect(screen.queryByText(/Verification:.*converged/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/physically verified/i)).not.toBeInTheDocument();
  });

  it.each([
    ['FAILURE', undefined, 'UNVERIFIED', 'FAILURE', 'No ACK yet', /unverified/],
    ['SUCCESS', 'REJECTED', 'UNVERIFIED', 'Published', 'Device ACK rejected', /unverified/],
    ['SUCCESS', undefined, 'CONVERGED', 'Published', 'No ACK yet', /converged/],
  ])('shows delivery %s and ACK %s independently of %s verification',
    (deliveryStatus, ackStatus, status, publication, ack, verification) => {
      mockUseDevice.mockReturnValue({ data: { id: 'dev-1', name: 'Actuator' } });
      mockUseLatestTwins.mockReturnValue({ data: [{ deviceId: 'dev-1', reportedState: {},
        desiredState: { 'binding:write': { typedValue: true, commandId: 'current-command', deliveryStatus, ackStatus } },
        verification: { 'binding:write': { status, desiredCommandId: 'current-command' } },
      }] });
      render(<DigitalTwinTab deviceId="dev-1" />);
      expect(screen.getByText(`MQTT publication: ${publication}`)).toBeInTheDocument();
      expect(screen.getByText(`Device ACK: ${ack}`)).toBeInTheDocument();
      expect(screen.getByText(verification)).toBeInTheDocument();
      expect(screen.queryByText(/physically verified/i)).not.toBeInTheDocument();
    });

  it.each(['STALE', 'OFFLINE', 'UNKNOWN'])('renders %s quality separately from persisted convergence', quality => {
    mockUseDevice.mockReturnValue({ data: { id: 'dev-1', name: 'Actuator' } });
    mockUseLatestTwins.mockReturnValue({ data: [{ deviceId: 'dev-1', reportedState: {},
      desiredState: { 'binding:write': { typedValue: true, commandId: 'current-command', deliveryStatus: 'SUCCESS' } },
      verification: { 'binding:write': { status: 'CONVERGED', desiredCommandId: 'current-command',
        readbackStateQuality: { state: quality } } },
    }] });
    render(<DigitalTwinTab deviceId="dev-1" />);
    expect(screen.getByText(new RegExp(`Verification: Last verified converged.*current readback ${quality.toLowerCase()}`))).toBeInTheDocument();
    expect(screen.getByText('Device ACK: No ACK yet')).toBeInTheDocument();
  });

  it.each([
    ['NO_DESIRED_STATE', 'No desired intent'],
    ['NOT_APPLICABLE', 'Verification not applicable'],
  ])('renders derived %s verification without fabricating desired intent', (status, label) => {
    mockUseDevice.mockReturnValue({ data: { id: 'dev-1', name: 'Actuator' } });
    mockUseLatestTwins.mockReturnValue({ data: [{ deviceId: 'dev-1', reportedState: {},
      desiredState: {}, verification: { 'binding:read': { status } },
    }] });
    render(<DigitalTwinTab deviceId="dev-1" />);
    expect(screen.getByText('State · binding:read')).toBeInTheDocument();
    expect(screen.getByText(`Verification: ${label}`)).toBeInTheDocument();
    expect(screen.queryByText(/^Desired intent ·/i)).not.toBeInTheDocument();
  });

  it.each([
    ['ON', 'OFF'],
    ['on', 'ON'],
  ])('renders exact typed desired/readback members %s and %s', (desiredValue, readbackValue) => {
    mockUseDevice.mockReturnValue({ data: { id: 'dev-1', name: 'Actuator' } });
    mockUseLatestTwins.mockReturnValue({ data: [{ deviceId: 'dev-1', reportedState: {},
      desiredState: { 'binding:write': { typedValue: desiredValue, commandId: 'current-command', deliveryStatus: 'SUCCESS' } },
      verification: { 'binding:write': { status: 'PENDING', readbackValue, comparisonResult: 'MISMATCH' } },
    }] });
    render(<DigitalTwinTab deviceId="dev-1" />);
    expect(screen.getByText(new RegExp(`Value: ${JSON.stringify(desiredValue)}.*Command: current-command`))).toBeInTheDocument();
    expect(screen.getByText(new RegExp(`Readback value: ${JSON.stringify(readbackValue)}.*MISMATCH`))).toBeInTheDocument();
    expect(screen.getByText('Verification: Awaiting qualified readback')).toBeInTheDocument();
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
        { deviceId: 'dev-1', reportedState: { TEMPERATURE: { value: 42, unit: 'CELSIUS', lastSeen: '2026-01-01T00:00:00Z', quality: 'GOOD' } } },
        { deviceId: 'dev-2', reportedState: { GAS: { value: 7, unit: 'PPM', lastSeen: '2026-01-01T00:00:00Z', quality: 'POOR' } } },
      ],
    });

    render(<DigitalTwinTab deviceId="dev-1" />);

    expect(screen.getByText('TEMPERATURE')).toBeInTheDocument();
    expect(screen.getByText('GOOD')).toBeInTheDocument();
    expect(screen.getByText('42')).toBeInTheDocument();
    expect(screen.queryByText('POOR')).not.toBeInTheDocument();
    expect(screen.queryByText('GAS')).not.toBeInTheDocument();
  });

  it('renders every capability in the aggregated device twin reportedState', () => {
    mockUseDevice.mockReturnValue({ data: { id: 'dev-1', name: 'ESP32-ENV-01' } });
    mockUseLatestTwins.mockReturnValue({
      data: [
        {
          deviceId: 'dev-1',
          reportedState: {
            TEMPERATURE: { value: 22.5, unit: 'CELSIUS', lastSeen: '2026-01-01T00:00:00Z', quality: 'GOOD' },
            HUMIDITY: { value: 55, unit: 'PERCENT', lastSeen: '2026-01-01T00:00:00Z', quality: 'GOOD' },
            PRESSURE: { value: 1013.9, unit: 'HPA', lastSeen: '2026-01-01T00:00:00Z', quality: 'GOOD' },
          },
        },
      ],
    });

    render(<DigitalTwinTab deviceId="dev-1" />);

    expect(screen.getByText('TEMPERATURE')).toBeInTheDocument();
    expect(screen.getByText('HUMIDITY')).toBeInTheDocument();
    expect(screen.getByText('PRESSURE')).toBeInTheDocument();
    expect(screen.getByText('1013.9')).toBeInTheDocument();
    expect(screen.queryByText('No twin data reported yet')).not.toBeInTheDocument();
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
      data: [{ deviceId: 'dev-1', reportedState: { TEMPERATURE: { value: 42, unit: 'CELSIUS', lastSeen: '2026-01-01T00:00:00Z', quality: 'GOOD' } } }],
    });

    render(<DigitalTwinTab deviceId="dev-1" />);

    expect(screen.queryByText('Sync')).not.toBeInTheDocument();
    expect(screen.queryByText('Connect')).not.toBeInTheDocument();
    expect(screen.queryByText('Reported state updated')).not.toBeInTheDocument();
  });

  it('never shows the dead Edit Twin / Save Changes mutation controls', () => {
    mockUseDevice.mockReturnValue({ data: { id: 'dev-1', name: 'Gas Sensor 1' } });
    mockUseLatestTwins.mockReturnValue({ data: [] });

    render(<DigitalTwinTab deviceId="dev-1" />);

    expect(screen.queryByText('Edit Twin')).not.toBeInTheDocument();
    expect(screen.queryByText('Save Changes')).not.toBeInTheDocument();
  });

  it.each(['GOOD', 'STALE', 'OFFLINE', 'UNKNOWN'])('renders backend-authoritative %s state quality', (quality) => {
    mockUseDevice.mockReturnValue({ data: { id: 'dev-1', name: 'Sensor' } });
    mockUseLatestTwins.mockReturnValue({ data: [{ deviceId: 'dev-1', reportedState: {
      TEMPERATURE: { value: 42, unit: 'C', quality: 'LEGACY', stateQuality: {
        state: quality, reason: `REASON_${quality}`, evaluatedAt: '2026-01-01T00:00:00Z',
        receivedAt: '2026-01-01T00:00:00Z', thresholdSeconds: 120, connectivity: 'ONLINE',
      } },
    } }] });
    render(<DigitalTwinTab deviceId="dev-1" />);
    expect(screen.getAllByText(quality).length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText(new RegExp(`REASON_${quality}`))).toBeInTheDocument();
  });

  it('uses one nearest-boundary timer to refetch authoritative Twin quality', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-01-01T00:00:00Z'));
    mockUseDevice.mockReturnValue({ data: { id: 'dev-1', name: 'Sensor' } });
    mockUseLatestTwins.mockReturnValue({ data: [{ deviceId: 'dev-1', reportedState: {
      A: { value: 1, quality: 'GOOD', stateQuality: { state: 'GOOD', reason: 'OK', receivedAt: '2026-01-01T00:00:00Z', thresholdSeconds: 10 } },
      B: { value: 2, quality: 'GOOD', stateQuality: { state: 'GOOD', reason: 'OK', receivedAt: '2026-01-01T00:00:00Z', thresholdSeconds: 20 } },
    } }] });
    render(<DigitalTwinTab deviceId="dev-1" />);
    expect(vi.getTimerCount()).toBe(1);
    act(() => vi.advanceTimersByTime(10_001));
    expect(mockInvalidateQueries).toHaveBeenCalledTimes(1);
    expect(mockInvalidateQueries).toHaveBeenCalledWith({ queryKey: ['twins', 100] });
  });

  it('loads durable typed history, distinguishes reported/desired, and appends the cursor page', async () => {
    const reported = { transitionId: 't1', transitionedAt: '2026-01-02T00:00:00Z', transitionType: 'REPORTED_STATE_CHANGED', source: 'TELEMETRY', previousValue: false, newValue: true, previousUnit: null, newUnit: null };
    const desired = { transitionId: 't2', transitionedAt: '2026-01-01T00:00:00Z', transitionType: 'DESIRED_STATE_CHANGED', source: 'MANUAL_COMMAND', previousValue: { channel: 1 }, newValue: { channel: 2 }, previousUnit: null, newUnit: null };
    mockGetHistory.mockResolvedValueOnce({ items: [reported], nextCursor: 'next' }).mockResolvedValueOnce({ items: [desired], nextCursor: null });
    mockUseDevice.mockReturnValue({ data: { id: 'dev-1', name: 'Sensor' } });
    mockUseLatestTwins.mockReturnValue({ data: [] });
    render(<DigitalTwinTab deviceId="dev-1" />);
    fireEvent.click(screen.getByText('Diffs'));
    await waitFor(() => expect(mockGetHistory).toHaveBeenCalledWith('dev-1', expect.objectContaining({ limit: 50 })));
    expect(await screen.findByText(/REPORTED_STATE_CHANGED/)).toBeInTheDocument();
    expect(screen.getByTestId('diff-original')).toHaveTextContent('false');
    expect(screen.getByTestId('diff-modified')).toHaveTextContent('true');
    fireEvent.click(screen.getByText('Load more'));
    await waitFor(() => expect(mockGetHistory).toHaveBeenLastCalledWith('dev-1', expect.objectContaining({ cursor: 'next' })));
    expect(await screen.findByText(/DESIRED_STATE_CHANGED/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('History transition'), { target: { value: '1' } });
    expect(screen.getByTestId('diff-original')).toHaveTextContent('"channel": 1');
    expect(screen.getByTestId('diff-modified')).toHaveTextContent('"channel": 2');
  });

  it('shows honest empty and API-error history states and resets the filter query', async () => {
    mockUseDevice.mockReturnValue({ data: { id: 'dev-1', name: 'Sensor' } });
    mockUseLatestTwins.mockReturnValue({ data: [] });
    render(<DigitalTwinTab deviceId="dev-1" />);
    fireEvent.click(screen.getByText('Diffs'));
    expect(await screen.findByText('No durable transitions')).toBeInTheDocument();
    mockGetHistory.mockRejectedValueOnce(new Error('history unavailable'));
    fireEvent.change(screen.getByLabelText('History transition type'), { target: { value: 'DESIRED_STATE_CHANGED' } });
    expect(await screen.findByRole('alert')).toHaveTextContent('history unavailable');
    expect(mockGetHistory).toHaveBeenLastCalledWith('dev-1', expect.objectContaining({ transitionType: 'DESIRED_STATE_CHANGED', cursor: undefined }));
  });
});
