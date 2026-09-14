import { apiClient } from './apiClient';

export type ClaimTier = 'FACT' | 'INFERENCE' | 'UNKNOWN';

/**
 * One evidence-grounded (or explicitly ungrounded) claim within a rule-match
 * explanation. Mirrors the backend Claim record exactly - see
 * com.nexus.rule.ai.Claim and GroundingChecker for the shape rules enforced
 * server-side (FACT requires evidenceRef+assertedValue; INFERENCE requires
 * supportingEvidenceRefs; UNKNOWN requires none of the above).
 */
export interface Claim {
  tier: ClaimTier;
  text: string;
  evidenceRef: string | null;
  assertedValue: unknown;
  supportingEvidenceRefs: string[] | null;
}

export type GeneratedBy = 'AI' | 'DETERMINISTIC_FALLBACK';

/**
 * Mirrors com.nexus.rule.ai.api.dto.RuleMatchExplanationResponse exactly.
 * `generatedBy` is the sole, authoritative signal distinguishing genuine
 * AI-provider output from the deterministic non-AI fallback - the frontend
 * must never infer this from anything else.
 */
export interface RuleMatchExplanation {
  matchId: string;
  summary: string;
  claims: Claim[];
  actionNote: string | null;
  generatedBy: GeneratedBy;
}

/**
 * Mirrors com.nexus.rule.api.dto.RuleMatchSummaryResponse - the durable,
 * list-view shape used to let a user pick a real rule match to explain.
 * Deliberately NOT sourced from the realtime RuleMatched WebSocket event,
 * which does not carry matchId.
 */
export interface RuleMatchSummary {
  matchId: string;
  ruleId: string;
  ruleName: string | null;
  matchedAt: string;
  correlationId: string | null;
  message: string | null;
}

/**
 * Mirrors com.nexus.action.api.dto.ActionHistoryResponse - the real action
 * triggered by a rule match (Phase 3), via the already-persisted
 * ActionHistoryRecord.matchId FK. ackStatus is null for NO_ACK_YET, never
 * a synthesized value.
 */
export interface MatchAction {
  executionId: string;
  requestId: string;
  matchId: string | null;
  deviceId: string | null;
  actionType: string;
  status: string;
  timestamp: string;
  message: string | null;
  ackStatus: 'ACCEPTED' | 'REJECTED' | null;
  ackReceivedAt: string | null;
  ackMessage: string | null;
}

export const aiApi = {
  getRuleMatchHistory: (limit = 50, ruleId?: string, correlationId?: string, deviceId?: string) => {
    let url = `/rule-matches/history?limit=${limit}`;
    if (ruleId) url += `&ruleId=${encodeURIComponent(ruleId)}`;
    if (correlationId) url += `&correlationId=${encodeURIComponent(correlationId)}`;
    if (deviceId) url += `&deviceId=${encodeURIComponent(deviceId)}`;
    return apiClient.get<RuleMatchSummary[]>(url);
  },

  explainRuleMatch: (matchId: string) =>
    apiClient.get<RuleMatchExplanation>(`/ai/explanations/rule-match/${matchId}`),

  // Phase 3: the real actions this rule match actually triggered.
  getActionsForMatch: (matchId: string) =>
    apiClient.get<MatchAction[]>(`/actions/history?matchId=${encodeURIComponent(matchId)}`),
};
