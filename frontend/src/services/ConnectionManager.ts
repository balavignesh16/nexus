import { useState, useEffect } from 'react';
import { RealtimeManager, type ConnectionState as RealtimeConnectionState } from '../realtime/RealtimeManager';

export type ServiceStatus = 'Online' | 'Offline' | 'Degraded';

export interface ConnectionState {
  backend: ServiceStatus;
  mqtt: ServiceStatus;
  database: ServiceStatus;
  latencyMs: number;
  reconnectAttempts: number;
  realtimeStatus: RealtimeConnectionState;
  messagesPerSec: number;
  bytesPerSec: number;
  droppedMessages: number;
  clientOffset: number;
}

class ConnectionManagerService {
  private state: ConnectionState = {
    backend: 'Offline',
    mqtt: 'Offline',
    database: 'Offline',
    latencyMs: 0,
    reconnectAttempts: 0,
    realtimeStatus: 'disconnected',
    messagesPerSec: 0,
    bytesPerSec: 0,
    droppedMessages: 0,
    clientOffset: 0,
  };

  private listeners = new Set<(state: ConnectionState) => void>();

  constructor() {
    RealtimeManager.onStateChange((realtimeStatus, metrics) => {
      this.updateState({
        realtimeStatus,
        messagesPerSec: metrics.messagesPerSec,
        bytesPerSec: metrics.bytesPerSec,
        droppedMessages: metrics.droppedMessages,
        clientOffset: metrics.clientOffset,
        reconnectAttempts: metrics.reconnectAttempts,
      });
    });
    this.startMonitoring();
  }

  private async startMonitoring() {
    const ping = async () => {
      const start = performance.now();
      try {
        // Backend reachability: "the backend answered this HTTP request."
        const response = await fetch('http://localhost:8080/api/v1/devices/active');
        const latency = Math.round(performance.now() - start);

        if (response.ok) {
          this.updateState({
            backend: 'Online',
            latencyMs: latency,
            reconnectAttempts: 0
          });
        } else {
          throw new Error('Degraded');
        }
      } catch (err) {
        // Backend is unreachable, so database/MQTT state can't be known either.
        this.updateState({
          backend: 'Offline',
          database: 'Offline',
          mqtt: 'Offline',
          reconnectAttempts: this.state.reconnectAttempts + 1
        });
        return;
      }

      // Database and MQTT are independently, directly verified via the
      // backend's own platform-health endpoint - never inferred from the
      // devices/active call above.
      try {
        const healthResponse = await fetch('http://localhost:8080/api/v1/platform-health');
        if (healthResponse.ok) {
          const health: { database: string; mqtt: string } = await healthResponse.json();
          this.updateState({
            database: health.database === 'UP' ? 'Online' : 'Offline',
            mqtt: health.mqtt === 'CONNECTED' ? 'Online' : 'Offline',
          });
        } else {
          this.updateState({ database: 'Offline', mqtt: 'Offline' });
        }
      } catch (err) {
        this.updateState({ database: 'Offline', mqtt: 'Offline' });
      }
    };

    ping();
    setInterval(ping, 10000); // 10s ping
  }

  private updateState(newState: Partial<ConnectionState>) {
    this.state = { ...this.state, ...newState };
    this.notify();
  }

  subscribe(listener: (state: ConnectionState) => void): () => void {
    this.listeners.add(listener);
    listener(this.state);
    return () => {
      this.listeners.delete(listener);
    };
  }

  private notify() {
    this.listeners.forEach(l => l(this.state));
  }
}

export const ConnectionManager = new ConnectionManagerService();

export function useConnectionStatus() {
  const [status, setStatus] = useState<ConnectionState>(ConnectionManager['state']);

  useEffect(() => {
    return ConnectionManager.subscribe(setStatus);
  }, []);

  return status;
}
