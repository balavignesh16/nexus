import { useEffect, useRef } from 'react';
import { useRealtimeContext } from './RealtimeContext';
import type { RealtimeMessage } from './RealtimeEvents';

/**
 * Low-level hook to subscribe to a raw STOMP topic.
 *
 * Pages should NOT use this hook directly.
 * Instead use the typed stream hooks:
 *   useTelemetryStream, useTwinStream, useEventStream,
 *   useRuleStream, useActionStream, usePlatformHealthStream
 *
 * @param topic     STOMP topic string (e.g. "/topic/telemetry")
 * @param handler   Callback receiving the typed RealtimeMessage
 * @param options   Optional: filter by deviceId, or disable subscription
 */
export function useRealtime<T>(
  topic: string,
  handler: (msg: RealtimeMessage<T>) => void,
  options?: {
    deviceId?: string;
    enabled?: boolean;
  }
): void {
  const { subscribe } = useRealtimeContext();
  const { deviceId, enabled = true } = options ?? {};
  const handlerRef = useRef(handler);

  useEffect(() => {
    handlerRef.current = handler;
  }, [handler]);

  useEffect(() => {
    if (!enabled) return;

    const filteredHandler = (msg: RealtimeMessage<T>) => {
      // Apply device-level filtering client-side
      if (deviceId && msg.deviceId && msg.deviceId !== deviceId) return;
      handlerRef.current(msg);
    };

    return subscribe<T>(topic, filteredHandler);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [topic, deviceId, enabled]);
}
