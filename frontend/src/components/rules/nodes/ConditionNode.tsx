import { Handle, Position, type NodeProps } from 'reactflow';
import { Filter } from 'lucide-react';

export function ConditionNode({ data, selected }: NodeProps) {
  return (
    <div className={`px-4 py-3 shadow-md rounded-md bg-card border-2 transition-colors ${selected ? 'border-primary' : 'border-border'}`}>
      <Handle type="target" position={Position.Left} className="w-3 h-3 bg-orange-500 border-2 border-background" />
      <div className="flex items-center gap-2">
        <div className="w-8 h-8 rounded-full bg-orange-500/20 flex items-center justify-center text-orange-500 shrink-0">
          <Filter size={14} />
        </div>
        <div>
          <div className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Condition</div>
          <div className="text-sm font-bold text-foreground mt-0.5">
            {data.field ? `${data.field} ${data.operator} ${data.expectedValue}` : 'If...'}
          </div>
        </div>
      </div>
      <Handle type="source" position={Position.Right} className="w-3 h-3 bg-orange-500 border-2 border-background" />
    </div>
  );
}
