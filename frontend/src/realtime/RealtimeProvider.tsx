import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { RealtimeContext } from './RealtimeContext';
import { RealtimeManager, type ConnectionState } from './RealtimeManager';
import { FEATURES } from '../config/features';
import { RealtimeDevTools } from './RealtimeDevTools';

/**
 * Owns the tab-wide connection lifecycle and exposes its stable public API.
 * All page-level access is through the typed stream hooks, never STOMP.
 */
export function RealtimeProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<ConnectionState>(RealtimeManager.state);
  const [metrics, setMetrics] = useState(RealtimeManager.metrics);

  useEffect(() => {
    RealtimeManager.connect();
    return RealtimeManager.onStateChange((nextState, nextMetrics) => {
      setState(nextState);
      setMetrics(nextMetrics);
    });
  }, []);

  const value = useMemo(() => ({
    connectionState: state,
    clientOffset: metrics.clientOffset,
    messagesPerSec: metrics.messagesPerSec,
    bytesPerSec: metrics.bytesPerSec,
    droppedMessages: metrics.droppedMessages,
    reconnectAttempts: metrics.reconnectAttempts,
    activeSubscriptions: metrics.activeSubscriptions,
    subscribe: RealtimeManager.subscribe.bind(RealtimeManager),
    replay: RealtimeManager.replay.bind(RealtimeManager),
  }), [state, metrics]);

  return <RealtimeContext.Provider value={value}>{children}{FEATURES.REALTIME_DEV_TOOLS && import.meta.env.DEV && <RealtimeDevTools />}</RealtimeContext.Provider>;
}
