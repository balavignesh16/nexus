import { useRealtime } from '../useRealtime';
import type { TwinUpdatedPayload, TwinDelta } from '../RealtimeEvents';
import { TOPICS } from '../RealtimeManager';

/**
 * Subscribes to live Digital Twin delta updates.
 *
 * Delivers only the changed properties (delta patches),
 * not the full twin object. Apply them to your local state:
 *
 *   useTwinStream(undefined, ({ deviceId, deltas }) => {
 *     deltas.forEach(d => applyDelta(d.property, d.newValue));
 *   });
 */
export function useTwinStream(
  deviceId: string | undefined,
  handler: (payload: TwinUpdatedPayload) => void,
  enabled = true
): void {
  useRealtime<TwinUpdatedPayload>(
    TOPICS.TWINS,
    (msg) => handler(msg.payload),
    { deviceId, enabled }
  );
}

export type { TwinDelta };
