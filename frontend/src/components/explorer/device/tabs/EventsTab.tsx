import { motion, AnimatePresence } from 'framer-motion';
import { Clock, AlertCircle, Info, Zap, AlertTriangle, Search } from 'lucide-react';
import { useState } from 'react';
import { Skeleton } from '../../../ui/Skeleton';
import { useRepositories } from '../../../../repositories/RepositoryProvider';
import { usePolling } from '../../../../hooks/usePolling';
import { DebugOverlay } from '../DebugOverlay';

export function EventsTab({ deviceId }: { deviceId: string }) {
  const { events } = useRepositories();
  const { data: deviceEvents = [], isLoading } = usePolling(
    ['events-history', deviceId],
    () => events.getEvents(deviceId, 100),
    { interval: 2000 }
  );
  const [filter, setFilter] = useState('ALL');

  if (isLoading) return <Skeleton className="w-full h-full" />;

  const getEventIcon = (severity: string) => {
    switch (severity) {
      case 'CRITICAL': return <AlertCircle size={16} className="text-destructive" />;
      case 'WARNING': return <AlertTriangle size={16} className="text-warning" />;
      case 'INFO': return <Info size={16} className="text-primary" />;
      default: return <Zap size={16} className="text-muted-foreground" />;
    }
  };

  const filteredEvents = deviceEvents.filter((e: any) => filter === 'ALL' || e.severity === filter);

  return (
    <div className="p-6 h-full flex flex-col space-y-6 animate-in slide-in-from-bottom-2 fade-in duration-300">
      
      {/* Header & Controls */}
      <div className="flex items-center justify-between bg-[#0b1221] backdrop-blur border border-border/30 rounded-xl p-4 shadow-sm shrink-0 relative group">
        <DebugOverlay 
          title="Event Timeline" 
          data={deviceEvents}
          repository="DataSourceResolver"
        />
        <div className="flex items-center gap-3">
          <div className="p-2 bg-blue-500/10 rounded-lg">
            <Clock size={20} className="text-blue-500" />
          </div>
          <div>
            <h3 className="font-semibold text-foreground">Event Timeline</h3>
            <p className="text-xs text-muted-foreground">Historical device events</p>
          </div>
        </div>
        
        <div className="flex items-center gap-4">
          <div className="relative group">
            <Search size={14} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-muted-foreground group-focus-within:text-blue-500 transition-colors" />
            <input 
              type="text" 
              placeholder="Search events..." 
              className="w-48 bg-[#060b14]/50 border border-border/30 rounded-md pl-8 pr-3 py-1.5 text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-blue-500/50 focus:border-blue-500/50 transition-all"
            />
          </div>
          <div className="flex bg-muted/30 rounded-md p-1 border border-border/30">
            {['ALL', 'INFO', 'WARNING', 'ERROR'].map(f => (
              <button 
                key={f}
                onClick={() => setFilter(f)}
                className={`px-3 py-1.5 text-xs font-medium rounded transition-colors ${filter === f ? 'bg-blue-500/20 text-blue-400 border border-blue-500/20' : 'text-muted-foreground hover:text-foreground'}`}
              >
                {f}
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* Timeline Feed */}
      <div className="flex-1 min-h-0 bg-[#0b1221] border border-border/30 rounded-xl overflow-hidden shadow-sm relative">
        <div className="absolute inset-0 overflow-y-auto custom-scrollbar p-6">
          <div className="relative before:absolute before:inset-0 before:ml-5 before:-translate-x-px md:before:mx-auto md:before:translate-x-0 before:h-full before:w-[2px] before:bg-gradient-to-b before:from-transparent before:via-border/30 before:to-transparent">
            
            <AnimatePresence initial={false}>
              {filteredEvents.length === 0 && (
                <div className="flex flex-col items-center justify-center py-20 text-muted-foreground gap-4">
                  <Clock size={48} className="opacity-20 animate-pulse" />
                  <div className="text-center">
                    <p className="text-sm font-medium text-foreground uppercase tracking-wider mb-1">Event Pipeline Listening</p>
                    <p className="text-xs">Connected to message broker. Waiting for incoming events.</p>
                  </div>
                  <div className="flex gap-4 text-[10px] font-mono mt-2 bg-muted/20 px-3 py-1.5 rounded border border-border/50">
                    <span>Polling: ACTIVE</span>
                    <span>Filter: {filter}</span>
                  </div>
                </div>
              )}
              {filteredEvents.map((event: any, i: number) => (
                <motion.div
                  key={event.id || `${event.timestamp}-${i}`}
                  initial={{ opacity: 0, y: -20 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={{ opacity: 0, scale: 0.95 }}
                  transition={{ duration: 0.3 }}
                  className="relative flex items-center justify-between md:justify-normal md:odd:flex-row-reverse group is-active mb-8"
                >
                  <div className="flex items-center justify-center w-10 h-10 rounded-full border-4 border-background bg-card shrink-0 md:order-1 md:group-odd:-translate-x-1/2 md:group-even:translate-x-1/2 shadow-sm z-10">
                    {getEventIcon(event.severity || 'INFO')}
                  </div>
                  
                  <div className="w-[calc(100%-4rem)] md:w-[calc(50%-2.5rem)] bg-card border border-border/50 p-4 rounded-xl shadow-sm hover:shadow-md hover:border-primary/50 transition-all">
                    <div className="flex items-center justify-between mb-2">
                      <span className="text-xs font-bold font-mono text-muted-foreground">
                        {new Date(event.timestamp).toLocaleTimeString([], { hour12: false, hour: '2-digit', minute:'2-digit', second:'2-digit' })}
                        <span className="text-[10px] text-muted-foreground/50 ml-1">.{new Date(event.timestamp).getMilliseconds()}</span>
                      </span>
                      <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full ${
                        event.severity === 'CRITICAL' ? 'bg-destructive/10 text-destructive' :
                        event.severity === 'WARNING' ? 'bg-warning/10 text-warning' :
                        'bg-primary/10 text-primary'
                      }`}>
                        {event.type || 'SYSTEM_EVENT'}
                      </span>
                    </div>
                    <p className="text-sm text-foreground/90 font-medium">
                      {event.message || `Event generated with payload: ${Object.keys(event.payload || {}).join(', ')}`}
                    </p>
                    {event.payload && Object.keys(event.payload).length > 0 && (
                      <div className="mt-3 bg-muted/30 rounded p-2 text-xs font-mono text-muted-foreground overflow-hidden text-ellipsis whitespace-nowrap">
                        {JSON.stringify(event.payload)}
                      </div>
                    )}
                  </div>
                </motion.div>
              ))}
            </AnimatePresence>
            
          </div>
        </div>
      </div>
    </div>
  );
}
