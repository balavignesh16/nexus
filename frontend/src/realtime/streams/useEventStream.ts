import { useRealtime } from '../useRealtime';
import type { DomainEventPayload } from '../RealtimeEvents';
import { TOPICS } from '../RealtimeManager';

/**
 * Subscribes to live domain events (threshold alerts, anomalies, etc.).
 *
 *   useEventStream(undefined, (event) => {
 *     setEvents(prev => [event, ...prev].slice(0, 500));
 *   });
 */
export function useEventStream(
  deviceId: string | undefined,
  handler: (payload: DomainEventPayload) => void,
  enabled = true
): void {
  useRealtime<DomainEventPayload>(
    TOPICS.EVENTS,
    (msg) => handler(msg.payload),
    { deviceId, enabled }
  );
}
