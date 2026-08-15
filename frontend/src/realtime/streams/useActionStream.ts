import { useRealtime } from '../useRealtime';
import type { ActionExecutedPayload } from '../RealtimeEvents';
import { TOPICS } from '../RealtimeManager';

/** Subscribes to live action execution results. */
export function useActionStream(
  handler: (payload: ActionExecutedPayload) => void,
  enabled = true
): void {
  useRealtime<ActionExecutedPayload>(
    TOPICS.ACTIONS,
    (msg) => handler(msg.payload),
    { enabled }
  );
}
