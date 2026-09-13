import { useRealtime } from '../useRealtime';
import type { ActionAcknowledgedPayload } from '../RealtimeEvents';
import { TOPICS } from '../RealtimeManager';

/**
 * Subscribes to live command-acknowledgement events (Tier 5E). Reuses the
 * existing /topic/actions channel (eventType "ActionAcknowledged") -
 * deliberately no new platform-level realtime topic is introduced.
 *
 * ACCEPTED/REJECTED describe only whether the device processed the
 * command - never a physical-outcome claim. See ActionAcknowledgedPayload.
 */
export function useActionAcknowledgementStream(
  deviceId: string | undefined,
  handler: (payload: ActionAcknowledgedPayload) => void,
  enabled = true
): void {
  useRealtime<ActionAcknowledgedPayload>(
    TOPICS.ACTIONS,
    (msg) => {
      if (msg.eventType === 'ActionAcknowledged') {
        handler(msg.payload);
      }
    },
    { deviceId, enabled }
  );
}
