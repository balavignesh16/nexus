import { describe, it, expect } from 'vitest';
import type { Rule, RuleCondition } from '../../../api/ruleApi';
import {
  applyTemporal, buildTemporalSpec, buildTriggerFields, emptyTriggerConfig, triggerConfigFromRule, validateTriggerConfig,
} from './ruleTriggerConfig';

const capabilityCondition: RuleCondition = { field: 'VALUE', operator: 'GT', expectedValue: '10', capabilityType: 'TEMPERATURE' };
const deviceCondition: RuleCondition = { field: 'DEVICE_ID', operator: 'EQ', expectedValue: 'dev-1' };

describe('ruleTriggerConfig - authoring shape for explicit triggers, schedules and temporal conditions', () => {
  it('an untouched configuration adds nothing to the request, so legacy bodies are unchanged', () => {
    const config = emptyTriggerConfig();
    expect(buildTriggerFields(config)).toEqual({});
    expect(applyTemporal([deviceCondition, capabilityCondition], config)).toEqual([deviceCondition, capabilityCondition]);
    expect(validateTriggerConfig(config, false)).toBeNull();
  });

  it('builds explicit subscriptions with their single filter and a repeat policy only when non-default', () => {
    const config = emptyTriggerConfig();
    config.selected = { ACCEPTED_TELEMETRY: 'temperature', CONNECTIVITY_CHANGED: 'OFFLINE', COMMAND_ACK_REJECTED: '', VERIFICATION_CHANGED: '' };
    config.repeatPolicy = 'EDGE_TRUE';
    expect(buildTriggerFields(config)).toEqual({
      triggers: [
        { type: 'ACCEPTED_TELEMETRY', filters: { capabilityKey: 'temperature' } },
        { type: 'CONNECTIVITY_CHANGED', filters: { connectivity: 'OFFLINE' } },
        { type: 'COMMAND_ACK_REJECTED' },
        { type: 'VERIFICATION_CHANGED' },
      ],
      repeatPolicy: 'EDGE_TRUE',
    });
  });

  it('builds a UTC one-shot or fixed-interval schedule', () => {
    const config = emptyTriggerConfig();
    config.selected = { SCHEDULE: '' };
    config.schedule = { kind: 'ONE_SHOT', at: '2026-10-14T10:00', intervalSeconds: '', startsAt: '' };
    const one = buildTriggerFields(config);
    expect(one.schedule?.kind).toBe('ONE_SHOT');
    expect(one.schedule?.at).toBe(new Date('2026-10-14T10:00').toISOString());
    config.schedule = { kind: 'FIXED_INTERVAL', at: '', intervalSeconds: '60', startsAt: '' };
    expect(buildTriggerFields(config).schedule).toEqual({ kind: 'FIXED_INTERVAL', intervalSeconds: 60 });
  });

  it('a temporal condition forces the TEMPORAL_DUE subscription and carries a stable condition id', () => {
    const config = emptyTriggerConfig();
    config.selected = { ACCEPTED_TELEMETRY: '' };
    config.temporal = { ...config.temporal, enabled: true, operator: 'FOR_DURATION', durationSeconds: '60' };
    expect(buildTriggerFields(config).triggers).toEqual([{ type: 'TEMPORAL_DUE' }]);
    expect(applyTemporal([deviceCondition, capabilityCondition], config)).toEqual([
      deviceCondition,
      { ...capabilityCondition, conditionId: 'c1', temporal: { operator: 'FOR_DURATION', durationSeconds: 60 } },
    ]);
  });

  it('builds every operator shape and an OFFLINE_FOR connectivity condition without a capability', () => {
    const config = emptyTriggerConfig();
    config.temporal = { enabled: true, operator: 'COUNT_WITHIN', durationSeconds: '', windowSeconds: '60', count: '3', direction: 'RISING', minChange: '', countMode: 'EDGES' };
    expect(buildTemporalSpec(config)).toEqual({ operator: 'COUNT_WITHIN', windowSeconds: 60, count: 3, countMode: 'EDGES' });
    config.temporal = { ...config.temporal, operator: 'TREND', durationSeconds: '40', windowSeconds: '300', count: '', direction: 'FALLING', minChange: '5' };
    expect(buildTemporalSpec(config)).toEqual({ operator: 'TREND', durationSeconds: 40, windowSeconds: 300, direction: 'FALLING', minChange: 5 });
    config.temporal = { ...config.temporal, operator: 'OFFLINE_FOR', durationSeconds: '120' };
    expect(applyTemporal([deviceCondition], config)).toEqual([
      deviceCondition,
      { field: 'VALUE', operator: 'EQ', expectedValue: 'OFFLINE', conditionId: 'c1', temporal: { operator: 'OFFLINE_FOR', durationSeconds: 120 } },
    ]);
  });

  it('rejects incomplete input before the request and never claims the server accepted it', () => {
    const config = emptyTriggerConfig();
    config.temporal = { ...config.temporal, enabled: true, operator: 'FOR_DURATION', durationSeconds: '' };
    expect(validateTriggerConfig(config, true)).toMatch(/positive duration/);
    expect(validateTriggerConfig(config, false)).toMatch(/specific device/);
    config.temporal = { ...config.temporal, operator: 'TREND', durationSeconds: '100', windowSeconds: '50', minChange: '2' };
    expect(validateTriggerConfig(config, true)).toMatch(/window must cover/);
    const manual = emptyTriggerConfig();
    manual.selected = { MANUAL: '' };
    expect(validateTriggerConfig(manual, false)).toMatch(/specific device/);
    const chained = emptyTriggerConfig();
    chained.selected = { RULE_MATCHED: '' };
    expect(validateTriggerConfig(chained, true)).toMatch(/parent rule id/);
    const schedule = emptyTriggerConfig();
    schedule.selected = { SCHEDULE: '' };
    expect(validateTriggerConfig(schedule, true)).toMatch(/UTC date and time/);
  });

  it('editing an existing rule reloads its subscriptions, schedule and temporal condition so a save cannot drop them', () => {
    const rule: Rule = {
      ruleId: 'r1', name: 'Existing', enabled: true, priority: 1, metadata: {}, actions: [],
      conditions: [deviceCondition, { ...capabilityCondition, conditionId: 'c1', temporal: { operator: 'COUNT_WITHIN', windowSeconds: 60, count: 3, countMode: 'EDGES' } }],
      triggers: [{ type: 'TEMPORAL_DUE' }], repeatPolicy: 'EDGE_TRUE',
    };
    const config = triggerConfigFromRule(rule);
    expect(config.temporal).toMatchObject({ enabled: true, operator: 'COUNT_WITHIN', windowSeconds: '60', count: '3', countMode: 'EDGES' });
    expect(config.repeatPolicy).toBe('EDGE_TRUE');
    expect(applyTemporal([deviceCondition, capabilityCondition], config)[1]).toMatchObject({ conditionId: 'c1', temporal: { operator: 'COUNT_WITHIN', count: 3 } });

    const filtered = triggerConfigFromRule({ ...rule, conditions: [deviceCondition], triggers: [{ type: 'VERIFICATION_CHANGED', filters: { status: 'TIMED_OUT' } }, { type: 'MANUAL' }], repeatPolicy: undefined });
    expect(buildTriggerFields(filtered).triggers).toEqual([{ type: 'VERIFICATION_CHANGED', filters: { status: 'TIMED_OUT' } }, { type: 'MANUAL' }]);
  });
});
