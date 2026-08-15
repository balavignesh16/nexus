import { apiClient } from './apiClient';

export type RuleField = 'EVENT_TYPE' | 'DEVICE_ID' | 'SENSOR_TYPE' | 'VALUE' | 'SEVERITY';
export type RuleOperator = 'EQ' | 'NE' | 'GT' | 'LT' | 'GTE' | 'LTE';

export interface RuleCondition {
  field: RuleField;
  operator: RuleOperator;
  expectedValue: string;
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
}

export const ruleApi = {
  getRules: () => apiClient.get<Rule[]>('/rules'),
  getRule: (id: string) => apiClient.get<Rule>(`/rules/${id}`),
  createRule: (rule: Omit<Rule, 'ruleId'>) => apiClient.post<Rule>('/rules', rule),
  updateRule: (id: string, rule: Rule) => apiClient.put<Rule>(`/rules/${id}`, rule),
  deleteRule: (id: string) => apiClient.delete(`/rules/${id}`),
};
