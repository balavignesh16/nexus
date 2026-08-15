import { apiClient } from './apiClient';

export interface ActionHistoryResponse {
  historyId: string;
  requestId: string;
  ruleId: string;
  deviceId: string;
  actionType: string;
  status: string;
  message: string;
  timestamp: string;
  parameters: Record<string, unknown>;
  correlationId: string;
}

export const actionApi = {
  getActionHistory: (limit: number = 100) => apiClient.get<ActionHistoryResponse[]>(`/actions/history?limit=${limit}`)
};
