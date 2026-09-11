/**
 * RealtimeManager — Singleton WebSocket connection manager.
 *
 * Architecture:
 * - One connection per browser tab (singleton).
 * - Uses STOMP over SockJS for broad browser support.
 * - Reconnects automatically with exponential backoff (1s initial, doubling, capped at 30s).
 * - On reconnect: restores all subscriptions + triggers replay.
 * - Batches incoming messages (100ms flush) to prevent 100 React renders/sec.
 * - Pauses chart updates when document is hidden (Visibility API).
 * - Clock sync: computes server/client offset on connect.
 * - Auth-ready: injects Authorization header when token is available.
 * - In Demo Mode: no socket is opened. Internal EventTarget bus only.
 */

import { Client, ReconnectionTimeMode, type StompSubscription } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import type { RealtimeMessage } from './RealtimeEvents';
import { RealtimeBuffer } from './RealtimeBuffer';
import { FEATURES } from '../config/features';

export type ConnectionState =
  | 'connecting'
  | 'connected'
  | 'disconnected'
  | 'recovering'
  | 'demo';

type MessageHandler<T = unknown> = (msg: RealtimeMessage<T>) => void;

interface Metrics {
  messagesPerSec: number;
  bytesPerSec: number;
  droppedMessages: number;
  reconnectAttempts: number;
  latencyMs: number;
  /** Server clock minus client clock in ms (positive = server ahead). */
  clientOffset: number;
  activeSubscriptions: string[];
}

type StateListener = (state: ConnectionState, metrics: Metrics) => void;

const TOPICS = {
  TELEMETRY: '/topic/telemetry',
  TWINS:     '/topic/twins',
  EVENTS:    '/topic/events',
  RULES:     '/topic/rules',
  ACTIONS:   '/topic/actions',
  HEALTH:    '/topic/platform/health',
  PIPELINE:  '/topic/pipeline',
} as const;

export { TOPICS };

class RealtimeManagerClass {
  private client: Client | null = null;
  private _state: ConnectionState = 'disconnected';

  // Per-topic subscriber sets
  private subscribers = new Map<string, Set<MessageHandler>>();
  // Active STOMP subscription handles (for cleanup on reconnect)
  private stompSubs = new Map<string, StompSubscription>();

  // Message batching: queue accumulated during batch window
  private messageQueue: Array<{ topic: string; msg: RealtimeMessage }> = [];
  private batchTimerId: ReturnType<typeof setInterval> | null = null;
  private isHidden = false;

  // Metrics
  private msgCountWindow: number[] = [];
  private byteCountWindow: number[] = [];
  private _metrics: Metrics = {
    messagesPerSec: 0,
    bytesPerSec: 0,
    droppedMessages: 0,
    reconnectAttempts: 0,
    latencyMs: 0,
    clientOffset: 0,
    activeSubscriptions: [],
  };

  readonly buffer = new RealtimeBuffer(FEATURES.REALTIME_BUFFER_SIZE ?? 1000);
  private stateListeners = new Set<StateListener>();
  private reconnectAttempt = 0;
  private lastEventTimestamps = new Map<string, number>();

  // ─── Init ─────────────────────────────────────────────────────────────────

  connect() {
    if (this._state === 'connected' || this._state === 'connecting') return;

    if (FEATURES.DEMO_MODE && !FEATURES.LIVE_BACKEND) {
      this.setState('demo');
      this.startBatchFlush();
      this.watchVisibility();
      return;
    }

    this.setState('connecting');
    this.startBatchFlush();
    this.watchVisibility();
    this.createClient();
  }

  private createClient() {
    const wsUrl = FEATURES.REALTIME_WS_URL ?? 'http://localhost:8080/ws';

    this.client = new Client({
      webSocketFactory: () => new SockJS(wsUrl) as WebSocket,

      // Auth hook: no-op today, ready for JWT
      connectHeaders: this.buildConnectHeaders(),

      onConnect: (frame) => {
        this.reconnectAttempt = 0;

        // Clock sync
        const serverTime = Number(frame.headers['server-time'] ?? Date.now());
        this._metrics.clientOffset = serverTime - Date.now();

        this.setState('connected');
        this.resubscribeAll();
        this.triggerReplay();
      },

      onDisconnect: () => {
        this.setState('disconnected');
      },

      onStompError: () => {
        this.setState('recovering');
      },

      onWebSocketClose: () => {
        this.reconnectAttempt++;
        this._metrics.reconnectAttempts = this.reconnectAttempt;
      },

      // Exponential backoff: 1s initial delay, doubling on each attempt, capped at 30s.
      reconnectDelay: 1000,
      reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
      maxReconnectDelay: 30_000,
    });

    this.client.activate();
  }

  private buildConnectHeaders(): Record<string, string> {
    const headers: Record<string, string> = {};
    // Auth hook: uncomment when auth is added
    // const token = getAuthToken();
    // if (token) headers['Authorization'] = `Bearer ${token}`;
    headers['server-time-request'] = 'true';
    return headers;
  }

  disconnect() {
    this.batchTimerId && clearInterval(this.batchTimerId);
    this.client?.deactivate();
    this.setState('disconnected');
  }

  // ─── Subscriptions ────────────────────────────────────────────────────────

  /**
   * Subscribe to a topic. Returns unsubscribe function.
   * Components should NOT call this directly — use typed stream hooks.
   */
  subscribe<T>(topic: string, handler: MessageHandler<T>): () => void {
    if (!this.subscribers.has(topic)) {
      this.subscribers.set(topic, new Set());
    }
    this.subscribers.get(topic)!.add(handler as MessageHandler);

    // If already connected via STOMP, subscribe immediately
    if (this._state === 'connected' && this.client?.connected) {
      this.ensureStompSub(topic);
    }

    this._metrics.activeSubscriptions = Array.from(this.subscribers.keys());

    return () => {
      this.subscribers.get(topic)?.delete(handler as MessageHandler);
      if (this.subscribers.get(topic)?.size === 0) {
        this.subscribers.delete(topic);
        this.stompSubs.get(topic)?.unsubscribe();
        this.stompSubs.delete(topic);
        this._metrics.activeSubscriptions = Array.from(this.subscribers.keys());
      }
    };
  }

  private resubscribeAll() {
    this.subscribers.forEach((_, topic) => this.ensureStompSub(topic));
  }

  private ensureStompSub(topic: string) {
    if (this.stompSubs.has(topic) || !this.client?.connected) return;

    const sub = this.client.subscribe(topic, (frame) => {
      try {
        const msg: RealtimeMessage = JSON.parse(frame.body);
        const byteLen = frame.body.length;

        // Runtime Validation
        if (!msg || !msg.eventId || msg.sequenceNumber === undefined || !msg.serverTimestamp) {
          console.warn('[Realtime] Invalid message payload discarded', msg);
          // In a real metrics scenario, increment invalidMessage metric here.
          return;
        }

        // Stale Check
        const lastSeq = (this as any).lastSequenceNumbers?.get(topic) || 0;
        if (msg.sequenceNumber <= lastSeq && msg.source !== 'REPLAY') {
          console.debug(`[Realtime] Dropping stale message for ${topic} (seq: ${msg.sequenceNumber} <= ${lastSeq})`);
          return;
        }
        if (!(this as any).lastSequenceNumbers) (this as any).lastSequenceNumbers = new Map<string, number>();
        if (msg.source !== 'REPLAY') {
          (this as any).lastSequenceNumbers.set(topic, msg.sequenceNumber);
        }

        // Track metrics
        this.msgCountWindow.push(Date.now());
        this.byteCountWindow.push(byteLen);

        // Buffer the message
        this.buffer.append(topic, msg);
        this.lastEventTimestamps.set(topic, msg.serverTimestamp);

        // Enqueue for batched flush
        if (!this.isHidden || topic === TOPICS.EVENTS || topic === TOPICS.ACTIONS) {
          this.messageQueue.push({ topic, msg });
        } else {
          this._metrics.droppedMessages++;
        }
      } catch (e) {
        console.error('[Realtime] Failed to parse message', e);
      }
    });

    this.stompSubs.set(topic, sub);
  }

  // ─── Demo Mode Bridge ─────────────────────────────────────────────────────

  /**
   * Called by RealtimeProvider's Demo Mode bridge.
   * Simulates a received message from the ScenarioEngine.
   */
  injectDemoMessage(topic: string, msg: RealtimeMessage) {
    this.buffer.append(topic, msg);
    this.lastEventTimestamps.set(topic, msg.serverTimestamp);
    this.messageQueue.push({ topic, msg });
  }

  // ─── Batched Flush ────────────────────────────────────────────────────────

  private startBatchFlush() {
    if (this.batchTimerId) return;
    const batchMs = FEATURES.REALTIME_BATCH_MS ?? 100;
    this.batchTimerId = setInterval(() => this.flush(), batchMs);
  }

  private flush() {
    if (this.messageQueue.length === 0) return;

    // Compute metrics
    const now = Date.now();
    const windowMs = 1000;
    this.msgCountWindow = this.msgCountWindow.filter(t => now - t < windowMs);
    this.byteCountWindow = this.byteCountWindow.filter((_, i) =>
      this.msgCountWindow[i] !== undefined
    );
    this._metrics.messagesPerSec = this.msgCountWindow.length;
    this._metrics.bytesPerSec = this.byteCountWindow.reduce((a, b) => a + b, 0);

    // Group messages by topic for efficient dispatch
    const byTopic = new Map<string, RealtimeMessage[]>();
    for (const { topic, msg } of this.messageQueue) {
      if (!byTopic.has(topic)) byTopic.set(topic, []);
      byTopic.get(topic)!.push(msg);
    }
    this.messageQueue = [];

    byTopic.forEach((msgs, topic) => {
      const handlers = this.subscribers.get(topic);
      if (!handlers) return;
      // Deliver last message only for high-frequency topics to prevent overload
      const toDeliver = topic === TOPICS.TELEMETRY ? [msgs[msgs.length - 1]] : msgs;
      toDeliver.forEach(msg => {
        handlers.forEach(h => {
          try { h(msg); } catch (e) { console.error('[Realtime] Handler error', e); }
        });
      });
    });

    this.notifyStateListeners();
  }

  // ─── Replay ───────────────────────────────────────────────────────────────

  async replay(topic: string): Promise<void> {
    const since = this.lastEventTimestamps.get(topic) ?? (Date.now() - 30_000);
    const local = this.buffer.getSince(topic, since);

    if (local.length > 0) {
      // Local buffer has the messages — inject them directly
      local.forEach(msg => this.messageQueue.push({ topic, msg }));
      return;
    }

    // Fallback: fetch from backend REST replay endpoint
    try {
      const url = `${FEATURES.REALTIME_WS_URL?.replace('ws:', 'http:').replace('/ws', '') ?? 'http://localhost:8080'}/api/realtime/replay?since=${since}&topic=${encodeURIComponent(topic)}`;
      const res = await fetch(url);
      if (!res.ok) return;
      const messages: RealtimeMessage[] = await res.json();
      messages.forEach(msg => {
        msg = { ...msg, source: 'REPLAY' };
        this.buffer.append(topic, msg);
        this.messageQueue.push({ topic, msg });
      });
    } catch (e) {
      console.warn('[Realtime] Replay fetch failed', e);
    }
  }

  private async triggerReplay() {
    const topics = Array.from(this.subscribers.keys());
    for (const topic of topics) {
      await this.replay(topic);
    }
  }

  // ─── Visibility API ───────────────────────────────────────────────────────

  private watchVisibility() {
    document.addEventListener('visibilitychange', () => {
      this.isHidden = document.hidden;
      if (!this.isHidden) {
        // Resume: flush anything queued while hidden
        this.flush();
      }
    });
  }

  // ─── State ────────────────────────────────────────────────────────────────

  private setState(state: ConnectionState) {
    this._state = state;
    this._metrics.reconnectAttempts = this.reconnectAttempt;
    this.notifyStateListeners();
  }

  get state(): ConnectionState {
    return this._state;
  }

  get metrics(): Metrics {
    return { ...this._metrics };
  }

  onStateChange(listener: StateListener): () => void {
    this.stateListeners.add(listener);
    listener(this._state, this._metrics);
    return () => this.stateListeners.delete(listener);
  }

  private notifyStateListeners() {
    this.stateListeners.forEach(l => l(this._state, { ...this._metrics }));
  }
}

export const RealtimeManager = new RealtimeManagerClass();
