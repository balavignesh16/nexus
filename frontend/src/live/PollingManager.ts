import { QueryClient } from '@tanstack/react-query';
import { telemetryApi } from '../api/telemetryApi';
import { twinApi } from '../api/twinApi';
import { eventApi } from '../api/eventApi';
import { actionApi } from '../api/actionApi';
import { ruleApi } from '../api/ruleApi';
import { deviceApi } from '../api/deviceApi';

export type ConnectionStatus = 'UNKNOWN' | 'CONNECTING' | 'ONLINE' | 'WARNING' | 'CRITICAL' | 'OFFLINE' | 'RECOVERING';

export interface PollingStats {
  status: ConnectionStatus;
  latencyMs: number;
  lastUpdate: number;
  backendReachable: boolean;
  mqttConnected: boolean; // simulated for now
  errorCount: number;
  lastError: string | null;
}

type Subscriber = (stats: PollingStats) => void;

class PollingManager {
  private queryClient: QueryClient | null = null;
  private intervalId: number | null = null;
  private isPaused: boolean = false;
  private isFetching: boolean = false;
  private subscribers: Set<Subscriber> = new Set();
  
  private stats: PollingStats = {
    status: 'UNKNOWN',
    latencyMs: 0,
    lastUpdate: 0,
    backendReachable: false,
    mqttConnected: false,
    errorCount: 0,
    lastError: null
  };

  public init(queryClient: QueryClient) {
    if (this.queryClient) return; // Already initialized
    this.queryClient = queryClient;
    this.stats.status = 'CONNECTING';
    this.notifySubscribers();

    // Listen to visibility changes
    document.addEventListener('visibilitychange', this.handleVisibilityChange);
    
    this.start();
  }

  public destroy() {
    this.stop();
    document.removeEventListener('visibilitychange', this.handleVisibilityChange);
    this.queryClient = null;
    this.subscribers.clear();
  }

  public subscribe(callback: Subscriber) {
    this.subscribers.add(callback);
    callback(this.stats); // initial emit
    return () => this.subscribers.delete(callback);
  }

  private notifySubscribers() {
    this.subscribers.forEach(cb => cb(this.stats));
  }

  private handleVisibilityChange = () => {
    if (document.hidden) {
      this.pause();
    } else {
      this.resume();
    }
  };

  private start() {
    if (this.intervalId) return;
    this.poll(); // immediate first fetch
    this.intervalId = window.setInterval(() => this.poll(), 3000);
  }

  private stop() {
    if (this.intervalId) {
      clearInterval(this.intervalId);
      this.intervalId = null;
    }
  }

  public pause() {
    this.isPaused = true;
  }

  public resume() {
    if (this.isPaused) {
      this.isPaused = false;
      this.poll();
    }
  }

  public getStats() {
    return this.stats;
  }

  private updateStatus(newStats: Partial<PollingStats>) {
    this.stats = { ...this.stats, ...newStats };
    
    // Evaluate aggregate status
    if (!this.stats.backendReachable) {
      this.stats.status = 'OFFLINE';
    } else if (this.stats.errorCount > 3) {
      this.stats.status = 'CRITICAL';
    } else if (this.stats.errorCount > 0) {
      this.stats.status = 'WARNING';
    } else if (this.stats.status === 'OFFLINE' || this.stats.status === 'CRITICAL' || this.stats.status === 'WARNING') {
      this.stats.status = 'RECOVERING';
      // Recover to online after a successful update if it was recovering
      setTimeout(() => {
        if (this.stats.errorCount === 0 && this.stats.backendReachable) {
          this.stats.status = 'ONLINE';
          this.notifySubscribers();
        }
      }, 2000);
    } else {
      this.stats.status = 'ONLINE';
    }

    this.notifySubscribers();
  }

  private async poll() {
    if (!this.queryClient || this.isPaused || this.isFetching) return;
    this.isFetching = true;

    const start = performance.now();

    try {
      // Fire-and-forget style parallel fetching
      const [telemetry, twins, events, actions, rules, devices] = await Promise.all([
        telemetryApi.getLatestTelemetry(100),
        twinApi.getLatestTwins(100),
        eventApi.getEvents(50),
        actionApi.getActionHistory(50),
        ruleApi.getRules(),
        deviceApi.getActiveDevices()
      ]);

      const end = performance.now();

      // Update React Query Cache
      this.queryClient.setQueryData(['telemetry', 100], telemetry);
      this.queryClient.setQueryData(['twins', 100], twins);
      this.queryClient.setQueryData(['events', 50], events);
      this.queryClient.setQueryData(['actions', 50], actions);
      this.queryClient.setQueryData(['rules'], rules);
      this.queryClient.setQueryData(['activeDevices'], devices);

      this.updateStatus({
        latencyMs: Math.round(end - start),
        lastUpdate: Date.now(),
        backendReachable: true,
        mqttConnected: true,
        errorCount: 0,
        lastError: null
      });

    } catch (error: any) {
      this.updateStatus({
        backendReachable: false,
        mqttConnected: false,
        errorCount: this.stats.errorCount + 1,
        lastError: error.message || 'Connection failed'
      });
    } finally {
      this.isFetching = false;
    }
  }
}

export const pollingManager = new PollingManager();
