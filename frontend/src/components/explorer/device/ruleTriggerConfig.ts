import type {
  CooldownScope, RepeatPolicy, Rule, RuleCondition, ScheduleSpec, TemporalOperator, TemporalSpec, TriggerSubscription, TriggerType,
} from '../../../api/ruleApi';

/**
 * Phase 3 authoring state: explicit trigger subscriptions, repeat policy, schedule and ONE temporal condition.
 * The server's validator stays the single authority; this module only shapes input and blocks obviously incomplete
 * submissions. Nothing here is sent unless the user configured it, so legacy rule bodies are byte-for-byte unchanged.
 */
export const TRIGGER_TYPES: { type: TriggerType; label: string; filterKey?: string; filterLabel?: string; filterOptions?: string[] }[] = [
  { type: 'ACCEPTED_TELEMETRY', label: 'Accepted telemetry', filterKey: 'capabilityKey', filterLabel: 'Capability key (optional)' },
  { type: 'TWIN_STATE_CHANGED', label: 'Reported state changed', filterKey: 'capabilityKey', filterLabel: 'Capability key (optional)' },
  { type: 'CONNECTIVITY_CHANGED', label: 'Connectivity changed', filterKey: 'connectivity', filterLabel: 'Observed status', filterOptions: ['', 'ONLINE', 'OFFLINE'] },
  { type: 'COMMAND_PUBLICATION_FAILED', label: 'Command publication failed' },
  { type: 'COMMAND_ACK_REJECTED', label: 'Command ACK rejected' },
  { type: 'COMMAND_ACK_TIMED_OUT', label: 'Command ACK timed out' },
  { type: 'VERIFICATION_CHANGED', label: 'Verification changed', filterKey: 'status', filterLabel: 'Status', filterOptions: ['', 'CONVERGED', 'DIVERGED', 'TIMED_OUT'] },
  { type: 'VERIFICATION_TIMED_OUT', label: 'Verification timed out' },
  { type: 'MANUAL', label: 'Manual execution (needs a device)' },
  { type: 'SCHEDULE', label: 'Schedule (needs a device)' },
  { type: 'RULE_MATCHED', label: 'Another rule matched', filterKey: 'parentRuleId', filterLabel: 'Parent rule id' },
];

export const TEMPORAL_OPERATORS: { operator: TemporalOperator; label: string }[] = [
  { operator: 'FOR_DURATION', label: 'Condition holds for a duration' },
  { operator: 'COUNT_WITHIN', label: 'Occurs N times within a window' },
  { operator: 'ABSENCE_FOR', label: 'No accepted observation for a duration' },
  { operator: 'OFFLINE_FOR', label: 'Observed OFFLINE for a duration' },
  { operator: 'TREND', label: 'Rising/falling trend' },
];

export interface TriggerConfig {
  selected: Partial<Record<TriggerType, string>>;   // value = the single optional filter value, '' when unfiltered
  repeatPolicy: RepeatPolicy;
  cooldownScope: CooldownScope;
  schedule: { kind: 'ONE_SHOT' | 'FIXED_INTERVAL'; at: string; intervalSeconds: string; startsAt: string };
  temporal: {
    enabled: boolean; operator: TemporalOperator; durationSeconds: string; windowSeconds: string; count: string;
    direction: 'RISING' | 'FALLING'; minChange: string; countMode: 'SAMPLES' | 'EDGES';
  };
}

export function emptyTriggerConfig(): TriggerConfig {
  return {
    selected: {},
    repeatPolicy: 'EVERY_TRIGGER_WHILE_TRUE',
    cooldownScope: 'GLOBAL',
    schedule: { kind: 'ONE_SHOT', at: '', intervalSeconds: '', startsAt: '' },
    temporal: { enabled: false, operator: 'FOR_DURATION', durationSeconds: '', windowSeconds: '', count: '', direction: 'RISING', minChange: '', countMode: 'SAMPLES' },
  };
}

const FILTER_KEY: Partial<Record<TriggerType, string>> = Object.fromEntries(
  TRIGGER_TYPES.filter((t) => t.filterKey).map((t) => [t.type, t.filterKey as string]),
);

/** Reads an existing rule so editing never silently drops its subscriptions, schedule or temporal condition. */
export function triggerConfigFromRule(rule: Rule): TriggerConfig {
  const config = emptyTriggerConfig();
  for (const sub of rule.triggers ?? []) {
    const key = FILTER_KEY[sub.type];
    config.selected[sub.type] = key ? (sub.filters?.[key] ?? '') : '';
  }
  if (rule.repeatPolicy) config.repeatPolicy = rule.repeatPolicy;
  if (rule.cooldownScope) config.cooldownScope = rule.cooldownScope;
  if (rule.schedule) {
    config.schedule = {
      kind: rule.schedule.kind,
      at: rule.schedule.at ?? '',
      intervalSeconds: rule.schedule.intervalSeconds != null ? String(rule.schedule.intervalSeconds) : '',
      startsAt: rule.schedule.startsAt ?? '',
    };
  }
  const temporal = (rule.conditions ?? []).find((c) => c.temporal)?.temporal;
  if (temporal) {
    config.temporal = {
      enabled: true,
      operator: temporal.operator,
      durationSeconds: temporal.durationSeconds != null ? String(temporal.durationSeconds) : '',
      windowSeconds: temporal.windowSeconds != null ? String(temporal.windowSeconds) : '',
      count: temporal.count != null ? String(temporal.count) : '',
      direction: temporal.direction ?? 'RISING',
      minChange: temporal.minChange != null ? String(temporal.minChange) : '',
      countMode: temporal.countMode ?? 'SAMPLES',
    };
    config.selected = { TEMPORAL_DUE: '' };
  }
  return config;
}

const num = (value: string): number | null => (value.trim() === '' ? null : Number(value));

export function buildTemporalSpec(config: TriggerConfig): TemporalSpec {
  const t = config.temporal;
  switch (t.operator) {
    case 'FOR_DURATION':
    case 'ABSENCE_FOR':
    case 'OFFLINE_FOR':
      return { operator: t.operator, durationSeconds: num(t.durationSeconds) };
    case 'COUNT_WITHIN':
      return { operator: t.operator, windowSeconds: num(t.windowSeconds), count: num(t.count), countMode: t.countMode };
    case 'TREND':
      return { operator: t.operator, durationSeconds: num(t.durationSeconds), windowSeconds: num(t.windowSeconds), direction: t.direction,
        minChange: num(t.minChange), ...(t.count.trim() ? { count: num(t.count) } : {}) };
  }
}

function buildTriggers(config: TriggerConfig): TriggerSubscription[] {
  if (config.temporal.enabled) return [{ type: 'TEMPORAL_DUE' }];
  return (Object.entries(config.selected) as [TriggerType, string][]).map(([type, value]) => {
    const key = FILTER_KEY[type];
    return key && value ? { type, filters: { [key]: value } } : { type };
  });
}

function buildSchedule(config: TriggerConfig): ScheduleSpec | undefined {
  if (config.temporal.enabled || config.selected.SCHEDULE === undefined) return undefined;
  const s = config.schedule;
  return s.kind === 'ONE_SHOT'
    ? { kind: 'ONE_SHOT', at: s.at ? new Date(s.at).toISOString() : null }
    : { kind: 'FIXED_INTERVAL', intervalSeconds: num(s.intervalSeconds), ...(s.startsAt ? { startsAt: new Date(s.startsAt).toISOString() } : {}) };
}

/** Body fields to merge into the rule payload; empty when nothing beyond the legacy adapter was configured. */
export function buildTriggerFields(config: TriggerConfig): Pick<Rule, 'triggers' | 'repeatPolicy' | 'schedule' | 'cooldownScope'> {
  const triggers = buildTriggers(config);
  const fields: Pick<Rule, 'triggers' | 'repeatPolicy' | 'schedule' | 'cooldownScope'> = {};
  if (triggers.length > 0) fields.triggers = triggers;
  if (config.repeatPolicy !== 'EVERY_TRIGGER_WHILE_TRUE') fields.repeatPolicy = config.repeatPolicy;
  if (config.cooldownScope !== 'GLOBAL') fields.cooldownScope = config.cooldownScope;   // only an explicit opt-in is ever sent
  const schedule = buildSchedule(config);
  if (schedule) fields.schedule = schedule;
  return fields;
}

/** Attaches the single temporal condition: onto the first capability condition, or an OFFLINE_FOR connectivity condition. */
export function applyTemporal(conditions: RuleCondition[], config: TriggerConfig): RuleCondition[] {
  if (!config.temporal.enabled) return conditions;
  const spec = buildTemporalSpec(config);
  const stripped = conditions.map((c) => {
    if (!c.temporal) return c;
    const { temporal: _temporal, conditionId: _id, ...rest } = c; // eslint-disable-line @typescript-eslint/no-unused-vars
    return rest as RuleCondition;
  });
  if (config.temporal.operator === 'OFFLINE_FOR') {
    return [...stripped.filter((c) => !(c.field === 'VALUE' && !c.capabilityType && !c.capabilityKey)),
      { field: 'VALUE', operator: 'EQ', expectedValue: 'OFFLINE', conditionId: 'c1', temporal: spec }];
  }
  const index = stripped.findIndex((c) => c.field === 'VALUE' && (c.capabilityType || c.capabilityKey));
  if (index < 0) return stripped;
  return stripped.map((c, i) => (i === index ? { ...c, conditionId: 'c1', temporal: spec } : c));
}

/** Blocks incomplete input before the request; the server still validates authoritatively. */
export function validateTriggerConfig(config: TriggerConfig, hasDevice: boolean): string | null {
  const t = config.temporal;
  if (t.enabled) {
    if (!hasDevice) return 'Temporal conditions need a specific device.';
    const positive = (v: string) => v.trim() !== '' && Number(v) > 0;
    if (t.operator === 'COUNT_WITHIN') {
      if (!positive(t.windowSeconds) || !positive(t.count)) return 'COUNT_WITHIN needs a positive window and count.';
    } else if (!positive(t.durationSeconds)) return 'Enter a positive duration in seconds.';
    if (t.operator === 'TREND') {
      if (!positive(t.windowSeconds) || !positive(t.minChange)) return 'TREND needs a window and a positive minimum change.';
      if (Number(t.windowSeconds) < Number(t.durationSeconds)) return 'The TREND window must cover the required span.';
    }
    return null;
  }
  const selected = Object.keys(config.selected) as TriggerType[];
  if ((config.selected.MANUAL !== undefined || config.selected.SCHEDULE !== undefined) && !hasDevice) return 'MANUAL and SCHEDULE triggers need a specific device.';
  if (config.selected.SCHEDULE !== undefined) {
    const s = config.schedule;
    if (s.kind === 'ONE_SHOT' && !s.at) return 'Choose a UTC date and time for the one-shot schedule.';
    if (s.kind === 'FIXED_INTERVAL' && !(Number(s.intervalSeconds) >= 1)) return 'Enter an interval of at least one second.';
  }
  if (selected.includes('RULE_MATCHED') && !config.selected.RULE_MATCHED) return 'Name the parent rule id for the rule-matched trigger.';
  return null;
}

/** Plain-language summary of a temporal condition for read-only views. */
export function describeTemporal(c: RuleCondition): string | null {
  const t = c.temporal;
  if (!t) return null;
  switch (t.operator) {
    case 'FOR_DURATION': return `holds continuously for ${t.durationSeconds}s with fresh, GOOD-quality readings`;
    case 'COUNT_WITHIN': return `${t.count} ${t.countMode === 'EDGES' ? 'false-to-true edges' : 'qualified samples'} within ${t.windowSeconds}s`;
    case 'ABSENCE_FOR': return `no accepted observation for ${t.durationSeconds}s`;
    case 'OFFLINE_FOR': return `device observed OFFLINE for ${t.durationSeconds}s (connectivity evidence, not physical proof)`;
    case 'TREND': return `${(t.direction ?? '').toLowerCase()} by at least ${t.minChange} over ${t.durationSeconds}s (window ${t.windowSeconds}s)`;
  }
}
