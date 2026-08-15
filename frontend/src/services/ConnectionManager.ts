import { useState, useEffect } from 'react';
import { RealtimeManager, type ConnectionState as RealtimeConnectionState } from '../realtime/RealtimeManager';

export type ServiceStatus = 'Online' | 'Offline' | 'Degraded';

export interface ConnectionState {
  backend: ServiceStatus;
  mqtt: ServiceStatus;
  database: ServiceStatus;
  twinEngine: ServiceStatus;
  ruleEngine: ServiceStatus;
  actionEngine: ServiceStatus;
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
    twinEngine: 'Offline',
    ruleEngine: 'Offline',
    actionEngine: 'Offline',
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
        // Simple health check endpoint (assuming spring boot actuator or similar)
        const response = await fetch('http://localhost:8080/api/v1/devices/active');
        const latency = Math.round(performance.now() - start);
        
        if (response.ok) {
          this.updateState({
            backend: 'Online',
            database: 'Online', // Inference
            twinEngine: 'Online',
            ruleEngine: 'Online',
            actionEngine: 'Online',
            mqtt: 'Online', // In a real app, MQTT client status handles this
            latencyMs: latency,
            reconnectAttempts: 0
          });
        } else {
          throw new Error('Degraded');
        }
      } catch (err) {
        this.updateState({
          backend: 'Offline',
          database: 'Offline',
          twinEngine: 'Offline',
          ruleEngine: 'Offline',
          actionEngine: 'Offline',
          mqtt: 'Offline',
          reconnectAttempts: this.state.reconnectAttempts + 1
        });
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
