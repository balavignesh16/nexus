import { toEpochMillis } from './commandLifecycle';

/**
 * The one boundary between the two shapes an action takes in the UI cache (ledger P4-102, decision D5):
 *  - a row of GET /actions/history (full: requestId, status, ackStatus, deliveryState, ISO timestamp, ...);
 *  - a realtime ActionExecuted message (partial: executionId, actionType, status, executedAt in epoch milliseconds, message).
 * Before this existed the realtime row dropped its status and carried the time under a different key than the history row, so a live row
 * showed "Unknown", "NO ACK YET" for a command that had been acknowledged, and "Invalid Date". Missing fields stay missing (undefined or
 * null); nothing is invented, and an unusable timestamp becomes null instead of an invalid date.
 */
export interface NormalizedAction {
  executionId?: string;
  requestId?: string;
  matchId?: string | null;
  deviceId?: string | null;
  actionType: string;
  status?: string;
  /** ISO-8601, or null when the source had no usable time. */
  timestamp: string | null;
  message?: string;
  correlationId?: string;
  parameters?: Record<string, unknown>;
  ackStatus?: string | null;
  ackDeadlineAt?: string | null;
  ackBackendReceivedAt?: string | null;
  commandStatus?: string | null;
  executionStatus?: string;
  deliveryState?: string;
  deliveryReason?: string;
  [extra: string]: unknown;
}

function text(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}

function isoOrNull(value: unknown): string | null {
  const ms = toEpochMillis(value);
  return ms === null ? null : new Date(ms).toISOString();
}

/** Normalizes either shape (and anything else, defensively) into one object. Never throws. */
export function normalizeAction(raw: unknown): NormalizedAction {
  const r = (raw && typeof raw === 'object' ? raw : {}) as Record<string, unknown>;
  const executionId = text(r.executionId) ?? text(r.id);
  return {
    ...r,
    executionId,
    requestId: text(r.requestId),
    actionType: text(r.actionType) ?? 'UNKNOWN',
    status: text(r.status) ?? text(r.executionStatus),
    timestamp: isoOrNull(r.timestamp ?? r.executedAt),
    message: typeof r.message === 'string' ? r.message : undefined,
    deliveryState: text(r.deliveryState),
    deliveryReason: text(r.deliveryReason),
  };
}

/** The realtime ActionExecuted payload as a cache row of the same shape as a history row. */
export function actionFromRealtime(payload: Record<string, unknown>, deviceId: string | null | undefined, correlationId: string | undefined): NormalizedAction {
  const executionId = text(payload.executionId);
  return normalizeAction({
    id: executionId,
    executionId,
    type: payload.status === 'FAILURE' ? 'ACTION_FAILED' : 'ACTION_COMPLETED',
    actionType: payload.actionType,
    status: payload.status,
    deviceId: deviceId ?? null,
    timestamp: payload.executedAt,
    message: payload.message,
    correlationId,
  });
}
