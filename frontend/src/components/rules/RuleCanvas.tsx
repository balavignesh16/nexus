import { useState, useCallback, useRef, useEffect, useMemo } from 'react';
import ReactFlow, {
  Background,
  Controls,
  MiniMap,
  addEdge,
  useNodesState,
  useEdgesState,
  type Connection,
  type Edge,
  type Node,
  type ReactFlowInstance,
  ReactFlowProvider,
  useReactFlow,
} from 'reactflow';
import 'reactflow/dist/style.css';
import './RuleStudio.css';
import { v4 as uuidv4 } from 'uuid';

import { TriggerNode } from './nodes/TriggerNode';
import { ConditionNode } from './nodes/ConditionNode';
import { ActionNode } from './nodes/ActionNode';
import { RuleInspector } from './RuleInspector';
import { RulePalette } from './RulePalette';
import { Save, Play, AlertTriangle, CheckCircle, Maximize, Network, Loader2, Workflow, Plus } from 'lucide-react';
import { validateGraph, compileGraphToRule, ruleToGraph } from '../../utils/workflowCompiler';
import { getLayoutedElements } from '../../utils/layoutEngine';
import { RuleSimulator } from './RuleSimulator';
import type { Rule } from '../../api/ruleApi';
import { describeSaveFailure, persistDefinition } from '../../utils/ruleSave';

const nodeTypes = {
  trigger: TriggerNode,
  condition: ConditionNode,
  action: ActionNode,
};

interface RuleCanvasProps {
  initialRule?: Rule | null;
  /** Called with the rule exactly as the backend returned it after a CONFIRMED save. */
  onSaved?: (rule: Rule) => void;
}

export function RuleCanvas({ initialRule, onSaved }: RuleCanvasProps) {
  const reactFlowWrapper = useRef<HTMLDivElement>(null);
  const [nodes, setNodes, onNodesChange] = useNodesState([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);
  const [reactFlowInstance, setReactFlowInstance] = useState<ReactFlowInstance | null>(null);
  const [selectedNode, setSelectedNode] = useState<Node | null>(null);
  const [validationErrors, setValidationErrors] = useState<{ nodeId?: string; message: string }[]>([]);
  const [saveSuccess, setSaveSuccess] = useState<Rule | null>(null);
  const [saving, setSaving] = useState(false);
  /** The rule as the backend last returned it: the base of the next save (its revision guards against concurrent edits). */
  const [persisted, setPersisted] = useState<Rule | null>(initialRule ?? null);
  const [isSimulatorOpen, setIsSimulatorOpen] = useState(false);
  const { setViewport, fitView } = useReactFlow();

  useEffect(() => {
    setPersisted(initialRule ?? null);
    setSaveSuccess(null);
    if (initialRule) {
      // The graph is rebuilt from the PERSISTED conditions and actions (the authority); stored layout only positions the nodes.
      const graph = ruleToGraph(initialRule);
      setNodes(graph.nodes);
      setEdges(graph.edges);
      try {
        if (initialRule.metadata?.reactFlowViewport) {
          const vp = JSON.parse(initialRule.metadata.reactFlowViewport);
          setTimeout(() => setViewport(vp), 50);
        }
      } catch { /* a malformed viewport only loses the zoom */ }
    } else {
      setNodes([]);
      setEdges([]);
    }
  }, [initialRule, setNodes, setEdges, setViewport]);

  const onLayout = useCallback(() => {
    const { nodes: layoutedNodes, edges: layoutedEdges } = getLayoutedElements(nodes, edges);
    setNodes([...layoutedNodes]);
    setEdges([...layoutedEdges]);
    setTimeout(() => {
      fitView({ duration: 800, padding: 0.2 });
    }, 100);
  }, [nodes, edges, setNodes, setEdges, fitView]);

  const onCenter = () => {
    fitView({ duration: 800, padding: 0.2 });
  };

  const onConnect = useCallback((params: Connection | Edge) => {
    setEdges((eds) => addEdge(params, eds));
    setValidationErrors([]);
    setSaveSuccess(null);
  }, [setEdges]);

  const onDragOver = useCallback((event: React.DragEvent) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
  }, []);

  const onDrop = useCallback(
    (event: React.DragEvent) => {
      event.preventDefault();

      const reactFlowBounds = reactFlowWrapper.current?.getBoundingClientRect();
      const type = event.dataTransfer.getData('application/reactflow');

      if (typeof type === 'undefined' || !type || !reactFlowInstance || !reactFlowBounds) {
        return;
      }

      const position = reactFlowInstance.project({
        x: event.clientX - reactFlowBounds.left,
        y: event.clientY - reactFlowBounds.top,
      });

      const newNode: Node = {
        id: uuidv4(),
        type,
        position,
        data: { label: `${type} node` },
      };

      setNodes((nds) => nds.concat(newNode));
      setValidationErrors([]);
      setSaveSuccess(null);
    },
    [reactFlowInstance, setNodes]
  );

  const onNodeClick = (_: React.MouseEvent, node: Node) => {
    setSelectedNode(node);
  };

  const addBlock = (type: string) => {
    if (!['trigger','condition','action'].includes(type)) return;
    const node: Node = {id:uuidv4(),type,position:{x:60+nodes.length*240,y:120},data:{label:type==='trigger'?'On event':type==='condition'?'Check a condition':'Perform an action'}};
    setNodes(current=>current.concat(node));setSelectedNode(node);setValidationErrors([]);setSaveSuccess(null);
    setTimeout(()=>fitView({duration:300,padding:.25}),60);
  };

  const onPaneClick = () => {
    setSelectedNode(null);
  };

  const updateNodeData = (id: string, data: any) => {
    setNodes((nds) =>
      nds.map((n) => {
        if (n.id === id) {
          n.data = data;
        }
        return n;
      })
    );
    if (selectedNode && selectedNode.id === id) {
      setSelectedNode({ ...selectedNode, data });
    }
    setValidationErrors([]);
    setSaveSuccess(null);
  };

  const handleValidate = () => {
    const validation = validateGraph(nodes, edges);
    setValidationErrors(validation.errors);
    
    // Highlight nodes with errors
    const errorNodeIds = new Set(validation.errors.map(e => e.nodeId).filter(Boolean));
    setNodes(nds => nds.map(n => ({
      ...n,
      className: errorNodeIds.has(n.id) ? 'ring-2 ring-destructive shadow-lg shadow-destructive/20' : ''
    })));
    
    return validation;
  };

  const handleSave = async () => {
    if (saving) return;
    const validation = handleValidate();
    if (!validation.isValid) { setSaveSuccess(null); return; }
    setSaving(true);
    setSaveSuccess(null);
    try {
      const viewport = reactFlowInstance ? reactFlowInstance.getViewport() : { x: 0, y: 0, zoom: 1 };
      const definition = compileGraphToRule(nodes, edges, viewport, persisted);
      // Success is claimed ONLY from the backend's own response; a rejection (validation, stale revision, outage) leaves no success banner.
      const saved = await persistDefinition(persisted, definition);
      setPersisted(saved);
      setSaveSuccess(saved);
      setValidationErrors([]);
      onSaved?.(saved);
    } catch (e) {
      setSaveSuccess(null);
      setValidationErrors(describeSaveFailure(e).map((message) => ({ message })));
    } finally {
      setSaving(false);
    }
  };

  /** What the simulator evaluates: the graph as drawn (unsaved), compiled by the same compiler the save uses; null while the graph is invalid. */
  const inlineDefinition = useMemo(() => {
    if (!isSimulatorOpen) return null;
    try { return compileGraphToRule(nodes, edges, { x: 0, y: 0, zoom: 1 }, persisted); } catch { return null; }
  }, [isSimulatorOpen, nodes, edges, persisted]);

  return (
    <div className="rule-studio flex-1 flex flex-col h-full min-h-0 overflow-hidden relative">
      
      {/* Toolbar */}
      <div className="studio-toolbar min-h-20 border-b border-border bg-card shrink-0 flex flex-wrap gap-3 items-center justify-between px-5 py-3">
        <div className="flex items-center gap-4">
          <div><h1 className="font-semibold text-foreground text-lg flex items-center gap-2"><Workflow size={19} className="text-primary"/>Visual Rule Builder</h1><p className="text-xs text-muted-foreground mt-1">Event → Conditions → Actions</p></div>
          <div className="px-2 py-0.5 rounded text-xs font-mono bg-muted/50 border border-border text-muted-foreground">
            {persisted ? (persisted.lifecycle ?? (persisted.enabled ? 'ACTIVE' : 'SAVED')) : 'UNSAVED'}
          </div>
          
          {validationErrors.length > 0 && (
            <div className="flex items-center gap-1.5 text-xs text-destructive bg-destructive/10 px-2 py-1 rounded">
              <AlertTriangle size={12} /> {validationErrors.length} validation error(s)
            </div>
          )}
          {saveSuccess && (
            <div className="flex items-center gap-1.5 text-xs text-success bg-success/10 px-2 py-1 rounded" role="status">
              <CheckCircle size={12} /> Saved by the backend{saveSuccess.rowRevision !== undefined ? ` (revision ${saveSuccess.rowRevision})` : ''}
            </div>
          )}
        </div>
        
        <div className="flex items-center gap-2">
          {/* Editor Controls */}
          <div className="flex items-center gap-1 border-r border-border pr-2 mr-1">
            <button onClick={onLayout} title="Auto Layout" aria-label="Arrange blocks" className="p-1.5 rounded hover:bg-muted text-muted-foreground hover:text-foreground transition-colors">
              <Network size={16} />
            </button>
            <button onClick={onCenter} title="Center Graph" aria-label="Fit canvas" className="p-1.5 rounded hover:bg-muted text-muted-foreground hover:text-foreground transition-colors">
              <Maximize size={16} />
            </button>
          </div>

          <button 
            onClick={handleValidate}
            className="flex items-center gap-2 px-3 py-1.5 rounded bg-muted hover:bg-muted/80 text-sm font-medium transition-colors text-foreground"
          >
            <AlertTriangle size={14} /> Validate
          </button>
          <button 
            onClick={() => setIsSimulatorOpen(!isSimulatorOpen)}
            className={`flex items-center gap-2 px-3 py-1.5 rounded text-sm font-medium transition-colors ${
              isSimulatorOpen ? 'bg-blue-500/20 text-blue-500 border border-blue-500/50' : 'bg-muted hover:bg-muted/80 text-foreground'
            }`}
          >
            <Play size={14} /> Simulate
          </button>
          <button 
            onClick={handleSave}
            disabled={saving}
            title="Saves the flat definition (all conditions, then all actions) through the backend. Branching graphs are not executable."
            className="flex items-center gap-2 px-3 py-1.5 rounded bg-primary hover:bg-primary/90 text-sm font-medium transition-colors text-primary-foreground"
          >
            {saving ? <Loader2 size={14} className="animate-spin" /> : <Save size={14} />} Save
          </button>
        </div>
      </div>

      {/* Workspace */}
      <div className="studio-workspace flex-1 flex">
        <RulePalette onAdd={addBlock}/>
        
        <div className="studio-canvas flex-1 flex flex-col h-full min-h-0 relative">
          <div className="flex-1 relative" ref={reactFlowWrapper}>
            <ReactFlow
              nodes={nodes}
              edges={edges}
              onNodesChange={onNodesChange}
              onEdgesChange={onEdgesChange}
              onConnect={onConnect}
              onInit={setReactFlowInstance}
              onDrop={onDrop}
              onDragOver={onDragOver}
              onNodeClick={onNodeClick}
              onPaneClick={onPaneClick}
              nodeTypes={nodeTypes}
              fitView
              className="bg-background"
            >
              <Background color="#d1cadf" gap={22} size={1}/>
              <Controls className="bg-card border-border fill-foreground" />
              <MiniMap 
                className="bg-card border-border" 
                maskColor="rgba(241,239,248,0.65)"
                nodeColor={(n) => {
                  if (n.type === 'trigger') return '#3b82f6';
                  if (n.type === 'condition') return '#f97316';
                  if (n.type === 'action') return '#22c55e';
                  return '#666';
                }} 
              />
            </ReactFlow>
            {nodes.length===0 && <div className="absolute inset-0 flex items-center justify-center pointer-events-none p-5">
              <div className="max-w-sm text-center rounded-3xl bg-card border border-border p-7 shadow-sm pointer-events-auto">
                <div className="mx-auto mb-4 h-12 w-12 rounded-2xl bg-muted flex items-center justify-center text-primary"><Workflow size={23}/></div>
                <h2 className="font-semibold text-lg text-foreground">Build your first rule</h2>
                <p className="text-sm text-muted-foreground leading-relaxed mt-2 mb-5">Start with an event, add conditions, then choose an action. Select any block to edit its properties.</p>
                <button type="button" onClick={()=>addBlock('trigger')} className="inline-flex items-center gap-2 bg-primary text-primary-foreground rounded-xl px-4 py-2.5 text-sm font-medium"><Plus size={16}/>Add event trigger</button>
                <p className="text-xs text-muted-foreground mt-4">Nothing runs until saved and separately activated.</p>
              </div>
            </div>}
            
            {/* Validation Errors Overlay */}
            {validationErrors.length > 0 && (
              <div className="absolute bottom-4 left-4 z-10 bg-card border border-destructive/50 rounded-md shadow-lg p-3 max-w-sm">
                <div className="flex items-center gap-2 text-destructive mb-2 font-semibold text-sm">
                  <AlertTriangle size={16} /> Validation Errors
                </div>
                <ul className="text-xs text-muted-foreground space-y-1 list-disc list-inside">
                  {validationErrors.map((err, idx) => (
                    <li key={idx}>{err.message}</li>
                  ))}
                </ul>
              </div>
            )}
          </div>
          
          {/* Simulator Console */}
          {isSimulatorOpen && (
            <div className="h-80 border-t border-border bg-card shrink-0">
              {inlineDefinition
                ? <RuleSimulator inlineDefinition={inlineDefinition} />
                : <div className="p-4 text-xs text-muted-foreground" role="note">Complete and validate the graph to run a backend dry-run. The dry-run evaluates the definition exactly as it would be saved.</div>}
            </div>
          )}
        </div>

        <RuleInspector selectedNode={selectedNode} onUpdateNode={updateNodeData} />
      </div>

    </div>
  );
}

export function RuleStudio({ initialRule, onSaved }: RuleCanvasProps) {
  return (
    <ReactFlowProvider>
      <RuleCanvas initialRule={initialRule} onSaved={onSaved} />
    </ReactFlowProvider>
  );
}
