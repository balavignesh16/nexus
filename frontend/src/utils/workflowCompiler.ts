import type { Node, Edge } from 'reactflow';
import type { Rule, RuleCondition, RuleAction, RuleField, RuleOperator } from '../api/ruleApi';

export interface NodeError {
  nodeId?: string;
  message: string;
}

export interface ValidationResult {
  isValid: boolean;
  errors: NodeError[];
}

/** The server-owned action types. The canvas never invents one. */
export const ACTION_TYPES = ['CREATE_ALERT', 'SEND_COMMAND', 'WEBHOOK'] as const;
const FIELDS: RuleField[] = ['EVENT_TYPE', 'DEVICE_ID', 'SENSOR_TYPE', 'VALUE', 'SEVERITY'];
const OPERATORS: RuleOperator[] = ['EQ', 'NE', 'GT', 'LT', 'GTE', 'LTE'];

/**
 * The condition properties a node may carry. They are copied verbatim, so capability keys, units, temporal definitions, hysteresis and
 * stable condition ids survive a load -> edit -> save round trip. (The canvas edits field/operator/expectedValue; the rest is preserved.)
 */
const CONDITION_KEYS = ['field', 'operator', 'expectedValue', 'capabilityType', 'capabilityKey', 'unit', 'conditionId', 'temporal', 'hysteresis'] as const;

/** Present means "not undefined/null": the empty string is a valid expected value, false and 0 are values. */
const present = (value: unknown): boolean => value !== undefined && value !== null;

/** Node ids of the executable chain trigger -> conditions -> actions, in edge order (not array order). */
function chainOrder(nodes: Node[], edges: Edge[]): Node[] {
  const trigger = nodes.find((n) => n.type === 'trigger');
  if (!trigger) return [];
  const next = new Map<string, string>();
  edges.forEach((e) => { if (!next.has(e.source)) next.set(e.source, e.target); });
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const ordered: Node[] = [];
  const seen = new Set<string>();
  for (let id: string | undefined = trigger.id; id && !seen.has(id); id = next.get(id)) {
    seen.add(id);
    const node = byId.get(id);
    if (node) ordered.push(node);
  }
  return ordered;
}

export function validateGraph(nodes: Node[], edges: Edge[]): ValidationResult {
  const errors: NodeError[] = [];

  if (nodes.length === 0) {
    return { isValid: false, errors: [{ message: 'Graph is empty.' }] };
  }

  const triggers = nodes.filter((n) => n.type === 'trigger');
  if (triggers.length === 0) errors.push({ message: 'Rule must start with a Trigger node.' });
  if (triggers.length > 1) errors.push({ message: 'Rule can only have one Trigger node.' });
  if (nodes.filter((n) => n.type === 'condition').length === 0) errors.push({ message: 'Rule must have at least one Condition node.' });
  if (nodes.filter((n) => n.type === 'action').length === 0) errors.push({ message: 'Rule must have at least one Action node.' });

  const connectedNodeIds = new Set<string>();
  edges.forEach((e) => { connectedNodeIds.add(e.source); connectedNodeIds.add(e.target); });

  nodes.forEach((n) => {
    if (nodes.length > 1 && !connectedNodeIds.has(n.id)) {
      errors.push({ nodeId: n.id, message: `Node "${n.data.label || n.type}" is disconnected.` });
    }
    if (n.type === 'condition') {
      // Presence, not truthiness: "" is a valid expected value; only an absent field/operator/value is incomplete.
      if (!present(n.data.field) || !present(n.data.operator) || !present(n.data.expectedValue)) {
        errors.push({ nodeId: n.id, message: `Condition node "${n.data.label || 'Condition'}" is incomplete.` });
      } else {
        if (!FIELDS.includes(n.data.field)) errors.push({ nodeId: n.id, message: `Condition node "${n.data.label || 'Condition'}" has an unknown field "${String(n.data.field)}".` });
        if (!OPERATORS.includes(n.data.operator)) errors.push({ nodeId: n.id, message: `Condition node "${n.data.label || 'Condition'}" has an unknown operator "${String(n.data.operator)}".` });
      }
    }
    if (n.type === 'action') {
      if (!present(n.data.actionType) || n.data.actionType === '') {
        errors.push({ nodeId: n.id, message: `Action node "${n.data.label || 'Action'}" is incomplete.` });
      } else if (!(ACTION_TYPES as readonly string[]).includes(n.data.actionType)) {
        errors.push({ nodeId: n.id, message: `Action node "${n.data.label || 'Action'}" has an unsupported action type "${String(n.data.actionType)}".` });
      }
      if (present(n.data.parameters)) {
        try {
          const parsed = JSON.parse(n.data.parameters);
          if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('not an object');
        } catch {
          errors.push({ nodeId: n.id, message: `Action node "${n.data.label || 'Action'}" has parameters that are not a valid JSON object.` });
        }
      }
    }
  });

  // The executable model is a flat definition: ALL conditions (AND, in order) then ALL actions. Branching or merging edges would imply a
  // different execution that the engine does not have, so they are refused rather than silently flattened.
  const out = new Map<string, number>();
  const into = new Map<string, number>();
  edges.forEach((e) => { out.set(e.source, (out.get(e.source) ?? 0) + 1); into.set(e.target, (into.get(e.target) ?? 0) + 1); });
  nodes.forEach((n) => {
    if ((out.get(n.id) ?? 0) > 1 || (into.get(n.id) ?? 0) > 1) {
      errors.push({ nodeId: n.id, message: `Node "${n.data.label || n.type}" branches or merges. Branching graphs are not executable; connect the nodes as a single chain (trigger, conditions, then actions).` });
    }
  });

  // Cycle detection (simple DFS)
  const adjList = new Map<string, string[]>();
  nodes.forEach((n) => adjList.set(n.id, []));
  edges.forEach((e) => { if (adjList.has(e.source)) adjList.get(e.source)!.push(e.target); });
  const visited = new Set<string>();
  const recStack = new Set<string>();
  const detectCycle = (nodeId: string): boolean => {
    if (!visited.has(nodeId)) {
      visited.add(nodeId);
      recStack.add(nodeId);
      for (const neighbor of adjList.get(nodeId) || []) {
        if (!visited.has(neighbor) && detectCycle(neighbor)) return true;
        else if (recStack.has(neighbor)) return true;
      }
    }
    recStack.delete(nodeId);
    return false;
  };
  for (const n of nodes) {
    if (detectCycle(n.id)) {
      errors.push({ nodeId: n.id, message: 'Rule contains a cycle. Execution path must be a Directed Acyclic Graph (DAG).' });
      break;
    }
  }

  // The chain must run trigger -> conditions -> actions (an action before a condition has no meaning in a flat definition).
  if (errors.length === 0) {
    const order = chainOrder(nodes, edges).map((n) => n.type);
    const firstAction = order.indexOf('action');
    if (order[0] !== 'trigger' || (firstAction >= 0 && order.slice(firstAction).includes('condition')))
      errors.push({ message: 'Connect the nodes in the order trigger, conditions, then actions.' });
  }

  return { isValid: errors.length === 0, errors };
}

/** Copies a persisted condition into node data without dropping or defaulting anything (false, 0 and "" stay as they are). */
function conditionToData(condition: RuleCondition): Record<string, unknown> {
  const data: Record<string, unknown> = { label: `${condition.field} ${condition.operator}` };
  for (const key of CONDITION_KEYS) { const value = (condition as unknown as Record<string, unknown>)[key]; if (value !== undefined) data[key] = value; }
  return data;
}

/**
 * Builds the visual graph from the PERSISTED definition (the authority), never from stale layout metadata. Saved layout positions are reused
 * only when the node ids still match. Every condition and action, including unsupported-by-the-editor properties, is carried in node data.
 */
export function ruleToGraph(rule: Rule): { nodes: Node[]; edges: Edge[] } {
  let saved: Record<string, { x: number; y: number }> = {};
  try {
    const layout = rule.metadata?.reactFlowLayout ?? rule.metadata?.reactFlowNodes;
    if (layout) for (const n of JSON.parse(layout) as { id: string; position?: { x: number; y: number } }[]) if (n.position) saved[n.id] = n.position;
  } catch { saved = {}; }
  const nodes: Node[] = [];
  const place = (id: string, index: number) => saved[id] ?? { x: index * 280, y: 80 };
  nodes.push({ id: 'trigger', type: 'trigger', position: place('trigger', 0), data: { label: rule.name } });
  rule.conditions.forEach((c, i) => nodes.push({ id: `condition-${i}`, type: 'condition', position: place(`condition-${i}`, i + 1), data: conditionToData(c) }));
  rule.actions.forEach((a, i) => nodes.push({
    id: `action-${i}`, type: 'action', position: place(`action-${i}`, rule.conditions.length + i + 1),
    data: { label: a.actionType, actionType: a.actionType, parameters: JSON.stringify(a.parameters ?? {}, null, 2) },
  }));
  const edges: Edge[] = [];
  for (let i = 1; i < nodes.length; i++) edges.push({ id: `edge-${i}`, source: nodes[i - 1].id, target: nodes[i].id });
  return { nodes, edges };
}

export interface CompiledDefinition {
  name: string;
  enabled: boolean;
  priority: number;
  conditions: RuleCondition[];
  actions: RuleAction[];
  metadata: Record<string, string>;
  cooldownSeconds?: number | null;
  triggers?: Rule['triggers'];
  repeatPolicy?: Rule['repeatPolicy'];
  schedule?: Rule['schedule'];
  cooldownScope?: Rule['cooldownScope'];
}

/**
 * Compiles the chain into the flat definition the backend executes. Rule-level configuration the canvas does not edit (triggers, repeat
 * policy, cooldown, schedule, priority, enabled) is carried over from {@code base} so saving never silently resets it. Invalid input is
 * rejected, never replaced by a placeholder.
 */
export function compileGraphToRule(nodes: Node[], edges: Edge[], viewport: unknown, base?: Rule | null): CompiledDefinition {
  const validation = validateGraph(nodes, edges);
  if (!validation.isValid) throw new Error('Graph is invalid: ' + validation.errors.map((e) => e.message).join(' '));
  const ordered = nodes.length === 1 ? nodes : chainOrder(nodes, edges);

  const conditions: RuleCondition[] = ordered.filter((n) => n.type === 'condition').map((n) => {
    const condition: Record<string, unknown> = {};
    for (const key of CONDITION_KEYS) if (n.data[key] !== undefined) condition[key] = n.data[key];
    return condition as unknown as RuleCondition;
  });
  const actions: RuleAction[] = ordered.filter((n) => n.type === 'action').map((n) => ({
    actionType: n.data.actionType as string,
    parameters: present(n.data.parameters) ? JSON.parse(n.data.parameters) : {},
  }));

  const metadata: Record<string, string> = { ...(base?.metadata ?? {}) };
  delete metadata.reactFlowNodes; delete metadata.reactFlowEdges;
  metadata.reactFlowLayout = JSON.stringify(nodes.map((n) => ({ id: n.id, position: n.position })));
  metadata.reactFlowViewport = JSON.stringify(viewport);
  metadata.version = '1.0.0';
  metadata.type = 'workflow';

  const trigger = nodes.find((n) => n.type === 'trigger');
  const definition: CompiledDefinition = {
    name: typeof trigger?.data.label === 'string' && trigger.data.label.trim() !== '' ? trigger.data.label : (base?.name ?? 'New Rule'),
    enabled: base?.enabled ?? false,
    priority: base?.priority ?? 10,
    conditions, actions, metadata,
  };
  if (base) {
    if (base.cooldownSeconds !== undefined) definition.cooldownSeconds = base.cooldownSeconds;
    if (base.triggers !== undefined) definition.triggers = base.triggers;
    if (base.repeatPolicy !== undefined) definition.repeatPolicy = base.repeatPolicy;
    if (base.schedule !== undefined) definition.schedule = base.schedule;
    if (base.cooldownScope !== undefined) definition.cooldownScope = base.cooldownScope;
  }
  return definition;
}
