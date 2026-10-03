import { apiClient } from './apiClient';
import type { DeviceTemplate, TemplateCapability } from './frameworkTypes';

export const deviceTemplateApi = {
  all: () => apiClient.get<DeviceTemplate[]>('/device-templates'),
  get: (id: string) => apiClient.get<DeviceTemplate>(`/device-templates/${id}`),
  create: (data: Omit<DeviceTemplate, 'id' | 'version' | 'compositionLocked'>) => apiClient.post<DeviceTemplate>('/device-templates', data),
  update: (id: string, data: Omit<DeviceTemplate, 'id' | 'key' | 'protocolType' | 'version' | 'compositionLocked'>) =>
    apiClient.put<DeviceTemplate>(`/device-templates/${id}`, data),
  capabilities: (id: string) => apiClient.get<TemplateCapability[]>(`/device-templates/${id}/capabilities`),
  addCapability: (id: string, data: Omit<TemplateCapability, 'id' | 'capabilityKey'>) =>
    apiClient.post<TemplateCapability>(`/device-templates/${id}/capabilities`, data),
  updateCapability: (id: string, associationId: string, data: Omit<TemplateCapability, 'id' | 'capabilityKey'>) =>
    apiClient.put<TemplateCapability>(`/device-templates/${id}/capabilities/${associationId}`, data),
};
