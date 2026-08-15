import { useRealtime } from '../useRealtime';
import type { PlatformHealthPayload } from '../RealtimeEvents';
import { TOPICS } from '../RealtimeManager';

/** Subscribes to platform-wide health status changes. */
export function usePlatformHealthStream(
  handler: (payload: PlatformHealthPayload) => void,
  enabled = true
): void {
  useRealtime<PlatformHealthPayload>(
    TOPICS.HEALTH,
    (msg) => handler(msg.payload),
    { enabled }
  );
}
