import { apiClient } from './apiClient';
import type { DeviceContract } from './frameworkTypes';

export const deviceContractApi = {
  get: (deviceId: string) => apiClient.get<DeviceContract>(`/devices/${deviceId}/contract`),
};
