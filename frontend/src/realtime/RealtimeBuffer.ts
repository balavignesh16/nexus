/**
 * Client-side bounded circular buffer for real-time events.
 *
 * Every event received by RealtimeManager is stored here. When the WebSocket
 * reconnects, the frontend first checks the local buffer for missed events
 * (faster than a REST round-trip), then falls back to GET /api/realtime/replay
 * for anything older than the buffer window.
 *
 * Memory safety: Each topic has a fixed-size queue. Oldest entries are evicted
 * when capacity is exceeded. Default: 1000 entries per topic.
 */

import type { RealtimeMessage } from './RealtimeEvents';

export class RealtimeBuffer {
  private readonly maxPerTopic: number;
  private readonly store = new Map<string, RealtimeMessage[]>();

  constructor(maxPerTopic = 1000) {
    this.maxPerTopic = maxPerTopic;
  }

  /** Appends a message to the per-topic buffer, evicting oldest if full. */
  append(topic: string, message: RealtimeMessage): void {
    if (!this.store.has(topic)) {
      this.store.set(topic, []);
    }
    const queue = this.store.get(topic)!;
    queue.push(message);
    if (queue.length > this.maxPerTopic) {
      queue.shift(); // evict oldest
    }
  }

  /**
   * Returns messages for a topic with serverTimestamp > since.
   * @param topic   Topic name
   * @param since   Epoch milliseconds
   * @param limit   Maximum results
   */
  getSince(topic: string, since: number, limit = 500): RealtimeMessage[] {
    const queue = this.store.get(topic) ?? [];
    return queue
      .filter(m => m.serverTimestamp > since)
      .slice(0, limit);
  }

  /** Returns the last recorded timestamp for a topic, or 0. */
  getLastTimestamp(topic: string): number {
    const queue = this.store.get(topic) ?? [];
    return queue.length > 0 ? queue[queue.length - 1].serverTimestamp : 0;
  }

  /** Total messages across all topics. */
  totalSize(): number {
    let total = 0;
    this.store.forEach(q => { total += q.length; });
    return total;
  }

  /** Per-topic fill ratio [0–1] relative to maxPerTopic. */
  fillRatio(topic: string): number {
    const queue = this.store.get(topic);
    if (!queue) return 0;
    return queue.length / this.maxPerTopic;
  }

  /** Clears all buffered data. */
  clear(): void {
    this.store.clear();
  }
}
