import { apiClient } from './apiClient';

export type Json = null | boolean | number | string | Json[] | { [key: string]: Json };
export interface AssistantAnswer {
  conversationId: string; turn: number; status: string; routedBy: string; orderedBy: string;
  statements: { id: string; kind: string; text: string; refs: string[] }[];
  additional: AssistantAnswer['statements'];
  sources: { ref: string; label: string; value: Json; presence: string }[];
  notices: string[]; clarification: string | null; evaluatedAt: string;
}
export interface StoredDevice { id: string; name: string; mode?: string }
export interface ModelStatus {
  mode: string;
  gateway: {
    identity: { providerId: string; modelId: string; configHash: string };
    configured: boolean; evidenceConsent: boolean; providerAttempts: number; providerResponses: number;
    lastOutcome: { outcome: string; finishedAt: string; durationMillis: number } | null;
  };
  ollama: {
    inspection: { reachable: boolean; installed: boolean; digest: string | null; remotelyHosted: boolean; problem: string | null };
    loaded: boolean | null; checkedAt: string;
  } | null;
}
export interface DraftView {
  draftId: string; state: string; valid: boolean; createdAt: string; expiresAt: string;
  validation: Json; definition: Json; evidenceRefs: Json; ruleId: string | null;
}
const enc = encodeURIComponent;
function query(values: Record<string, string | number | undefined>) {
  return new URLSearchParams(Object.entries(values).filter(([, v]) => v !== undefined && v !== '')
    .map(([k, v]) => [k, String(v)])).toString();
}
// Live API only: no demo answers, direct model calls, approval or activation routes.
export const intelligenceApi = {
  modelStatus: () => apiClient.get<ModelStatus>('/assistant/model-status'),
  ask: (question: string, conversationId?: string) => apiClient.post<AssistantAnswer>('/assistant/ask', { question, conversationId }),
  devices: () => apiClient.get<StoredDevice[]>('/devices'),
  availability: () => apiClient.get<string[]>('/assistant/evidence/lookups'),
  recommendations: (deviceId?: string) => apiClient.get<Json>(`/intelligence/recommendations?${query({ deviceId })}`),
  anomalies: (deviceId: string, capabilityKey: string, windowHours: number) => apiClient.get<Json>(`/intelligence/anomalies/devices/${enc(deviceId)}?${query({ capabilityKey, windowHours })}`),
  trend: (deviceId: string, capabilityKey: string, windowHours: number, limitValue?: number, limitUnit?: string) =>
    apiClient.get<Json>(`/intelligence/trends/devices/${enc(deviceId)}?${query({ capabilityKey, windowHours, limitValue, limitUnit })}`),
  readings: (deviceId: string) => apiClient.get<Json>(`/devices/${enc(deviceId)}/telemetry?limit=50`),
  nonMatch: (ruleId: string, deviceId?: string) => apiClient.get<Json>(`/intelligence/explanations/rules/${enc(ruleId)}/non-match?${query({ deviceId })}`),
  energy: (deviceId: string, windowHours: number) => apiClient.get<Json>(`/intelligence/energy?${query({ deviceId, windowHours })}`),
  drafts: () => apiClient.get<DraftView[]>('/intelligence/drafts'),
  propose: (goal: string) => apiClient.post<Json>('/intelligence/drafts', { goal }),
};
