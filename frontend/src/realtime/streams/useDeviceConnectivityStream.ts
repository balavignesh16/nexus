import { useRealtime } from '../useRealtime';
import type { DeviceConnectivityPayload } from '../RealtimeEvents';
import { TOPICS } from '../RealtimeManager';

/**
 * Subscribes to live physical MQTT connectivity changes (Tier 5B).
 *
 * Deliberately independent of a device's manually-managed lifecycle status
 * (DeviceStatus: REGISTERED/ACTIVE/MAINTENANCE/OFFLINE) - never merge these
 * two signals into one display. See DeviceConnectivityBadge for the
 * intended presentation.
 */
export function useDeviceConnectivityStream(
  deviceId: string | undefined,
  handler: (payload: DeviceConnectivityPayload) => void,
  enabled = true
): void {
  useRealtime<DeviceConnectivityPayload>(
    TOPICS.CONNECTIVITY,
    (msg) => handler(msg.payload),
    { deviceId, enabled }
  );
}
