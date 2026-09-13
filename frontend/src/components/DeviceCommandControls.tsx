import React, { useState } from 'react';
import { deviceApi, CapabilityType, ExecutionStatus, type DeviceCapability } from '../api/deviceApi';
import { ApiError } from '../api/apiClient';

interface DeviceCommandControlsProps {
  deviceId: string;
  capabilities: DeviceCapability[];
}

type DispatchState = 'idle' | 'sending' | 'sent' | 'failed';

/**
 * Tier 5D: the first manual physical-command trigger in NEXUS. Renders a
 * control ONLY for a capability the device is actually, authoritatively
 * provisioned with WRITE for (never inferred, never assumed).
 *
 * On a successful backend publish this shows "Command sent" - never "LED
 * is ON/OFF" and never "Command delivered". No acknowledgement or physical
 * outcome exists yet (deferred to a later phase); this control can only
 * ever report that NEXUS attempted and published the request.
 */
export const DeviceCommandControls: React.FC<DeviceCommandControlsProps> = ({ deviceId, capabilities }) => {
  const hasLedWrite = capabilities.some(
    (c) => c.capabilityType === CapabilityType.LED && c.direction === 'WRITE'
  );

  const [state, setState] = useState<DispatchState>('idle');
  const [message, setMessage] = useState<string | null>(null);

  if (!hasLedWrite) {
    return null;
  }

  const sendCommand = async (value: boolean) => {
    setState('sending');
    setMessage(null);
    try {
      const result = await deviceApi.sendDeviceCommand(deviceId, {
        capability: CapabilityType.LED,
        command: 'SET_STATE',
        value,
      });
      if (result.status === ExecutionStatus.SUCCESS) {
        setState('sent');
        setMessage('Command sent');
      } else {
        setState('failed');
        setMessage(result.message);
      }
    } catch (err) {
      setState('failed');
      setMessage(err instanceof ApiError ? err.message : 'Command failed');
    }
  };

  return (
    <div className="flex flex-col gap-2" data-testid="device-command-controls">
      <div className="flex items-center gap-2">
        <button
          type="button"
          onClick={() => sendCommand(true)}
          disabled={state === 'sending'}
          className="px-3 py-1.5 text-xs font-medium rounded bg-primary/10 text-primary hover:bg-primary/20 disabled:opacity-50"
        >
          Turn LED ON
        </button>
        <button
          type="button"
          onClick={() => sendCommand(false)}
          disabled={state === 'sending'}
          className="px-3 py-1.5 text-xs font-medium rounded bg-muted/50 text-muted-foreground hover:bg-muted disabled:opacity-50"
        >
          Turn LED OFF
        </button>
      </div>
      {message && (
        <span
          data-testid="device-command-result"
          className={`text-xs ${state === 'failed' ? 'text-destructive' : 'text-muted-foreground'}`}
        >
          {message}
        </span>
      )}
    </div>
  );
};
