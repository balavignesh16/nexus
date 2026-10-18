import React, { useEffect, useRef, useState } from 'react';
import { deviceApi, CapabilityType, ExecutionStatus, type DeviceCapability } from '../api/deviceApi';
import { ApiError } from '../api/apiClient';
import { useActionAcknowledgementStream } from '../realtime/streams/useActionAcknowledgementStream';
import { Button } from './foundation/Button';
import { queueFullMessage } from '../utils/commandLifecycle';

interface DeviceCommandControlsProps {
  deviceId: string;
  capabilities: DeviceCapability[];
}

// Tier 5E: UI-only lifecycle. 'sent' means only "NEXUS published the
// command to MQTT" - it then waits for a device ACK. No state here is ever
// persisted to the backend, and a timeout never claims the command or the
// device failed (see 'ack-timeout' below).
type DispatchState = 'idle' | 'sending' | 'sent' | 'queued' | 'waiting-ack' | 'acknowledged' | 'rejected' | 'ack-timeout' | 'failed' | 'refused';

// UI-only - not backend-persisted, not a scheduler. If an ACK arrives after
// this fires, the realtime handler below still updates the UI to the real
// state; this only changes the displayed text while nothing has arrived yet.
const ACK_TIMEOUT_MS = 15000;

/**
 * Tier 5D/5E: the manual physical-command trigger in NEXUS. Renders a
 * control ONLY for a capability the device is actually, authoritatively
 * provisioned with WRITE for (never inferred, never assumed).
 *
 * Lifecycle: "Command sent" (NEXUS published to MQTT) -> "Waiting for
 * acknowledgement" -> "Acknowledged" (ACCEPTED) or "Rejected: <reason>",
 * or "No acknowledgement received yet" after a UI-only timeout. None of
 * these ever claim a physical outcome (e.g. "LED is ON/OFF") - ACCEPTED
 * means only that the device processed and applied the instruction.
 */
export const DeviceCommandControls: React.FC<DeviceCommandControlsProps> = ({ deviceId, capabilities }) => {
  const hasLedWrite = capabilities.some(
    (c) => c.capabilityType === CapabilityType.LED && c.direction === 'WRITE'
  );

  const [state, setState] = useState<DispatchState>('idle');
  const [message, setMessage] = useState<string | null>(null);
  const pendingCommandIdRef = useRef<string | null>(null);
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    return () => {
      if (timeoutRef.current) {
        clearTimeout(timeoutRef.current);
      }
    };
  }, []);

  useActionAcknowledgementStream(deviceId, (payload) => {
    if (payload.commandId !== pendingCommandIdRef.current) {
      return;
    }
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current);
      timeoutRef.current = null;
    }
    if (payload.status === 'ACCEPTED') {
      setState('acknowledged');
      setMessage('Acknowledged');
    } else {
      setState('rejected');
      setMessage(`Rejected: ${payload.message || 'unknown reason'}`);
    }
  });

  if (!hasLedWrite) {
    return null;
  }

  const sendCommand = async (value: boolean) => {
    setState('sending');
    setMessage(null);
    pendingCommandIdRef.current = null;
    if (timeoutRef.current) {
      clearTimeout(timeoutRef.current);
      timeoutRef.current = null;
    }
    try {
      const result = await deviceApi.sendDeviceCommand(deviceId, {
        capability: CapabilityType.LED,
        command: 'SET_STATE',
        value,
      });
      if (result.status === ExecutionStatus.SUCCESS) {
        setState('waiting-ack');
        setMessage('Command sent - waiting for acknowledgement');
        pendingCommandIdRef.current = result.requestId;
        const dispatchedCommandId = result.requestId;
        timeoutRef.current = setTimeout(() => {
          // Only downgrade the display if this exact command is still the
          // one being waited on and no ACK has arrived for it yet - an ACK
          // that arrives afterward still updates the UI via the handler
          // above (pendingCommandIdRef is untouched by this branch).
          if (pendingCommandIdRef.current === dispatchedCommandId) {
            setState((current) => (current === 'waiting-ack' ? 'ack-timeout' : current));
            setMessage('No acknowledgement received yet');
          }
        }, ACK_TIMEOUT_MS);
      } else if (result.status === ExecutionStatus.ACCEPTED_FOR_DISPATCH) {
        // Outbox mode: NEXUS durably accepted the command and will publish it; it is NOT yet published and NOT failed. An ACK still
        // arrives through the realtime handler above; the lifecycle (queued, published, acknowledged) is on the Actions page.
        setState('queued');
        setMessage('Command accepted and queued for delivery - not yet published');
        pendingCommandIdRef.current = result.requestId;
      } else {
        setState('failed');
        setMessage(result.message);
      }
    } catch (err) {
      if (err instanceof ApiError && err.status === 429) {
        // A refusal writes nothing (P4.6): say that it was not queued, and do not call it a delivery failure.
        const scope = (err.data as { scope?: unknown } | undefined)?.scope;
        setState('refused');
        setMessage(queueFullMessage(scope));
      } else {
        setState('failed');
        setMessage(err instanceof ApiError ? err.message : 'Command failed');
      }
    }
  };

  // Visual-only (Step 3 correction): restrained lavender/glass treatment
  // via the foundation Button primitive, replacing the legacy blue
  // --primary chip. No dispatch/ACK logic above this line changed.
  const messageColor =
    state === 'failed' || state === 'rejected' ? 'var(--danger)' :
    state === 'refused' ? 'var(--warning)' :
    state === 'acknowledged' ? 'var(--success)' :
    'var(--foundation-muted-fg)';

  return (
    <div className="flex flex-col gap-2" data-testid="device-command-controls">
      <div className="flex items-center gap-2">
        <Button
          type="button"
          variant="secondary"
          size="sm"
          onClick={() => sendCommand(true)}
          disabled={state === 'sending'}
        >
          Turn LED ON
        </Button>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => sendCommand(false)}
          disabled={state === 'sending'}
        >
          Turn LED OFF
        </Button>
      </div>
      {message && (
        <span
          data-testid="device-command-result"
          className="text-xs"
          style={{ color: messageColor }}
        >
          {message}
        </span>
      )}
    </div>
  );
};
