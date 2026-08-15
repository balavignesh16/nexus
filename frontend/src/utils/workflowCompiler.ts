import type { Node, Edge } from 'reactflow';
import type { Rule, RuleCondition, RuleAction, RuleField, RuleOperator } from '../api/ruleApi';
import { v4 as uuidv4 } from 'uuid';

export interface NodeError {
  nodeId?: string;
  message: string;
}

export interface ValidationResult {
  isValid: boolean;
  errors: NodeError[];
}

export function validateGraph(nodes: Node[], edges: Edge[]): ValidationResult {
  const errors: NodeError[] = [];
  
  if (nodes.length === 0) {
    return { isValid: false, errors: [{ message: 'Graph is empty.' }] };
  }

  const triggers = nodes.filter(n => n.type === 'trigger');
  if (triggers.length === 0) {
    errors.push({ message: 'Rule must start with a Trigger node.' });
  }
  if (triggers.length > 1) {
    errors.push({ message: 'Rule can only have one Trigger node.' });
  }

  const actions = nodes.filter(n => n.type === 'action');
  if (actions.length === 0) {
    errors.push({ message: 'Rule must have at least one Action node.' });
  }

  // Check for disconnected nodes
  const connectedNodeIds = new Set<string>();
  edges.forEach(e => {
    connectedNodeIds.add(e.source);
    connectedNodeIds.add(e.target);
  });

  nodes.forEach(n => {
    if (nodes.length > 1 && !connectedNodeIds.has(n.id)) {
      errors.push({ nodeId: n.id, message: `Node "${n.data.label || n.type}" is disconnected.` });
    }
    
    if (n.type === 'condition') {
      if (!n.data.field || !n.data.operator || !n.data.expectedValue) {
        errors.push({ nodeId: n.id, message: `Condition node "${n.data.label || 'Condition'}" is incomplete.` });
      }
    }
    if (n.type === 'action') {
      if (!n.data.actionType) {
        errors.push({ nodeId: n.id, message: `Action node "${n.data.label || 'Action'}" is incomplete.` });
      }
    }
  });

  // Cycle detection (simple DFS)
  const adjList = new Map<string, string[]>();
  nodes.forEach(n => adjList.set(n.id, []));
  edges.forEach(e => {
    if (adjList.has(e.source)) {
      adjList.get(e.source)!.push(e.target);
    }
  });

  const visited = new Set<string>();
  const recStack = new Set<string>();

  const detectCycle = (nodeId: string): boolean => {
    if (!visited.has(nodeId)) {
      visited.add(nodeId);
      recStack.add(nodeId);

      const neighbors = adjList.get(nodeId) || [];
      for (const neighbor of neighbors) {
        if (!visited.has(neighbor) && detectCycle(neighbor)) {
          return true;
        } else if (recStack.has(neighbor)) {
          return true;
        }
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

  return {
    isValid: errors.length === 0,
    errors
  };
}

export function compileGraphToRule(nodes: Node[], edges: Edge[], viewport: any, existingRuleId?: string): Rule {
  const validation = validateGraph(nodes, edges);
  if (!validation.isValid) {
    throw new Error('Graph is invalid: ' + validation.errors.join(', '));
  }

  const conditions: RuleCondition[] = nodes
    .filter(n => n.type === 'condition')
    .map(n => ({
      field: n.data.field as RuleField,
      operator: n.data.operator as RuleOperator,
      expectedValue: n.data.expectedValue
    }));

  const actions: RuleAction[] = nodes
    .filter(n => n.type === 'action')
    .map(n => {
      let params = {};
      try {
        params = JSON.parse(n.data.parameters || '{}');
      } catch (e) {
        params = { error: 'invalid json' };
      }
      return {
        actionType: n.data.actionType,
        parameters: params
      };
    });

  const metadata: Record<string, string> = {
    reactFlowNodes: JSON.stringify(nodes),
    reactFlowEdges: JSON.stringify(edges),
    reactFlowViewport: JSON.stringify(viewport),
    version: '1.0.0',
    type: 'workflow'
  };

  return {
    ruleId: existingRuleId || uuidv4(),
    name: nodes.find(n => n.type === 'trigger')?.data.label || 'New Rule',
    enabled: true,
    priority: 10,
    conditions,
    actions,
    metadata
  };
}
