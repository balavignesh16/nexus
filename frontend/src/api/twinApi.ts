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
  }>;
  effectiveState?: Record<string, {
    stateId: string; value: unknown; source: 'REPORTED' | 'DESIRED_UNVERIFIED' | 'NONE';
    observedValue: unknown; intentValue: unknown;
  }>;
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
}

export const twinApi = {
  getLatestTwins: (limit: number = 100) => 
    apiClient.get<DigitalTwinResponse[]>(`/twins?limit=${limit}`),
  
  getDeviceTwin: (deviceId: string) => 
    apiClient.get<DigitalTwinResponse>(`/devices/${deviceId}/twin`)
};
