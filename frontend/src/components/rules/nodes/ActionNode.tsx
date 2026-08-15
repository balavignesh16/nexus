import { Handle, Position, type NodeProps } from 'reactflow';
import { Zap } from 'lucide-react';

export function ActionNode({ data, selected }: NodeProps) {
  return (
    <div className={`px-4 py-3 shadow-md rounded-md bg-card border-2 transition-colors ${selected ? 'border-primary' : 'border-border'}`}>
      <Handle type="target" position={Position.Left} className="w-3 h-3 bg-green-500 border-2 border-background" />
      <div className="flex items-center gap-2">
        <div className="w-8 h-8 rounded-full bg-green-500/20 flex items-center justify-center text-green-500 shrink-0">
          <Zap size={14} />
        </div>
        <div>
          <div className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Action</div>
          <div className="text-sm font-bold text-foreground mt-0.5">{data.actionType || 'Then...'}</div>
        </div>
      </div>
    </div>
  );
}
