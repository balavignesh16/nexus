import { Play, Filter, Zap, ArrowRightLeft, Settings, BrainCircuit, Activity } from 'lucide-react';

export function RulePalette() {
  const onDragStart = (event: React.DragEvent, nodeType: string) => {
    event.dataTransfer.setData('application/reactflow', nodeType);
    event.dataTransfer.effectAllowed = 'move';
  };

  return (
    <div className="w-64 bg-card border-r border-border flex flex-col h-full shrink-0">
      <div className="p-4 border-b border-border">
        <h2 className="font-semibold text-foreground">Nodes</h2>
        <p className="text-xs text-muted-foreground mt-1">Drag nodes to build your workflow</p>
      </div>
      
      <div className="flex-1 overflow-y-auto p-4 space-y-6">
        
        {/* Triggers */}
        <div className="space-y-3">
          <div className="text-xs font-semibold text-muted-foreground uppercase tracking-wider">Triggers</div>
          <div 
            className="flex items-center gap-3 p-3 rounded-md border border-border bg-background cursor-grab hover:border-blue-500/50 transition-colors"
            draggable
            onDragStart={(e) => onDragStart(e, 'trigger')}
          >
            <div className="w-8 h-8 rounded bg-blue-500/20 text-blue-500 flex items-center justify-center shrink-0">
              <Play size={14} className="ml-0.5" />
            </div>
            <div className="text-sm font-medium text-foreground">On Event</div>
          </div>
        </div>

        {/* Conditions */}
        <div className="space-y-3">
          <div className="text-xs font-semibold text-muted-foreground uppercase tracking-wider">Conditions</div>
          <div 
            className="flex items-center gap-3 p-3 rounded-md border border-border bg-background cursor-grab hover:border-orange-500/50 transition-colors"
            draggable
            onDragStart={(e) => onDragStart(e, 'condition')}
          >
            <div className="w-8 h-8 rounded bg-orange-500/20 text-orange-500 flex items-center justify-center shrink-0">
              <Filter size={14} />
            </div>
            <div className="text-sm font-medium text-foreground">Condition</div>
          </div>
        </div>

        {/* Logic */}
        <div className="space-y-3">
          <div className="text-xs font-semibold text-muted-foreground uppercase tracking-wider">Logic</div>
          <div className="flex items-center gap-3 p-3 rounded-md border border-border bg-muted/50 cursor-not-allowed opacity-60">
            <div className="w-8 h-8 rounded bg-muted-foreground/20 text-muted-foreground flex items-center justify-center shrink-0">
              <ArrowRightLeft size={14} />
            </div>
            <div className="text-sm font-medium text-muted-foreground">Branch (OR)</div>
          </div>
        </div>

        {/* Actions */}
        <div className="space-y-3">
          <div className="text-xs font-semibold text-muted-foreground uppercase tracking-wider">Actions</div>
          <div 
            className="flex items-center gap-3 p-3 rounded-md border border-border bg-background cursor-grab hover:border-green-500/50 transition-colors"
            draggable
            onDragStart={(e) => onDragStart(e, 'action')}
          >
            <div className="w-8 h-8 rounded bg-green-500/20 text-green-500 flex items-center justify-center shrink-0">
              <Zap size={14} />
            </div>
            <div className="text-sm font-medium text-foreground">Action</div>
          </div>
        </div>

        {/* Utilities & AI */}
        <div className="space-y-3">
          <div className="text-xs font-semibold text-muted-foreground uppercase tracking-wider">Future Extensions</div>
          
          <div className="flex items-center gap-3 p-3 rounded-md border border-border bg-muted/50 cursor-not-allowed opacity-60">
            <div className="w-8 h-8 rounded bg-muted-foreground/20 text-muted-foreground flex items-center justify-center shrink-0">
              <Settings size={14} />
            </div>
            <div className="text-sm font-medium text-muted-foreground">Data Transform</div>
          </div>
          
          <div className="flex items-center gap-3 p-3 rounded-md border border-border bg-muted/50 cursor-not-allowed opacity-60">
            <div className="w-8 h-8 rounded bg-muted-foreground/20 text-muted-foreground flex items-center justify-center shrink-0">
              <BrainCircuit size={14} />
            </div>
            <div className="text-sm font-medium text-muted-foreground">AI Inference</div>
          </div>

          <div className="flex items-center gap-3 p-3 rounded-md border border-border bg-muted/50 cursor-not-allowed opacity-60">
            <div className="w-8 h-8 rounded bg-muted-foreground/20 text-muted-foreground flex items-center justify-center shrink-0">
              <Activity size={14} />
            </div>
            <div className="text-sm font-medium text-muted-foreground">Delay (Timer)</div>
          </div>
        </div>

      </div>
    </div>
  );
}
