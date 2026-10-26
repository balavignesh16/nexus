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
  /** Additive (Phase 4): the outbox delivery state, absent for a command without a delivery record. */
  deliveryState?: string;
  deliveryReason?: string;
}

export interface LifecycleFact {
  sequence: number;
  fact: string;
  fromState?: string;
  toState?: string;
  attempt: number;
  reason?: string;
  source: string;
  occurredAt?: string;
}

/** GET /actions/{id}/lifecycle: read-only, persisted facts and pure derivations of them. */
export interface CommandLifecycle {
  commandId: string;
  deviceId?: string;
  matchId?: string;
  actionType: string;
  executionStatus?: string;
  delivery?: {
    state: string;
    attemptCount: number;
    maxAttempts: number;
    terminalReason?: string;
    lastErrorCode?: string;
    acceptedAt?: string;
    firstPublishConfirmedAt?: string;
    terminalAt?: string;
  };
  ack?: { status: string; timedOut: boolean; deadlineAt?: string; backendReceivedAt?: string; timedOutAt?: string; deviceClaimedTimestamp?: string };
  convergence?: { status: string; updatedAt?: string; deadlineAt?: string };
  latency?: { acceptedToPublishedMs?: number; publishedToAckMs?: number; publishedToConvergenceMs?: number; acceptedToTerminalMs?: number };
  explanation: string[];
  facts: LifecycleFact[];
  factsTruncated: boolean;
}

export interface DeadLetter {
  commandId: string;
  deviceId?: string;
  actionType: string;
  terminalReason?: string;
  lastErrorCode?: string;
  attemptCount: number;
  maxAttempts: number;
  acceptedAt?: string;
  terminalAt?: string;
  uncertain: boolean;
  acknowledged: boolean;
}

export interface DeadLetterPage {
  items: DeadLetter[];
  nextCursor?: string;
}

export const actionApi = {
  getActionHistory: (limit: number = 100) => apiClient.get<ActionHistoryResponse[]>(`/actions/history?limit=${limit}`),
  getLifecycle: (commandId: string) => apiClient.get<CommandLifecycle>(`/actions/${encodeURIComponent(commandId)}/lifecycle`),
  getDeadLetters: (limit: number = 5, cursor?: string) =>
    apiClient.get<DeadLetterPage>(`/actions/dead-letters?limit=${limit}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`),
};
