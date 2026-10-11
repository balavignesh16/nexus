import { apiClient } from './apiClient';

export interface ActionHistoryResponse {
  executionId: string;
  historyId?: string;
  requestId: string;
  matchId: string | null;
  deviceId: string | null;
  actionType: string;
  status: string;
  message: string;
  timestamp: string;
  parameters: Record<string, unknown>;
  correlationId?: string;
  ackStatus?: 'ACCEPTED' | 'REJECTED' | null;
  ackReceivedAt?: string | null;
  ackMessage?: string | null;
  executionStatus?: string;
  commandStatus?: 'ACCEPTED_FOR_DISPATCH' | 'PUBLISHED' | 'ACKNOWLEDGED' | 'REJECTED' | 'FAILED' | 'TIMED_OUT' | null;
  publishedAt?: string | null;
  ackBackendReceivedAt?: string | null;
  ackDeviceTimestamp?: string | null;
  ackDeadlineAt?: string | null;
}

export const actionApi = {
  getActionHistory: (limit: number = 100) => apiClient.get<ActionHistoryResponse[]>(`/actions/history?limit=${limit}`)
};
