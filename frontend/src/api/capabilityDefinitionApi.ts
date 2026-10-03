import { apiClient } from './apiClient';
import type { CapabilityDefinition, CapabilityDefinitionCreate, CapabilityDefinitionUpdate } from './frameworkTypes';

export const capabilityDefinitionApi = {
  all: () => apiClient.get<CapabilityDefinition[]>('/capability-definitions'),
  get: (id: string) => apiClient.get<CapabilityDefinition>(`/capability-definitions/${id}`),
  create: (data: CapabilityDefinitionCreate) => apiClient.post<CapabilityDefinition>('/capability-definitions', data),
  update: (id: string, data: CapabilityDefinitionUpdate) =>
    apiClient.put<CapabilityDefinition>(`/capability-definitions/${id}`, data),
};
