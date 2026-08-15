import { Activity, Bell, Server, Settings, Zap } from 'lucide-react';
import type { EventResponse } from '../../api/eventApi';

interface EventFeedProps {
  events: EventResponse[];
}

export function EventFeed({ events }: EventFeedProps) {
  const getEventConfig = (type: string) => {
    switch(type) {
      case 'TWIN_CREATED': return { icon: Server, color: 'text-primary' };
      case 'TWIN_UPDATED': return { icon: Activity, color: 'text-success' };
      case 'RULE_MATCHED': return { icon: Bell, color: 'text-warning' };
      case 'ACTION_EXECUTED': return { icon: Zap, color: 'text-[#8b5cf6]' }; // Purple
      default: return { icon: Settings, color: 'text-muted-foreground' };
    }
  };

  return (
    <div className="flex flex-col h-full">
      <div className="overflow-y-auto flex-1 p-4 flex flex-col gap-3">
        {events.length === 0 ? (
          <div className="text-center text-muted-foreground py-10 text-sm">No events detected</div>
        ) : (
          events.map((event) => {
            const config = getEventConfig(event.eventType);
            const Icon = config.icon;
            return (
              <div key={event.eventId} className="flex gap-4 p-3 rounded-md bg-background/30 hover:bg-background/80 transition-colors border border-transparent hover:border-border">
                <div className={`mt-1 ${config.color}`}>
                  <Icon size={16} />
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex justify-between items-start mb-1">
                    <span className={`text-sm font-medium ${config.color}`}>{event.eventType}</span>
                    <span className="text-xs text-muted-foreground font-mono">{new Date(event.timestamp).toLocaleTimeString()}</span>
                  </div>
                  <div className="text-xs text-muted-foreground truncate">Device: {event.deviceId || 'System'}</div>
                  <div className="text-xs text-foreground mt-1 opacity-80 truncate">
                    Correlation: {event.correlationId}
                  </div>
                </div>
              </div>
            );
          })
        )}
      </div>
    </div>
  );
}
