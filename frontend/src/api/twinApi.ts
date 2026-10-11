import { apiClient } from './apiClient';

// One response represents one device. reportedState retains every capability
// independently; latest* is the backwards-compatible most-recent summary.
// Field names are verified against DigitalTwinResponse.java.
export interface DigitalTwinResponse {
  deviceId: string;
  latestTimestamp: string | null;
  latestSensorType: string | null;
  latestValue: number | null;
  latestUnit: string | null;
  lastSeen: string | null;
  quality: string;
  metadata: Record<string, unknown>;
  reportedState: Record<string, TwinCapabilityState>;
  desiredState?: Record<string, {
    stateId: string; typedValue: unknown; valueType: string; unit: string | null;
    source: string; commandId: string; requestedAt: string;
    acceptedForDispatchAt: string; deliveryStatus: string; ackStatus: string | null;
    commandStatus?: string | null; publishedAt?: string | null;
    ackBackendReceivedAt?: string | null; ackDeadlineAt?: string | null;
  }>;
  effectiveState?: Record<string, {
    stateId: string; value: unknown; source: 'REPORTED' | 'DESIRED_UNVERIFIED' | 'NONE';
    observedValue: unknown; intentValue: unknown; stateQuality?: TwinStateQualityEvidence | null;
    verification?: string | null;
  }>;
  verification?: Record<string, TwinVerificationState>;
}

export interface TwinVerificationState {
  status: 'UNVERIFIED' | 'PENDING' | 'CONVERGED' | 'DIVERGED' | 'TIMED_OUT' | 'NO_DESIRED_STATE' | 'NOT_APPLICABLE';
  updatedAt: string | null; deadlineAt: string | null; desiredCommandId: string | null;
  mappingId: string | null; readbackBindingId: string | null; readbackStateKey: string | null;
  readbackValue: unknown; readbackEventTime: string | null; readbackReceivedAt: string | null;
  readbackStateQuality: TwinStateQualityEvidence | null; readbackEvidence: string | null;
  attribution: string | null; comparator: string | null; tolerance: number | null;
  comparisonResult: string | null; discrepancyReason: string | null;
  readbackTelemetryId: string | null;
}

export interface TwinCapabilityState {
  stateId?: string;
  value: number | null;
  typedValue?: number | boolean | string | Record<string, unknown> | null;
  valueType?: 'NUMBER' | 'BOOLEAN' | 'STRING' | 'ENUM' | 'OBJECT' | null;
  capabilityKey?: string | null;
  definitionId?: string | null;
  bindingId?: string | null;
  resolutionSource?: string | null;
  unit: string | null;
  timestamp: string;
  lastSeen: string;
  quality: string;
  metadata: Record<string, unknown>;
  stateQuality?: TwinStateQualityEvidence | null;
}

export interface TwinStateQualityEvidence {
  state: 'GOOD' | 'STALE' | 'OFFLINE' | 'UNKNOWN';
  reason: string; evaluatedAt: string; receivedAt: string | null;
  thresholdSeconds: number; connectivity: 'ONLINE' | 'OFFLINE' | 'UNKNOWN';
}

export interface TwinHistoryItem {
  transitionId: string; deviceId: string; stateId: string; bindingId: string | null;
  legacyKey: string | null; capabilityDefinitionId: string | null; capabilityKey: string | null;
  externalIdentifier: string | null; subtargetKey: string; transitionType: string; source: string;
  valueType: string; previousValue: unknown; newValue: unknown; previousUnit: string | null;
  newUnit: string | null; eventTime: string | null; receivedAt: string | null;
  requestedAt: string | null; acceptedForDispatchAt: string | null;
  transitionedAt: string; correlationId: string | null; commandId: string | null;
  previousVerificationStatus?: string | null; newVerificationStatus?: string | null;
  verificationCommandId?: string | null; verificationMappingId?: string | null;
  readbackTelemetryId?: string | null; readbackStateKey?: string | null;
  readbackBindingId?: string | null; readbackEventTime?: string | null;
  readbackReceivedAt?: string | null; readbackEvidence?: string | null;
  readbackAttribution?: string | null; comparatorKey?: string | null;
  comparisonResult?: string | null; numericTolerance?: number | null;
  verificationReason?: string | null;
}
export interface TwinHistoryPage { items: TwinHistoryItem[]; nextCursor: string | null }
export interface TwinHistoryFilters { bindingId?: string; legacyKey?: string; transitionType?: string; from?: string; to?: string; limit?: number; cursor?: string }

export const twinApi = {
  getLatestTwins: (limit: number = 100) => 
    apiClient.get<DigitalTwinResponse[]>(`/twins?limit=${limit}`),
  
  getDeviceTwin: (deviceId: string) => 
    apiClient.get<DigitalTwinResponse>(`/devices/${deviceId}/twin`),

  getHistory: (deviceId: string, filters: TwinHistoryFilters = {}) => {
    const query = new URLSearchParams();
    Object.entries(filters).forEach(([key, value]) => { if (value !== undefined && value !== '') query.set(key, String(value)); });
    return apiClient.get<TwinHistoryPage>(`/devices/${deviceId}/twin/history?${query.toString()}`);
  }
};
