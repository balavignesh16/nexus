import { apiClient } from './apiClient';

export interface ReadbackMapping {
  id: string; deviceId: string; writeBindingId: string; writeSubtargetKey: string;
  readBindingId: string; selectorKey: string; selectorPath: string | null;
  comparatorKey: string; numericTolerance: number | null; qualificationMode: 'NONE' | 'COMMAND_ECHO';
  verificationTimeoutSeconds: number; enabled: boolean; templateMappingId: string | null;
}
export interface ReadbackPolicy {
  writeId: string; subtarget: string; readId: string; selector: string;
  selectorPath: string | null; comparator: string; tolerance: number | null;
  qualification: 'NONE' | 'COMMAND_ECHO'; timeoutSeconds: number;
}
export const readbackMappingApi = {
  all: (deviceId: string) => apiClient.get<ReadbackMapping[]>(`/devices/${deviceId}/readback-mappings`),
  create: (deviceId: string, policy: ReadbackPolicy) =>
    apiClient.post<ReadbackMapping>(`/devices/${deviceId}/readback-mappings`, policy),
  disable: (deviceId: string, mappingId: string) =>
    apiClient.delete(`/devices/${deviceId}/readback-mappings/${mappingId}`),
};
