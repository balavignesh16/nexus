import type { LucideIcon } from 'lucide-react';

export interface TimelineEvent {
  id: string;
  time: string;
  title: string;
  description: string;
  icon: LucideIcon;
  type: 'telemetry' | 'rule' | 'action';
}

interface ActivityTimelineProps {
  title: string;
  events: TimelineEvent[];
  emptyMessage?: string;
}

export function ActivityTimeline({ title, events, emptyMessage = 'No activity recorded' }: ActivityTimelineProps) {
  const getTypeStyles = (type: string) => {
    switch (type) {
      case 'telemetry': return 'text-blue-500';
      case 'rule': return 'text-amber-500';
      case 'action': return 'text-red-500';
      default: return 'text-muted-foreground';
    }
  };

  return (
    <div className="bg-[#12121a] border border-[#1f1f28] rounded-xl flex flex-col shadow-sm min-h-[300px] h-full">
      <div className="p-5 pb-0 border-b-0 shrink-0">
        <h3 className="text-sm font-semibold text-foreground">{title}</h3>
      </div>
      <div className="flex-1 overflow-hidden relative flex flex-col mt-4">
        {events.length === 0 ? (
          <div className="flex-1 flex flex-col items-center justify-center text-muted-foreground p-6 text-center">
            <span className="text-sm">{emptyMessage}</span>
          </div>
        ) : (
          <div className="absolute inset-0 overflow-y-auto custom-scrollbar px-5">
            <div className="relative before:absolute before:inset-0 before:ml-[11px] before:-translate-x-px before:h-full before:w-[2px] before:bg-gradient-to-b before:from-[#1f1f28] before:to-transparent space-y-6">
              
              {events.map((event, index) => (
                <div key={`${event.id}-${index}`} className="relative flex gap-4 group cursor-default">
                  <div className={`w-6 h-6 rounded-full bg-[#12121a] border-[3px] border-[#0a0a0f] flex items-center justify-center shrink-0 z-10 ${getTypeStyles(event.type)}`}>
                    <event.icon size={10} className="fill-current" />
                  </div>
                  <div className="flex-1 pt-0.5 pb-2">
                    <div className="flex items-center gap-2 mb-1">
                      <span className="text-[10px] font-mono text-muted-foreground font-bold">{event.time}</span>
                    </div>
                    <p className="text-xs font-semibold text-foreground">{event.title}</p>
                    <p className="text-[11px] text-muted-foreground mt-1">{event.description}</p>
                  </div>
                </div>
              ))}
              
            </div>
          </div>
        )}
        
        <div className="mt-auto p-4 text-center shrink-0 bg-[#12121a]/90 backdrop-blur border-t border-[#1f1f28]/50">
          <button className="text-[10px] text-blue-500 hover:text-blue-400 font-medium transition-colors cursor-pointer">
            View full timeline →
          </button>
        </div>
      </div>
    </div>
  );
}
