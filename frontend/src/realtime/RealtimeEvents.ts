/**
 * TypeScript types for all real-time events streaming from the backend.
 *
 * Mirrors the backend RealtimeMessage Java record exactly.
 * Components must never use raw STOMP topics — they use typed stream hooks
 * (useTelemetryStream, useTwinStream, etc.) which internally subscribe to these.
 */

export type RealtimeEventType =
  | 'TelemetryReceived'
  | 'TwinUpdated'
  | 'DomainEventCreated'
  | 'RuleMatched'
  | 'ActionExecuted'
  | 'DeviceOnline'
  | 'DeviceOffline'
  | 'PlatformHealthChanged'
  | 'PipelineTrace';

export type RealtimeSource = 'BACKEND' | 'DEMO' | 'REPLAY';

export interface RealtimeMessage<T = unknown> {
  eventId: string;
  eventType: RealtimeEventType;
  deviceId: string | null;
  correlationId: string;
  sequenceNumber: number;
  /** Epoch milliseconds on the server clock. Used for client/server clock offset. */
  serverTimestamp: number;
  payload: T;
  source: RealtimeSource;
  version: number;
}

// ─── Payload Shapes ───────────────────────────────────────────────────────────

export interface TelemetryPayload {
  deviceId: string;
  sensorType: string;
  value: number;
  unit: string;
  timestamp: number;
}

export interface TwinDelta {
  property: string;
  oldValue: unknown;
  newValue: unknown;
  timestamp: number;
}

export interface TwinUpdatedPayload {
  deviceId: string;
  deltas: TwinDelta[];
  twinTimestamp: number;
}

export interface DomainEventPayload {
  eventId: string;
  deviceId: string;
  eventType: string;
  severity: string;
  source: string;
  timestamp: number;
}

export interface RuleMatchedPayload {
  ruleId: string;
  ruleName: string;
  reason: string;
  deviceId: string;
}

export interface ActionExecutedPayload {
  executionId: string;
  actionType: string;
  status: string;
  executedAt: number;
  message: string;
}

export interface PipelineTracePayload {
  traceId: string;
  correlationId: string;
  stage: 'MQTT_IN' | 'TELEMETRY' | 'TWIN' | 'RULE' | 'ACTION' | 'ALERT';
  deviceId: string | null;
  enteredAt: number;
  exitedAt: number;
  latencyMs: number;
  succeeded: boolean;
  stagePayload: unknown;
}

export interface PlatformHealthPayload {
  backend: string;
  mqtt: string;
  database: string;
  [key: string]: string;
}

/**
 * Physical MQTT connectivity (Tier 5B) - deliberately independent of a
 * device's manually-managed lifecycle status (REGISTERED/ACTIVE/
 * MAINTENANCE/OFFLINE). Delivered as eventType "DeviceOnline" or
 * "DeviceOffline".
 */
export interface DeviceConnectivityPayload {
  deviceId: string;
  status: 'ONLINE' | 'OFFLINE';
}
