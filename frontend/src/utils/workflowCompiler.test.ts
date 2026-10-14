import { describe, it, expect } from 'vitest';
import type { Edge, Node } from 'reactflow';
import type { Rule } from '../api/ruleApi';
import { compileGraphToRule, ruleToGraph, validateGraph } from './workflowCompiler';

const chain = (...nodes: Node[]): { nodes: Node[]; edges: Edge[] } => ({
  nodes, edges: nodes.slice(1).map((n, i) => ({ id: `e${i}`, source: nodes[i].id, target: n.id })),
});
const trigger = (label = 'My rule'): Node => ({ id: 't', type: 'trigger', position: { x: 0, y: 0 }, data: { label } });
const condition = (id: string, data: Record<string, unknown>): Node => ({ id, type: 'condition', position: { x: 1, y: 1 }, data });
const action = (id: string, data: Record<string, unknown> = { actionType: 'CREATE_ALERT', parameters: '{}' }): Node => ({ id, type: 'action', position: { x: 2, y: 2 }, data });

describe('workflow validation uses presence, not truthiness (scenario 61)', () => {
  it('accepts the empty string as a valid expected value and a "0" or "false" one', () => {
    for (const expectedValue of ['', '0', 'false']) {
      const g = chain(trigger(), condition('c', { field: 'VALUE', operator: 'EQ', expectedValue }), action('a'));
      expect(validateGraph(g.nodes, g.edges).errors, `expected "${expectedValue}"`).toEqual([]);
    }
  });

  it('rejects a condition whose field, operator or value is absent (undefined or null) - not merely falsy', () => {
    for (const data of [{ operator: 'EQ', expectedValue: '1' }, { field: 'VALUE', expectedValue: '1' }, { field: 'VALUE', operator: 'EQ' }, { field: 'VALUE', operator: 'EQ', expectedValue: null }]) {
      const g = chain(trigger(), condition('c', data), action('a'));
      expect(validateGraph(g.nodes, g.edges).isValid).toBe(false);
    }
  });

  it('rejects unknown fields, operators, invented action types and parameters that are not a JSON object', () => {
    const base = { field: 'VALUE', operator: 'EQ', expectedValue: '1' };
    const bad = [
      chain(trigger(), condition('c', { ...base, field: 'COLOR' }), action('a')),
      chain(trigger(), condition('c', { ...base, operator: 'LIKE' }), action('a')),
      chain(trigger(), condition('c', base), action('a', { actionType: 'SEND_ALERT', parameters: '{}' })),
      chain(trigger(), condition('c', base), action('a', { actionType: 'WEBHOOK', parameters: '{oops' })),
      chain(trigger(), condition('c', base), action('a', { actionType: 'WEBHOOK', parameters: '[1]' })),
    ];
    for (const g of bad) expect(validateGraph(g.nodes, g.edges).isValid).toBe(false);
  });

  it('requires a condition and refuses a branching, merging or mis-ordered graph instead of silently flattening it', () => {
    const noCondition = chain(trigger(), action('a'));
    expect(validateGraph(noCondition.nodes, noCondition.edges).errors.map((e) => e.message).join(' ')).toMatch(/at least one Condition/);
    const nodes = [trigger(), condition('c1', { field: 'VALUE', operator: 'EQ', expectedValue: '1' }), action('a1'), action('a2')];
    const branching: Edge[] = [{ id: '1', source: 't', target: 'c1' }, { id: '2', source: 'c1', target: 'a1' }, { id: '3', source: 'c1', target: 'a2' }];
    const result = validateGraph(nodes, branching);
    expect(result.isValid).toBe(false);
    expect(result.errors.map((e) => e.message).join(' ')).toMatch(/Branching graphs are not executable/);
    const misordered = chain(trigger(), action('a'), condition('c', { field: 'VALUE', operator: 'EQ', expectedValue: '1' }));
    expect(validateGraph(misordered.nodes, misordered.edges).errors.map((e) => e.message).join(' ')).toMatch(/trigger, conditions, then actions/);
  });
});

const persisted: Rule = {
  ruleId: 'rule-1', name: 'Persisted', enabled: true, priority: 7, metadata: { owner: 'ops' }, cooldownSeconds: 60,
  triggers: [{ type: 'ACCEPTED_TELEMETRY' }], repeatPolicy: 'EDGE_TRUE', cooldownScope: 'PER_SCOPE', lifecycle: 'ACTIVE', rowRevision: 4,
  conditions: [
    { field: 'DEVICE_ID', operator: 'EQ', expectedValue: 'dev-1' },
    { field: 'VALUE', operator: 'GT', expectedValue: '30', capabilityType: 'TEMPERATURE', unit: 'CELSIUS', conditionId: 'h1', hysteresis: { exit: '25' } },
    { field: 'VALUE', operator: 'EQ', expectedValue: '', capabilityKey: 'label' },
    { field: 'VALUE', operator: 'EQ', expectedValue: 'OFFLINE', conditionId: 'c9', temporal: { operator: 'OFFLINE_FOR', durationSeconds: 600 } },
  ],
  actions: [{ actionType: 'SEND_COMMAND', parameters: { deviceId: 'dev-2', value: false } }, { actionType: 'CREATE_ALERT', parameters: { severity: 'INFO' } }],
};

describe('graph <-> persisted definition round trip loses nothing', () => {
  it('rebuilds the graph from the persisted conditions and actions and recompiles them verbatim', () => {
    const graph = ruleToGraph(persisted);
    expect(graph.nodes.map((n) => n.type)).toEqual(['trigger', 'condition', 'condition', 'condition', 'condition', 'action', 'action']);
    expect(validateGraph(graph.nodes, graph.edges).errors).toEqual([]);
    const compiled = compileGraphToRule(graph.nodes, graph.edges, { x: 0, y: 0, zoom: 1 }, persisted);
    expect(compiled.conditions).toEqual(persisted.conditions);
    expect(compiled.actions).toEqual(persisted.actions);
    expect(compiled.conditions[2].expectedValue).toBe('');
    expect(compiled.actions[0].parameters).toEqual({ deviceId: 'dev-2', value: false });
  });

  it('carries the rule-level configuration the canvas does not edit, and never resets it', () => {
    const graph = ruleToGraph(persisted);
    const compiled = compileGraphToRule(graph.nodes, graph.edges, { x: 0, y: 0, zoom: 1 }, persisted);
    expect(compiled).toMatchObject({ name: 'Persisted', enabled: true, priority: 7, cooldownSeconds: 60, repeatPolicy: 'EDGE_TRUE', cooldownScope: 'PER_SCOPE', triggers: [{ type: 'ACCEPTED_TELEMETRY' }] });
    expect(compiled.metadata.owner).toBe('ops');
    expect(compiled.metadata.type).toBe('workflow');
  });

  it('compiles in chain order, not array order, and keeps an edited condition value', () => {
    const graph = ruleToGraph(persisted);
    const reversedArray = [graph.nodes[0], graph.nodes[2], graph.nodes[1], ...graph.nodes.slice(3)];
    const edited = reversedArray.map((n) => (n.id === 'condition-1' ? { ...n, data: { ...n.data, expectedValue: '0' } } : n));
    const compiled = compileGraphToRule(edited, graph.edges, {}, persisted);
    expect(compiled.conditions[1].expectedValue).toBe('0');
    expect(compiled.conditions.map((c) => c.conditionId ?? c.field)).toEqual(['DEVICE_ID', 'h1', 'VALUE', 'c9']);
  });

  it('refuses invalid parameters instead of saving a placeholder, and names a brand-new rule from its trigger', () => {
    const g = chain(trigger('Fresh'), condition('c', { field: 'VALUE', operator: 'EQ', expectedValue: '1' }), action('a', { actionType: 'WEBHOOK', parameters: '{"url":' }));
    expect(() => compileGraphToRule(g.nodes, g.edges, {}, null)).toThrow(/not a valid JSON object/);
    const ok = chain(trigger('Fresh'), condition('c', { field: 'VALUE', operator: 'EQ', expectedValue: '1' }), action('a'));
    const compiled = compileGraphToRule(ok.nodes, ok.edges, {}, null);
    expect(compiled).toMatchObject({ name: 'Fresh', enabled: false, priority: 10 });
    expect(compiled.actions[0].parameters).toEqual({});
  });
});
