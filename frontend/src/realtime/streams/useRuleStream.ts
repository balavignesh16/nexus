import { useRealtime } from '../useRealtime';
import type { RuleMatchedPayload } from '../RealtimeEvents';
import { TOPICS } from '../RealtimeManager';

/** Subscribes to live rule match events. */
export function useRuleStream(
  handler: (payload: RuleMatchedPayload) => void,
  enabled = true
): void {
  useRealtime<RuleMatchedPayload>(
    TOPICS.RULES,
    (msg) => handler(msg.payload),
    { enabled }
  );
}
