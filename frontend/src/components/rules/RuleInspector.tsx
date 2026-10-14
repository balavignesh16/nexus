import type { Node } from 'reactflow';
import { Settings, Info, AlertCircle, FileJson, BookOpen, AlertTriangle } from 'lucide-react';
import { ACTION_TYPES } from '../../utils/workflowCompiler';

interface RuleInspectorProps {
  selectedNode: Node | null;
  onUpdateNode: (id: string, data: any) => void;
}

const FIELDS = ['EVENT_TYPE', 'DEVICE_ID', 'SENSOR_TYPE', 'VALUE', 'SEVERITY'];
const OPERATORS = ['EQ', 'NE', 'GT', 'LT', 'GTE', 'LTE'];
const ACTIONS: string[] = [...ACTION_TYPES];

export function RuleInspector({ selectedNode, onUpdateNode }: RuleInspectorProps) {
  if (!selectedNode) {
    return (
      <div className="w-80 bg-card border-l border-border h-full shrink-0 flex flex-col items-center justify-center p-6 text-center text-muted-foreground">
        <Info size={32} className="mb-4 opacity-20" />
        <p className="text-sm">Select a node on the canvas to configure its properties.</p>
      </div>
    );
  }

  const { type, data, id } = selectedNode;

  const updateData = (key: string, value: any) => {
    onUpdateNode(id, { ...data, [key]: value });
  };

  return (
    <div className="w-80 bg-card border-l border-border h-full shrink-0 flex flex-col">
      <div className="p-4 border-b border-border flex items-center gap-2">
        <Settings size={16} className="text-muted-foreground" />
        <h2 className="font-semibold text-foreground">Node Properties</h2>
      </div>

      <div className="flex-1 overflow-y-auto p-4 space-y-6">
        
        <div className="space-y-4">
          <div className="space-y-1.5">
            <label className="text-xs font-medium text-muted-foreground">Label</label>
            <input 
              type="text" 
              value={data.label || ''} 
              onChange={(e) => updateData('label', e.target.value)}
              className="w-full bg-background border border-border rounded px-3 py-1.5 text-sm text-foreground focus:outline-none focus:border-primary"
              placeholder="e.g., Check Temp"
            />
          </div>
          
          <div className="space-y-1.5">
            <label className="text-xs font-medium text-muted-foreground">Description</label>
            <textarea 
              value={data.description || ''} 
              onChange={(e) => updateData('description', e.target.value)}
              rows={2}
              className="w-full bg-background border border-border rounded px-3 py-1.5 text-sm text-foreground focus:outline-none focus:border-primary resize-none"
              placeholder="Optional description"
            />
          </div>
        </div>

        {type === 'condition' && (
          <div className="space-y-4 pt-4 border-t border-border">
            <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Condition Config</h3>
            
            <div className="space-y-1.5">
              <label className="text-xs font-medium text-muted-foreground">Field</label>
              <select 
                value={data.field || ''}
                onChange={(e) => updateData('field', e.target.value)}
                className="w-full bg-background border border-border rounded px-3 py-1.5 text-sm text-foreground focus:outline-none focus:border-primary"
              >
                <option value="" disabled>Select field...</option>
                {FIELDS.map(f => <option key={f} value={f}>{f}</option>)}
              </select>
            </div>

            <div className="space-y-1.5">
              <label className="text-xs font-medium text-muted-foreground">Operator</label>
              <select 
                value={data.operator || ''}
                onChange={(e) => updateData('operator', e.target.value)}
                className="w-full bg-background border border-border rounded px-3 py-1.5 text-sm text-foreground focus:outline-none focus:border-primary"
              >
                <option value="" disabled>Select operator...</option>
                {OPERATORS.map(o => <option key={o} value={o}>{o}</option>)}
              </select>
            </div>

            <div className="space-y-1.5">
              <label className="text-xs font-medium text-muted-foreground">Expected Value</label>
              <input 
                type="text" 
                value={data.expectedValue ?? ''}
                onChange={(e) => updateData('expectedValue', e.target.value)}
                className="w-full bg-background border border-border rounded px-3 py-1.5 text-sm text-foreground focus:outline-none focus:border-primary"
                placeholder="e.g., 50 or CRITICAL (empty text is a valid value)"
              />
            </div>
            
            {(data.field === undefined || data.field === '' || data.operator === undefined || data.operator === '' || data.expectedValue === undefined || data.expectedValue === null) && (
              <div className="flex items-start gap-2 p-2 bg-destructive/10 text-destructive text-xs rounded border border-destructive/20 mt-2">
                <AlertTriangle size={14} className="shrink-0 mt-0.5" />
                <p>Missing required fields. This condition will fail validation.</p>
              </div>
            )}
          </div>
        )}

        {type === 'action' && (
          <div className="space-y-4 pt-4 border-t border-border">
            <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Action Config</h3>
            
            <div className="space-y-1.5">
              <label className="text-xs font-medium text-muted-foreground">Action Type</label>
              <select 
                value={data.actionType || ''}
                onChange={(e) => updateData('actionType', e.target.value)}
                className="w-full bg-background border border-border rounded px-3 py-1.5 text-sm text-foreground focus:outline-none focus:border-primary"
              >
                <option value="" disabled>Select action...</option>
                {ACTIONS.map(a => <option key={a} value={a}>{a}</option>)}
              </select>
            </div>
            
            <div className="space-y-1.5">
              <label className="text-xs font-medium text-muted-foreground">Parameters (JSON)</label>
              <textarea 
                value={data.parameters ?? '{}'}
                onChange={(e) => updateData('parameters', e.target.value)}
                rows={4}
                className="w-full bg-background border border-border rounded px-3 py-1.5 text-sm font-mono text-foreground focus:outline-none focus:border-primary resize-none"
                placeholder='{"target": "alert-channel"}'
              />
            </div>
            
            {(data.actionType === undefined || data.actionType === '') && (
              <div className="flex items-start gap-2 p-2 bg-destructive/10 text-destructive text-xs rounded border border-destructive/20 mt-2">
                <AlertTriangle size={14} className="shrink-0 mt-0.5" />
                <p>Missing action type. This action will fail validation.</p>
              </div>
            )}
          </div>
        )}

        {type === 'trigger' && (
          <div className="space-y-4 pt-4 border-t border-border">
            <div className="p-3 bg-muted/30 border border-border rounded text-xs text-muted-foreground flex gap-2">
              <AlertCircle size={14} className="shrink-0 text-blue-500" />
              <span>This workflow will execute whenever any incoming event matches the subsequent conditions.</span>
            </div>
          </div>
        )}
        
        {/* Advanced Section */}
        <div className="space-y-4 pt-4 border-t border-border">
          <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground flex items-center gap-1.5">
            <FileJson size={12} /> Raw JSON
          </h3>
          <pre className="p-2 bg-muted/50 rounded border border-border text-[10px] font-mono text-muted-foreground overflow-x-auto">
            {JSON.stringify(data, null, 2)}
          </pre>
        </div>
        
        <div className="space-y-4 pt-4 border-t border-border">
          <h3 className="text-xs font-semibold uppercase tracking-wider text-muted-foreground flex items-center gap-1.5">
            <BookOpen size={12} /> Documentation
          </h3>
          <div className="text-xs text-muted-foreground">
            <p className="mb-2">Visit the <a href="#" className="text-blue-500 hover:underline">Nexus Docs</a> for advanced configuration guides for this node type.</p>
          </div>
        </div>
      </div>
    </div>
  );
}
