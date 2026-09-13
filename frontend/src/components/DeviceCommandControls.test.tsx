import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { DeviceCommandControls } from './DeviceCommandControls';
import { deviceApi } from '../api/deviceApi';
import { ApiError } from '../api/apiClient';
import type { DeviceCapability } from '../api/deviceApi';

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

describe('DeviceCommandControls - truthful command dispatch UI', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders nothing when the device has no LED WRITE capability', () => {
    render(
      <DeviceCommandControls
        deviceId="dev-1"
        capabilities={[capability({ capabilityType: 'TEMPERATURE', direction: 'READ' })]}
      />
    );
    expect(screen.queryByTestId('device-command-controls')).not.toBeInTheDocument();
  });

  it('renders nothing when LED capability exists but is READ only', () => {
    render(
      <DeviceCommandControls
        deviceId="dev-1"
        capabilities={[capability({ capabilityType: 'LED', direction: 'READ' })]}
      />
    );
    expect(screen.queryByTestId('device-command-controls')).not.toBeInTheDocument();
  });

  it('shows "Command sent" on a truthful SUCCESS backend response - never a physical-outcome claim', async () => {
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

    render(<DeviceCommandControls deviceId="dev-1" capabilities={[capability()]} />);
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

    render(<DeviceCommandControls deviceId="dev-1" capabilities={[capability()]} />);
    fireEvent.click(screen.getByText('Turn LED OFF'));

    await waitFor(() => expect(screen.getByTestId('device-command-result')).toHaveTextContent('Device is offline'));
  });

  it('shows a truthful error message when the request itself fails (e.g. 404/400)', async () => {
    vi.mocked(deviceApi.sendDeviceCommand).mockRejectedValue(new ApiError(404, 'Device not found'));

    render(<DeviceCommandControls deviceId="dev-1" capabilities={[capability()]} />);
    fireEvent.click(screen.getByText('Turn LED ON'));

    await waitFor(() => expect(screen.getByTestId('device-command-result')).toHaveTextContent('Device not found'));
  });
});
