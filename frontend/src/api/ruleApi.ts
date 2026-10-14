import { apiClient } from './apiClient';
import type { CapabilityType } from './deviceApi';

export type RuleField = 'EVENT_TYPE' | 'DEVICE_ID' | 'SENSOR_TYPE' | 'VALUE' | 'SEVERITY';
export type RuleOperator = 'EQ' | 'NE' | 'GT' | 'LT' | 'GTE' | 'LTE';

/**
 * Phase 13: capabilityType is a new, optional field. Absent (undefined) on
 * every rule created before Phase 13 and on any condition the user builds
 * without picking a specific device capability - the backend treats those
 * exactly as before (legacy, event-payload-based evaluation). When present,
 * it's authoritative: the condition is evaluated against current
 * DigitalTwinRegistry state for (the rule's device, capabilityType).
 */
/** Phase 3 trigger families (contract C12). Imported rules carry no explicit subscription and use the legacy DomainEvent adapter. */
export type TriggerType =
  | 'LEGACY_DOMAIN_EVENT' | 'ACCEPTED_TELEMETRY' | 'TWIN_STATE_CHANGED' | 'CONNECTIVITY_CHANGED'
  | 'COMMAND_PUBLICATION_FAILED' | 'COMMAND_ACK_REJECTED' | 'COMMAND_ACK_TIMED_OUT'
  | 'VERIFICATION_CHANGED' | 'VERIFICATION_TIMED_OUT' | 'MANUAL' | 'SCHEDULE' | 'TEMPORAL_DUE' | 'RULE_MATCHED';

export interface TriggerSubscription {
  type: TriggerType;
  /** Supported keys: capabilityKey, connectivity (ONLINE|OFFLINE), status (CONVERGED|DIVERGED|TIMED_OUT), parentRuleId. */
  filters?: Record<string, string>;
}

export type RepeatPolicy = 'EVERY_TRIGGER_WHILE_TRUE' | 'EDGE_TRUE';
export type CooldownScope = 'GLOBAL' | 'PER_SCOPE';

export interface ScheduleSpec {
  kind: 'ONE_SHOT' | 'FIXED_INTERVAL';
  /** UTC instant (ISO-8601) for ONE_SHOT. */
  at?: string | null;
  intervalSeconds?: number | null;
  /** UTC instant (ISO-8601) anchoring FIXED_INTERVAL slots; defaults to activation time. */
  startsAt?: string | null;
}

export type TemporalOperator = 'FOR_DURATION' | 'COUNT_WITHIN' | 'ABSENCE_FOR' | 'OFFLINE_FOR' | 'TREND';

export interface TemporalSpec {
  operator: TemporalOperator;
  durationSeconds?: number | null;
  windowSeconds?: number | null;
  count?: number | null;
  direction?: 'RISING' | 'FALLING' | null;
  minChange?: number | null;
  countMode?: 'SAMPLES' | 'EDGES' | null;
}

/** Explicit numeric hysteresis: the condition's expectedValue is the ENTER threshold, `exit` the EXIT threshold (same unit). */
export interface HysteresisSpec { exit: string }

export interface RuleCondition {
  field: RuleField;
  operator: RuleOperator;
  expectedValue: string;
  capabilityType?: CapabilityType;
  capabilityKey?: string;
  unit?: string;
  /** Stable identity inside an immutable version; required for temporal conditions. */
  conditionId?: string;
  temporal?: TemporalSpec;
  hysteresis?: HysteresisSpec;
}

export interface RuleAction {
  actionType: string;
  parameters: Record<string, any>;
}

export interface Rule {
  ruleId: string;
  name: string;
  enabled: boolean;
  priority: number;
  conditions: RuleCondition[];
  actions: RuleAction[];
  metadata: Record<string, string>;
  /** Phase 13: seconds a matched rule must wait before it can match again. Undefined/null = no cooldown. */
  cooldownSeconds?: number | null;
  lifecycle?: 'DRAFT' | 'VALIDATED' | 'ACTIVE' | 'PAUSED' | 'ARCHIVED';
  activeVersionId?: string | null;
  workingVersionId?: string | null;
  workingVersionState?: 'DRAFT' | 'VALIDATED';
  rowRevision?: number;
  executionEpoch?: number;
  reconciliation?: string;
  originalEnabled?: boolean | null;
  diagnostics?: { code: string; message: string; path: string }[];
  /** Explicit trigger subscriptions; absent on imported rules (legacy DomainEvent adapter only). */
  triggers?: TriggerSubscription[];
  repeatPolicy?: RepeatPolicy;
  schedule?: ScheduleSpec;
  /** GLOBAL (default; one cooldown per logical rule) or an explicit PER_SCOPE opt-in (one per resolved device). */
  cooldownScope?: CooldownScope;
}

/** Persisted runtime facts for one rule: timers, temporal state/evidence, arbitration outcomes and matches. */
export interface RuleRuntimeSnapshot {
  timers: { timer_id: string; kind: string; due_at: string; status: string; invalidation_reason?: string | null; skipped_slots?: number; condition_id?: string | null }[];
  temporalState: { condition_id: string; operator: string; phase: string; due_at?: string | null; member_count: number; sequence: number; reset_reason?: string | null; generation: number }[];
  temporalEvidence: { seq: number; event: string; conditionId: string; occurredAt: string; details: Record<string, unknown> }[];
  candidates: { candidate_id: string; action_type: string; target_key?: string | null; priority: number; disposition: string; reason: string; outcome?: CandidateOutcome | null; outcome_reason?: string | null }[];
  matches: { match_id: string; trigger_type?: string | null; event_id?: string | null; matched_at: string }[];
}

export interface ExclusionMember { ruleId: string; rank: number }
export interface ExclusionGroup { groupId: string; name: string; members: ExclusionMember[] }

export interface ArbitrationCandidate {
  candidate_id: string; match_id: string; rule_id: string; rule_revision: number; action_ordinal: number; action_type: string; target_key?: string | null;
  priority: number; disposition: string; reason: string; winner_candidate_id?: string | null; request_id: string; outcome?: CandidateOutcome | null; outcome_reason?: string | null;
  /** ActionHistory facts shown as separate evidence, never inferred from the outcome. */
  history_status?: string | null; published_at?: string | null; ack_status?: string | null;
  parameters: Record<string, unknown>; evidence: Record<string, unknown>;
}
export interface ArbitrationEvidence {
  matchId: string;
  plans: { planId: string; status: string; decidedAt: string; source: { kind: 'FACT' | 'EVENT'; id: string }; candidates: ArbitrationCandidate[] }[];
  note: string;
}

/**
 * What guarded acceptance / the executor PROVED for a selected candidate. ACCEPTED is a command that passed guarded acceptance (publication, ACK
 * and convergence are separate facts: see history_status, published_at and ack_status). EXECUTED is a non-command executor that reported
 * success (stub executors prove nothing outside the system). A missing outcome means nothing is known.
 */
export type CandidateOutcome = 'ACCEPTED' | 'EXECUTED' | 'EXECUTION_FAILED' | 'UNSUPPORTED' | 'IGNORED' | 'DENIED';

export interface ManualExecutionResult { requestId: string; factId: string; ruleVersionId: string; note: string }

// ---------------------------------------------------------------------------------------------------------------- dry-run (C19)

export type DryRunMode = 'SUPPLIED_STATE' | 'CURRENT_STATE' | 'HISTORICAL_REPLAY';
export type DryRunValueType = 'NUMBER' | 'BOOLEAN' | 'STRING' | 'ENUM';
/** A typed JSON value: false, 0 and "" are values; null is an explicit null; a missing property is none of these. */
export type DryRunJsonValue = boolean | number | string | null;

export interface DryRunTwinValue {
  deviceId?: string; capabilityKey: string; valueType: DryRunValueType; value: DryRunJsonValue; unit: string | null;
  observedAt: string; receivedAt?: string | null; freshnessThresholdSeconds?: number | null;
}
export interface DryRunSupplied {
  twins?: DryRunTwinValue[];
  connectivity?: { deviceId: string; status: 'ONLINE' | 'OFFLINE' | 'UNKNOWN' }[];
  cooldowns?: { ruleId: string; lastMatchedAt: string }[];
  edgeLatches?: { ruleId: string; latched: boolean }[];
  hysteresisLatches?: { ruleId: string; conditionId: string; latched: boolean }[];
  temporal?: { ruleId: string; versionId?: string | null; conditionId: string; operator: string; phase: string; sequence: number; generation: number; armedAt?: string | null; dueAt?: string | null }[];
}
/** An inline, never-saved definition uses the same shape as a rule create request. */
export interface DryRunInlineDefinition {
  name: string; enabled: boolean; priority: number; conditions: RuleCondition[]; actions: RuleAction[]; metadata?: Record<string, string>;
  cooldownSeconds?: number | null; triggers?: TriggerSubscription[]; repeatPolicy?: RepeatPolicy; schedule?: ScheduleSpec; cooldownScope?: CooldownScope;
}
export interface DryRunRequest {
  mode: DryRunMode;
  scope?: { deviceId: string };
  versions: { ruleId?: string; versionId?: string; definition?: DryRunInlineDefinition; label?: string }[];
  trigger: { type: TriggerType; deviceId?: string; attributes?: Record<string, unknown>; event?: { eventType: string; severity?: string; payload?: Record<string, unknown> }; correlationId?: string };
  simulationTime?: string;
  supplied?: DryRunSupplied;
}
export interface DryRunConditionEvidence {
  field: string; operator: string; expectedValue: string | null; actualValue: unknown; conditionMet: boolean;
  capabilityKey?: string | null; valueType?: string | null; unit?: string | null; stateQuality?: string | null; qualityReason?: string | null;
  receivedAt?: string | null; freshnessThresholdSeconds?: number | null; connectivity?: string | null; eligible?: boolean | null;
  hysteresis?: { decision?: string; latchedBefore?: boolean; latchedAfter?: boolean } | null;
}
export interface DryRunVersionResult {
  simulatedVersionId: string; ruleId: string | null; versionId: string | null; revision: number; executionEpoch: number; name: string; priority: number;
  source: 'STORED_ACTIVE' | 'STORED_VERSION' | 'INLINE'; triggerEligible: boolean; outcome: string; matched: boolean; message: string; lane: number;
  conditions: DryRunConditionEvidence[]; contractDiagnostics: { code: string; message: string; path: string }[];
  cooldown: { windowSeconds: number | null; scope: string; lastMatchedAt: string | null; cooling: boolean } | null;
  repeat: { policy: string; edgeLatched: boolean } | null;
  /** Stored ownership read (NOT the production authorization check). Null for an unsaved inline definition. */
  ownership: { lifecycle: string; activeVersion: boolean; reconciliation: string | null; executableInProduction: boolean } | null;
}
export interface DryRunCandidate {
  simulatedId: string; lane: number; ruleId: string | null; versionId: string | null; ordinal: number; actionType: string; targetKey: string | null; priority: number;
  disposition: string; reason: string; winnerId: string | null; wouldBeSelected: boolean; exclusionGroups: { groupId: string; rank: number }[]; parameters: Record<string, unknown>;
  /** Always NOT_EVALUATED: SELECTED is an arbitration outcome, not a prediction that guarded acceptance would succeed. */
  acceptanceAuthorization: 'NOT_EVALUATED';
}
export interface DryRunResponse {
  schemaVersion: number; mode: DryRunMode; hypothetical: true; simulatedAt: string; timeSource: 'SUPPLIED' | 'BACKEND_CLOCK'; scopeDeviceId: string | null;
  versions: DryRunVersionResult[]; candidates: DryRunCandidate[];
  hypotheticalEffects: { kind: string; ruleId: string | null; detail: string; from: unknown; to: unknown }[];
  diagnostics: { code: string; severity: string; message: string; path: string }[]; notice: string;
}
/** A typed rejection: the backend `code` is stable (HISTORICAL_REPLAY_UNSUPPORTED, TOO_MANY_VERSIONS, MULTIPLE_SCOPES, ...). */
export interface DryRunRejection { status: number; code: string; message: string }

export const ruleApi = {
  getRules: (deviceId?: string) =>
    apiClient.get<Rule[]>(deviceId ? `/rules?deviceId=${encodeURIComponent(deviceId)}` : '/rules'),
  getRule: (id: string) => apiClient.get<Rule>(`/rules/${id}`),
  createRule: (rule: Omit<Rule, 'ruleId'>) => apiClient.post<Rule>('/rules', rule),
  updateRule: (id: string, rule: Rule) => apiClient.put<Rule>(`/rules/${id}`, { ...rule, expectedRevision: rule.rowRevision }),
  deleteRule: (id: string) => apiClient.delete(`/rules/${id}`),
  enableRule: (id: string) => apiClient.post<void>(`/rules/${id}/enable`, {}),
  disableRule: (id: string) => apiClient.post<void>(`/rules/${id}/disable`, {}),
  saveDraft: (id: string, definition: Omit<Rule, 'ruleId'>, expectedRevision: number) =>
    apiClient.post<Rule>(`/rules/${id}/draft`, { definition, expectedRevision }),
  createDraft: (definition: Omit<Rule, 'ruleId'>) => apiClient.post<Rule>('/rules/drafts', { definition, expectedRevision: 0 }),
  transition: (id: string, operation: 'validate' | 'activate' | 'pause' | 'resume' | 'archive', expectedRevision: number) =>
    apiClient.post<Rule | null>(`/rules/${id}/lifecycle/${operation}`, { expectedRevision }),
  getRuntime: (id: string) => apiClient.get<RuleRuntimeSnapshot>(`/rules/${id}/runtime`),
  /** Persisted arbitration evidence of one match: every candidate of its plan with disposition, reason, winner and what acceptance did. */
  getArbitration: (matchId: string) => apiClient.get<ArbitrationEvidence>(`/rule-matches/history/${matchId}/arbitration`),
  listExclusionGroups: () => apiClient.get<ExclusionGroup[]>('/rule-exclusion-groups'),
  createExclusionGroup: (name: string) => apiClient.post<ExclusionGroup>('/rule-exclusion-groups', { name }),
  setExclusionMember: (groupId: string, ruleId: string, rank: number) => apiClient.put<ExclusionGroup>(`/rule-exclusion-groups/${groupId}/members/${ruleId}`, { rank }),
  removeExclusionMember: (groupId: string, ruleId: string) => apiClient.delete(`/rule-exclusion-groups/${groupId}/members/${ruleId}`),
  deleteExclusionGroup: (groupId: string) => apiClient.delete(`/rule-exclusion-groups/${groupId}`),
  /** Hypothetical evaluation on the backend (production engine). Never persists, dispatches or publishes. */
  dryRun: (request: DryRunRequest) => apiClient.post<DryRunResponse>('/rules/dry-run', request),
  /** Persists a server-issued MANUAL request addressed to the active version; evaluation is a separate persisted fact. */
  executeManually: (id: string, expectedVersionId?: string | null) =>
    apiClient.post<ManualExecutionResult>(`/rules/${id}/manual-executions`, expectedVersionId ? { expectedVersionId } : {}),
};
