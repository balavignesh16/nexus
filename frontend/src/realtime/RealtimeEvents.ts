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
  | 'ActionAcknowledged'
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

/**
 * Tier 5E: whether the device acknowledged/processed a dispatched command -
 * NOT physical-outcome verification. ACCEPTED means the device parsed the
 * command, passed its own validation, and applied it (e.g. called
 * digitalWrite()); it never means "LED is ON/OFF".
 */
export interface ActionAcknowledgedPayload {
  commandId: string;
  deviceId: string;
  status: 'ACCEPTED' | 'REJECTED';
  message: string;
  timestamp: number;
}

// The backend broadcasts the same PipelineTrace bean over this WebSocket
// topic that it persists and serves via GET /pipeline/history - there is
// no separate realtime-only shape (verified against
// RealtimePublisher.publishPipelineTrace, which broadcasts the raw
// persisted trace object; PipelineTrace's getters, not its @Transient
// JPA annotations, are what Jackson serializes).
export interface PipelineTracePayload {
  traceId: string;
  correlationId: string;
  deviceId: string | null;
  stage: 'MQTT_IN' | 'TELEMETRY' | 'TWIN' | 'RULE' | 'ACTION' | 'ALERT';
  stageIndex: number;
  previousStage: string | null;
  nextStage: string | null;
  startTimestamp: number;
  endTimestamp: number;
  durationMs: number;
  status: string;
  payloadSize: number;
  metadata: Record<string, any>;
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
