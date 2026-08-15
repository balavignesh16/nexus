import { apiClient } from './apiClient';

export interface TelemetryResponse {
  id: string;
  deviceId: string;
  payload: Record<string, unknown>;
  timestamp: string;
  correlationId: string;
}

export const telemetryApi = {
  getLatestTelemetry: (limit: number = 100) => apiClient.get<TelemetryResponse[]>(`/telemetry?limit=${limit}`),
  getDeviceTelemetry: (deviceId: string, limit: number = 50) => apiClient.get<TelemetryResponse[]>(`/devices/${deviceId}/telemetry?limit=${limit}`),
  getDeviceLatestTelemetry: (deviceId: string) => apiClient.get<TelemetryResponse>(`/devices/${deviceId}/telemetry/latest`)
};
