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
        {Object.keys(twin.reportedState ?? {}).length > 0 ? (
          Object.entries(twin.reportedState).map(([capability, state]) => (
            <div className="flex justify-between py-1 gap-3" key={capability}>
              <span className="text-muted-foreground truncate">{capability}</span>
              <span className="text-foreground font-medium truncate max-w-[120px] text-right">
                {state.value} {state.unit}
              </span>
            </div>
          ))
        ) : twin.latestSensorType ? (
          <div className="flex justify-between py-1 gap-3">
            <span className="text-muted-foreground truncate">{twin.latestSensorType}</span>
            <span className="text-foreground font-medium truncate max-w-[120px] text-right">{twin.latestValue} {twin.latestUnit}</span>
          </div>
        ) : (
          <div className="text-muted-foreground text-center py-2 text-xs">No reading available</div>
        )}
      </div>

      <div className="flex items-center gap-1.5 text-xs text-muted-foreground mt-auto pt-2 border-t border-border/50">
        <Clock size={12} />
        <span>Seen {twin.lastSeen ? new Date(twin.lastSeen).toLocaleTimeString() : 'Never'}</span>
      </div>
    </div>
  );
}
