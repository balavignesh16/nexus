import { useRealtime } from '../useRealtime';
import type { TelemetryPayload } from '../RealtimeEvents';
import { TOPICS } from '../RealtimeManager';

/**
 * Subscribes to live telemetry events from the platform.
 *
 * Usage:
 *   useTelemetryStream(deviceId, (payload) => {
 *     setMetrics(prev => [...prev, payload]);
 *   });
 *
 * @param deviceId  Optional: filter events to a specific device
 * @param handler   Callback receiving the telemetry payload
 * @param enabled   Set to false to temporarily pause subscription
 */
export function useTelemetryStream(
  deviceId: string | undefined,
  handler: (payload: TelemetryPayload) => void,
  enabled = true
): void {
  useRealtime<TelemetryPayload>(
    TOPICS.TELEMETRY,
    (msg) => handler(msg.payload),
    { deviceId, enabled }
  );
}
