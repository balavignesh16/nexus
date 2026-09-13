import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { DeviceCommandControls } from './DeviceCommandControls';
import { deviceApi } from '../api/deviceApi';
import { ApiError } from '../api/apiClient';
import type { DeviceCapability } from '../api/deviceApi';
import { RealtimeContext, type RealtimeContextValue } from '../realtime/RealtimeContext';
import type { RealtimeMessage } from '../realtime/RealtimeEvents';

vi.mock('../api/deviceApi', async () => {
  const actual = await vi.importActual<typeof import('../api/deviceApi')>('../api/deviceApi');
  return {
    ...actual,
    deviceApi: {
      ...actual.deviceApi,
      sendDeviceCommand: vi.fn(),
    },
  };
});

function capability(overrides: Partial<DeviceCapability> = {}): DeviceCapability {
  return {
    id: 'cap-1',
    deviceId: 'dev-1',
    capabilityType: 'LED',
    direction: 'WRITE',
    ...overrides,
  };
}

// Tier 5E: DeviceCommandControls now subscribes to the realtime ack stream,
// which requires a RealtimeContext - a minimal stub provider is used here
// (no real STOMP connection). `emitActionAcknowledged` lets a test simulate
// the backend broadcasting an ActionAcknowledged event for the topic this
// component subscribed to.
function renderWithRealtime(ui: React.ReactElement) {
  let capturedHandler: ((msg: RealtimeMessage<unknown>) => void) | null = null;

  const contextValue: RealtimeContextValue = {
    connectionState: 'CONNECTED' as any,
    clientOffset: 0,
    messagesPerSec: 0,
    bytesPerSec: 0,
    droppedMessages: 0,
    reconnectAttempts: 0,
    activeSubscriptions: [],
    subscribe: (_topic, handler) => {
      capturedHandler = handler as (msg: RealtimeMessage<unknown>) => void;
      return () => {
        capturedHandler = null;
      };
    },
    replay: async () => {},
  };

  const result = render(
    <RealtimeContext.Provider value={contextValue}>{ui}</RealtimeContext.Provider>
  );

  const emitActionAcknowledged = (payload: {
    commandId: string;
    deviceId: string;
    status: 'ACCEPTED' | 'REJECTED';
    message: string;
    timestamp: number;
  }) => {
    if (!capturedHandler) {
      throw new Error('No realtime subscription captured yet');
    }
    (capturedHandler as (msg: RealtimeMessage<unknown>) => void)({
      eventId: 'evt-1',
      eventType: 'ActionAcknowledged',
      deviceId: payload.deviceId,
      correlationId: payload.commandId,
      sequenceNumber: 1,
      serverTimestamp: Date.now(),
      payload,
      source: 'BACKEND',
      version: 1,
    });
  };

  return { ...result, emitActionAcknowledged };
}

describe('DeviceCommandControls - truthful command dispatch UI', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders nothing when the device has no LED WRITE capability', () => {
    renderWithRealtime(
      <DeviceCommandControls
        deviceId="dev-1"
        capabilities={[capability({ capabilityType: 'TEMPERATURE', direction: 'READ' })]}
      />
    );
    expect(screen.queryByTestId('device-command-controls')).not.toBeInTheDocument();
  });

  it('renders nothing when LED capability exists but is READ only', () => {
    renderWithRealtime(
      <DeviceCommandControls
        deviceId="dev-1"
        capabilities={[capability({ capabilityType: 'LED', direction: 'READ' })]}
      />
    );
    expect(screen.queryByTestId('device-command-controls')).not.toBeInTheDocument();
  });

  it('shows "waiting for acknowledgement" on a truthful SUCCESS backend response - never a physical-outcome claim', async () => {
    vi.mocked(deviceApi.sendDeviceCommand).mockResolvedValue({
      executionId: 'exec-1',
      requestId: 'req-1',
      matchId: null,
      actionType: 'SEND_COMMAND',
      parameters: {},
      status: 'SUCCESS',
      timestamp: new Date().toISOString(),
      message: 'Command published',
    });

    renderWithRealtime(<DeviceCommandControls deviceId="dev-1" capabilities={[capability()]} />);
    fireEvent.click(screen.getByText('Turn LED ON'));

    await waitFor(() => expect(screen.getByTestId('device-command-result')).toHaveTextContent('Command sent'));
    expect(screen.getByTestId('device-command-result')).not.toHaveTextContent('LED is ON');
    expect(deviceApi.sendDeviceCommand).toHaveBeenCalledWith('dev-1', {
      capability: 'LED',
      command: 'SET_STATE',
      value: true,
    });
  });

  it('shows the truthful backend failure message when the device is offline - never fabricates success', async () => {
    vi.mocked(deviceApi.sendDeviceCommand).mockResolvedValue({
      executionId: 'exec-1',
      requestId: 'req-1',
      matchId: null,
      actionType: 'SEND_COMMAND',
      parameters: {},
      status: 'FAILURE',
      timestamp: new Date().toISOString(),
      message: 'Device is offline',
    });

    renderWithRealtime(<DeviceCommandControls deviceId="dev-1" capabilities={[capability()]} />);
    fireEvent.click(screen.getByText('Turn LED OFF'));

    await waitFor(() => expect(screen.getByTestId('device-command-result')).toHaveTextContent('Device is offline'));
  });

  it('shows a truthful error message when the request itself fails (e.g. 404/400)', async () => {
    vi.mocked(deviceApi.sendDeviceCommand).mockRejectedValue(new ApiError(404, 'Device not found'));

    renderWithRealtime(<DeviceCommandControls deviceId="dev-1" capabilities={[capability()]} />);
    fireEvent.click(screen.getByText('Turn LED ON'));

    await waitFor(() => expect(screen.getByTestId('device-command-result')).toHaveTextContent('Device not found'));
  });

  // ---------- Tier 5E: acknowledgement lifecycle ----------

  it('transitions to "Acknowledged" when an ACCEPTED ack arrives for the matching commandId', async () => {
    vi.mocked(deviceApi.sendDeviceCommand).mockResolvedValue({
      executionId: 'exec-1',
      requestId: 'cmd-123',
      matchId: null,
      actionType: 'SEND_COMMAND',
      parameters: {},
      status: 'SUCCESS',
      timestamp: new Date().toISOString(),
      message: 'Command published to MQTT',
    });

    const { emitActionAcknowledged } = renderWithRealtime(
      <DeviceCommandControls deviceId="dev-1" capabilities={[capability()]} />
    );
    fireEvent.click(screen.getByText('Turn LED ON'));

    await waitFor(() => expect(screen.getByTestId('device-command-result')).toHaveTextContent('waiting for acknowledgement'));

    emitActionAcknowledged({
      commandId: 'cmd-123',
      deviceId: 'dev-1',
      status: 'ACCEPTED',
      message: '',
      timestamp: Date.now() / 1000,
    });

    await waitFor(() => expect(screen.getByTestId('device-command-result')).toHaveTextContent('Acknowledged'));
    expect(screen.getByTestId('device-command-result')).not.toHaveTextContent('LED is ON');
  });

  it('shows the truthful rejection reason when a REJECTED ack arrives', async () => {
    vi.mocked(deviceApi.sendDeviceCommand).mockResolvedValue({
      executionId: 'exec-1',
      requestId: 'cmd-456',
      matchId: null,
      actionType: 'SEND_COMMAND',
      parameters: {},
      status: 'SUCCESS',
      timestamp: new Date().toISOString(),
      message: 'Command published to MQTT',
    });

    const { emitActionAcknowledged } = renderWithRealtime(
      <DeviceCommandControls deviceId="dev-1" capabilities={[capability()]} />
    );
    fireEvent.click(screen.getByText('Turn LED ON'));

    await waitFor(() => screen.getByTestId('device-command-result'));

    emitActionAcknowledged({
      commandId: 'cmd-456',
      deviceId: 'dev-1',
      status: 'REJECTED',
      message: 'STALE',
      timestamp: Date.now() / 1000,
    });

    await waitFor(() => expect(screen.getByTestId('device-command-result')).toHaveTextContent('Rejected: STALE'));
  });

  it('ignores an ack for a different commandId - never applies it to the currently pending command', async () => {
    vi.mocked(deviceApi.sendDeviceCommand).mockResolvedValue({
      executionId: 'exec-1',
      requestId: 'cmd-789',
      matchId: null,
      actionType: 'SEND_COMMAND',
      parameters: {},
      status: 'SUCCESS',
      timestamp: new Date().toISOString(),
      message: 'Command published to MQTT',
    });

    const { emitActionAcknowledged } = renderWithRealtime(
      <DeviceCommandControls deviceId="dev-1" capabilities={[capability()]} />
    );
    fireEvent.click(screen.getByText('Turn LED ON'));

    await waitFor(() => expect(screen.getByTestId('device-command-result')).toHaveTextContent('waiting for acknowledgement'));

    emitActionAcknowledged({
      commandId: 'some-other-command',
      deviceId: 'dev-1',
      status: 'ACCEPTED',
      message: '',
      timestamp: Date.now() / 1000,
    });

    expect(screen.getByTestId('device-command-result')).toHaveTextContent('waiting for acknowledgement');
  });
});
