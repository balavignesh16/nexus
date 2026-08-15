import { createContext, useContext } from 'react';
import type { ConnectionState } from './RealtimeManager';
import type { RealtimeMessage } from './RealtimeEvents';

export interface RealtimeContextValue {
  connectionState: ConnectionState;
  /** Server clock minus client clock in ms. Used to correct chart timestamps. */
  clientOffset: number;
  messagesPerSec: number;
  bytesPerSec: number;
  droppedMessages: number;
  reconnectAttempts: number;
  activeSubscriptions: string[];
  /**
   * Low-level subscribe. Use typed stream hooks (useTelemetryStream, etc.) instead.
   */
  subscribe<T>(
    topic: string,
    handler: (msg: RealtimeMessage<T>) => void
  ): () => void;
  /**
   * Triggers replay of missed events for a topic since the given epoch ms.
   */
  replay(topic: string): Promise<void>;
}

export const RealtimeContext = createContext<RealtimeContextValue | null>(null);

export function useRealtimeContext(): RealtimeContextValue {
  const ctx = useContext(RealtimeContext);
  if (!ctx) throw new Error('useRealtimeContext must be used inside <RealtimeProvider>');
  return ctx;
}
