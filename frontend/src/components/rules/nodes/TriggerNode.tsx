import { Handle, Position, type NodeProps } from 'reactflow';
import { Play } from 'lucide-react';

export function TriggerNode({ data, selected }: NodeProps) {
  return (
    <div className={`px-4 py-3 shadow-md rounded-md bg-card border-2 transition-colors ${selected ? 'border-primary' : 'border-border'}`}>
      <div className="flex items-center gap-2">
        <div className="w-8 h-8 rounded-full bg-blue-500/20 flex items-center justify-center text-blue-500 shrink-0">
          <Play size={14} className="ml-0.5" />
        </div>
        <div>
          <div className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Trigger</div>
          <div className="text-sm font-bold text-foreground mt-0.5">{data.label || 'On Event'}</div>
        </div>
      </div>
      <Handle type="source" position={Position.Right} className="w-3 h-3 bg-blue-500 border-2 border-background" />
    </div>
  );
}
