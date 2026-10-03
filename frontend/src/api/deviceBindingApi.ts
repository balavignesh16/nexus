import { apiClient } from './apiClient';
import type { BindingOverride, DeviceBinding } from './frameworkTypes';

export const deviceBindingApi = {
  all: (deviceId: string) => apiClient.get<DeviceBinding[]>(`/devices/${deviceId}/capability-bindings`),
  create: (deviceId: string, capabilityDefinitionId: string, data: BindingOverride) =>
    apiClient.post<DeviceBinding>(`/devices/${deviceId}/capability-bindings`, { capabilityDefinitionId, ...data }),
  update: (deviceId: string, bindingId: string, data: BindingOverride) =>
    apiClient.put<DeviceBinding>(`/devices/${deviceId}/capability-bindings/${bindingId}`, data),
};
