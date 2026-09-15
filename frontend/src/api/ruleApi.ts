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
export interface RuleCondition {
  field: RuleField;
  operator: RuleOperator;
  expectedValue: string;
  capabilityType?: CapabilityType;
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
}

export const ruleApi = {
  getRules: (deviceId?: string) =>
    apiClient.get<Rule[]>(deviceId ? `/rules?deviceId=${encodeURIComponent(deviceId)}` : '/rules'),
  getRule: (id: string) => apiClient.get<Rule>(`/rules/${id}`),
  createRule: (rule: Omit<Rule, 'ruleId'>) => apiClient.post<Rule>('/rules', rule),
  updateRule: (id: string, rule: Rule) => apiClient.put<Rule>(`/rules/${id}`, rule),
  deleteRule: (id: string) => apiClient.delete(`/rules/${id}`),
  enableRule: (id: string) => apiClient.post<void>(`/rules/${id}/enable`, {}),
  disableRule: (id: string) => apiClient.post<void>(`/rules/${id}/disable`, {}),
};
