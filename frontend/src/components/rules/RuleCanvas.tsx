import { useState, useCallback, useRef, useEffect } from 'react';
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
import { v4 as uuidv4 } from 'uuid';

import { TriggerNode } from './nodes/TriggerNode';
import { ConditionNode } from './nodes/ConditionNode';
import { ActionNode } from './nodes/ActionNode';
import { RuleInspector } from './RuleInspector';
import { RulePalette } from './RulePalette';
import { Save, Play, AlertTriangle, CheckCircle, Undo2, Redo2, Maximize, Network } from 'lucide-react';
import { validateGraph, compileGraphToRule } from '../../utils/workflowCompiler';
import { getLayoutedElements } from '../../utils/layoutEngine';
import { RuleSimulator } from './RuleSimulator';
import type { Rule } from '../../api/ruleApi';

const nodeTypes = {
  trigger: TriggerNode,
  condition: ConditionNode,
  action: ActionNode,
};

interface RuleCanvasProps {
  initialRule?: Rule | null;
}

export function RuleCanvas({ initialRule }: RuleCanvasProps) {
  const reactFlowWrapper = useRef<HTMLDivElement>(null);
  const [nodes, setNodes, onNodesChange] = useNodesState([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);
  const [reactFlowInstance, setReactFlowInstance] = useState<ReactFlowInstance | null>(null);
  const [selectedNode, setSelectedNode] = useState<Node | null>(null);
  const [validationErrors, setValidationErrors] = useState<{ nodeId?: string; message: string }[]>([]);
  const [saveSuccess, setSaveSuccess] = useState(false);
  const [isSimulatorOpen, setIsSimulatorOpen] = useState(false);
  const { setViewport, fitView } = useReactFlow();

  useEffect(() => {
    if (initialRule && initialRule.metadata?.reactFlowNodes && initialRule.metadata?.reactFlowEdges) {
      try {
        const parsedNodes = JSON.parse(initialRule.metadata.reactFlowNodes);
        const parsedEdges = JSON.parse(initialRule.metadata.reactFlowEdges);
        setNodes(parsedNodes);
        setEdges(parsedEdges);
        if (initialRule.metadata.reactFlowViewport) {
          const vp = JSON.parse(initialRule.metadata.reactFlowViewport);
          setTimeout(() => setViewport(vp), 50); // delay to let nodes render
        }
      } catch (e) {
        console.error('Failed to parse graph layout', e);
      }
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
    setSaveSuccess(false);
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
      setSaveSuccess(false);
    },
    [reactFlowInstance, setNodes]
  );

  const onNodeClick = (_: React.MouseEvent, node: Node) => {
    setSelectedNode(node);
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
    setSaveSuccess(false);
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
    const validation = handleValidate();
    if (!validation.isValid) {
      setSaveSuccess(false);
      return;
    }

    try {
      const viewport = reactFlowInstance ? reactFlowInstance.getViewport() : { x: 0, y: 0, zoom: 1 };
      const rule = compileGraphToRule(nodes, edges, viewport, initialRule?.ruleId);
      // Mocking API call to save
      await new Promise(r => setTimeout(r, 500));
      console.log('Saved rule:', rule);
      setValidationErrors([]);
      setSaveSuccess(true);
      setTimeout(() => setSaveSuccess(false), 3000);
    } catch (e: any) {
      setValidationErrors([{ message: e.message || 'Failed to save rule' }]);
    }
  };

  return (
    <div className="flex-1 flex flex-col h-full bg-background overflow-hidden relative">
      
      {/* Toolbar */}
      <div className="h-14 border-b border-border bg-card shrink-0 flex items-center justify-between px-6">
        <div className="flex items-center gap-4">
          <h1 className="font-semibold text-foreground text-lg">Visual Rules Studio</h1>
          <div className="px-2 py-0.5 rounded text-xs font-mono bg-muted/50 border border-border text-muted-foreground">
            DRAFT
          </div>
          
          {validationErrors.length > 0 && (
            <div className="flex items-center gap-1.5 text-xs text-destructive bg-destructive/10 px-2 py-1 rounded">
              <AlertTriangle size={12} /> {validationErrors.length} validation error(s)
            </div>
          )}
          {saveSuccess && (
            <div className="flex items-center gap-1.5 text-xs text-success bg-success/10 px-2 py-1 rounded">
              <CheckCircle size={12} /> Rule saved successfully
            </div>
          )}
        </div>
        
        <div className="flex items-center gap-2">
          {/* Editor Controls */}
          <div className="flex items-center gap-1 border-r border-border pr-2 mr-1">
            <button className="p-1.5 rounded hover:bg-muted text-muted-foreground hover:text-foreground transition-colors disabled:opacity-50" disabled>
              <Undo2 size={16} />
            </button>
            <button className="p-1.5 rounded hover:bg-muted text-muted-foreground hover:text-foreground transition-colors disabled:opacity-50" disabled>
              <Redo2 size={16} />
            </button>
            <div className="w-px h-4 bg-border mx-1" />
            <button onClick={onLayout} title="Auto Layout" className="p-1.5 rounded hover:bg-muted text-muted-foreground hover:text-foreground transition-colors">
              <Network size={16} />
            </button>
            <button onClick={onCenter} title="Center Graph" className="p-1.5 rounded hover:bg-muted text-muted-foreground hover:text-foreground transition-colors">
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
            className="flex items-center gap-2 px-3 py-1.5 rounded bg-primary hover:bg-primary/90 text-sm font-medium transition-colors text-primary-foreground"
          >
            <Save size={14} /> Save Rule
          </button>
        </div>
      </div>

      {/* Workspace */}
      <div className="flex-1 flex overflow-hidden">
        <RulePalette />
        
        <div className="flex-1 flex flex-col h-full relative">
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
              <Background color="#333" gap={16} />
              <Controls className="bg-card border-border fill-foreground" />
              <MiniMap 
                className="bg-card border-border" 
                nodeColor={(n) => {
                  if (n.type === 'trigger') return '#3b82f6';
                  if (n.type === 'condition') return '#f97316';
                  if (n.type === 'action') return '#22c55e';
                  return '#666';
                }} 
              />
            </ReactFlow>
            
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
            <div className="h-64 border-t border-border bg-card shrink-0">
              <RuleSimulator nodes={nodes} edges={edges} />
            </div>
          )}
        </div>

        <RuleInspector selectedNode={selectedNode} onUpdateNode={updateNodeData} />
      </div>

    </div>
  );
}

export function RuleStudio({ initialRule }: RuleCanvasProps) {
  return (
    <ReactFlowProvider>
      <RuleCanvas initialRule={initialRule} />
    </ReactFlowProvider>
  );
}
