import { apiClient } from './apiClient';

export interface DigitalTwinResponse {
  deviceId: string;
  metadata: Record<string, string>;
  runtimeState: Record<string, unknown>;
  lastSeenAt: string;
  quality: string;
}

export const twinApi = {
  getLatestTwins: (limit: number = 100) => 
    apiClient.get<DigitalTwinResponse[]>(`/twins?limit=${limit}`),
  
  getDeviceTwin: (deviceId: string) => 
    apiClient.get<DigitalTwinResponse>(`/devices/${deviceId}/twin`)
};
