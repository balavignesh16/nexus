import { apiClient } from './apiClient';

// One response represents one device. reportedState retains every capability
// independently; latest* is the backwards-compatible most-recent summary.
// Field names are verified against DigitalTwinResponse.java.
export interface DigitalTwinResponse {
  deviceId: string;
  latestTimestamp: string;
  latestSensorType: string;
  latestValue: number;
  latestUnit: string;
  lastSeen: string;
  quality: string;
  metadata: Record<string, unknown>;
  reportedState: Record<string, TwinCapabilityState>;
}

export interface TwinCapabilityState {
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
