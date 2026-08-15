import { TOPICS } from '../RealtimeManager';
import type { PipelineTracePayload } from '../RealtimeEvents';
import { useRealtime } from '../useRealtime';

/** F7.5-ready stream of correlated pipeline stage traces. */
export function usePipelineStream(
  deviceId: string | undefined,
  handler: (payload: PipelineTracePayload) => void,
  enabled = true,
): void {
  useRealtime<PipelineTracePayload>(TOPICS.PIPELINE, (message) => handler(message.payload), {
    deviceId,
    enabled,
  });
}
