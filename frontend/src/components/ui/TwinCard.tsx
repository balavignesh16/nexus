import { Cpu, Activity, Clock } from 'lucide-react';
import type { DigitalTwinResponse } from '../../api/twinApi';
import { cn } from '../../lib/utils';

interface TwinCardProps {
  twin: DigitalTwinResponse;
}

export function TwinCard({ twin }: TwinCardProps) {
  const isHealthy = twin.quality === 'GOOD';
  
  return (
    <div className="bg-card border border-border rounded-lg p-5 flex flex-col gap-4 hover:border-primary/30 transition-colors">
      <div className="flex justify-between items-start">
        <div className="flex items-center gap-2">
          <Cpu className={cn("mt-0.5", isHealthy ? "text-primary" : "text-warning")} size={18} />
          <div>
            <h4 className="font-medium text-foreground text-sm truncate max-w-[150px]" title={twin.deviceId}>
              {twin.deviceId}
            </h4>
            <div className="text-xs text-muted-foreground flex items-center gap-1 mt-0.5">
              <Activity size={12} />
              {twin.quality}
            </div>
          </div>
        </div>
        <span className={cn(
          "text-[10px] uppercase tracking-widest font-semibold px-2 py-0.5 rounded-full",
          isHealthy ? "bg-success/10 text-success border border-success/20" : "bg-warning/10 text-warning border border-warning/20"
        )}>
          {isHealthy ? 'ONLINE' : 'STALE'}
        </span>
      </div>
      
      <div className="bg-background/50 rounded p-3 font-mono text-sm border border-border/50">
        {Object.entries(twin.runtimeState || {}).slice(0, 3).map(([key, val]) => (
          <div key={key} className="flex justify-between py-1 border-b border-border/30 last:border-0">
            <span className="text-muted-foreground">{key}</span>
            <span className="text-foreground font-medium truncate max-w-[100px] text-right">
              {typeof val === 'object' ? JSON.stringify(val) : String(val)}
            </span>
          </div>
        ))}
        {Object.keys(twin.runtimeState || {}).length === 0 && (
          <div className="text-muted-foreground text-center py-2 text-xs">No runtime state available</div>
        )}
      </div>
      
      <div className="flex items-center gap-1.5 text-xs text-muted-foreground mt-auto pt-2 border-t border-border/50">
        <Clock size={12} />
        <span>Seen {twin.lastSeenAt ? new Date(twin.lastSeenAt).toLocaleTimeString() : 'Never'}</span>
      </div>
    </div>
  );
}
