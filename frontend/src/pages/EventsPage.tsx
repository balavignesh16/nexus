import { useState, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useRepositories } from '../repositories/RepositoryProvider';
import { usePolling } from '../hooks/usePolling';
import { Zap, Search, Filter, AlertTriangle, AlertCircle, Info, Hash } from 'lucide-react';
import { Skeleton } from '../components/ui/Skeleton';
import { motion, AnimatePresence } from 'framer-motion';
import { useInspector } from '../components/ui/InspectorProvider';

export default function EventsPage() {
  const { events } = useRepositories();
  const { data: globalEvents = [], isLoading } = usePolling(
    ['global-events-history'],
    () => events.getEvents(undefined, 100),
    { interval: 3000 }
  );

  const [search, setSearch] = useState('');
  const [filterSeverity, setFilterSeverity] = useState('ALL');
  const { openInspector } = useInspector();
  const navigate = useNavigate();

  const filteredEvents = useMemo(() => {
    return [...globalEvents].filter((e: any) => {
      const matchSearch = (e.type || '').toLowerCase().includes(search.toLowerCase()) || 
                          (e.deviceId || '').toLowerCase().includes(search.toLowerCase());
      const matchSeverity = filterSeverity === 'ALL' || e.severity === filterSeverity;
      return matchSearch && matchSeverity;
    });
  }, [globalEvents, search, filterSeverity]);

  if (isLoading && globalEvents.length === 0) {
    return (
      <div className="p-8 h-full flex flex-col gap-4">
        <Skeleton className="w-1/3 h-10 rounded" />
        <Skeleton className="w-full h-full rounded-xl" />
      </div>
    );
  }

  const getEventIcon = (severity: string) => {
    switch (severity) {
      case 'CRITICAL': return <AlertCircle size={16} className="text-destructive" />;
      case 'WARNING': return <AlertTriangle size={16} className="text-warning" />;
      default: return <Info size={16} className="text-blue-400" />;
    }
  };

  return (
    <div className="flex-1 flex flex-col h-full bg-[#060b14] overflow-hidden text-foreground animate-in fade-in duration-300">
      
      {/* TOOLBAR */}
      <div className="shrink-0 h-16 border-b border-[#1f1f28] flex items-center justify-between px-6 bg-[#0a0a0f]">
        <div className="flex items-center gap-3">
          <div className="p-2 bg-blue-500/10 rounded-lg">
            <Zap size={20} className="text-blue-400" />
          </div>
          <div>
            <h1 className="text-lg font-bold">Event Operations Center</h1>
            <p className="text-xs text-muted-foreground">Global event stream & correlation</p>
          </div>
        </div>

        <div className="flex items-center gap-4">
          <div className="relative">
            <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
            <input 
              type="text" 
              placeholder="Search events..."
              value={search}
              onChange={e => setSearch(e.target.value)}
              className="pl-9 pr-4 py-1.5 bg-[#13131a] border border-[#1f1f28] rounded-md text-sm focus:outline-none focus:border-blue-500 transition-colors w-64"
            />
          </div>
          
          <select 
            className="bg-[#13131a] border border-[#1f1f28] rounded-md text-sm px-3 py-1.5 focus:outline-none focus:border-blue-500"
            value={filterSeverity}
            onChange={e => setFilterSeverity(e.target.value)}
          >
            <option value="ALL">All Severities</option>
            <option value="INFO">Info</option>
            <option value="WARNING">Warning</option>
            <option value="CRITICAL">Critical</option>
          </select>
          
          <button className="flex items-center gap-2 px-3 py-1.5 bg-[#13131a] border border-[#1f1f28] rounded-md text-sm text-muted-foreground hover:text-foreground">
            <Filter size={14} /> Advanced
          </button>
        </div>
      </div>

      <div className="flex-1 flex overflow-hidden">
        
        {/* TIMELINE */}
        <div className="flex-1 overflow-y-auto custom-scrollbar p-6">
          <div className="max-w-4xl mx-auto space-y-4">
            <AnimatePresence>
              {filteredEvents.length === 0 ? (
                <div className="text-center py-20 text-muted-foreground">
                  <Zap size={48} className="mx-auto opacity-20 mb-4" />
                  <p>No events found matching your criteria.</p>
                </div>
              ) : (
                filteredEvents.map((event: any, idx: number) => {
                  return (
                    <motion.div 
                      key={event.id || idx}
                      initial={{ opacity: 0, x: -10 }}
                      animate={{ opacity: 1, x: 0 }}
                      exit={{ opacity: 0 }}
                      transition={{ delay: idx * 0.05 }}
                      onClick={() => openInspector({
                        id: event.id,
                        type: 'Event',
                        title: event.type,
                        subtitle: event.deviceId ? `Source: ${event.deviceId}` : 'System Event',
                        status: event.severity,
                        statusColor: event.severity === 'CRITICAL' ? 'destructive' : event.severity === 'WARNING' ? 'warning' : 'success',
                        properties: {
                          'Correlation ID': event.correlationId || 'N/A',
                          'Source Node': event.deviceId || 'System',
                          'Timestamp': new Date(event.timestamp).toLocaleString(),
                          'Severity': event.severity
                        },
                        rawJson: event,
                        correlationId: event.correlationId,
                        // No real per-event history source exists (Phase 3) -
                        // omitted rather than fabricated. The real
                        // correlation/pipeline trace is reachable via the
                        // "View Trace" link below, when a correlationId
                        // actually exists.
                      })}
                      className={`relative pl-8 pb-8 group cursor-pointer`}
                    >
                      {/* Line */}
                      <div className={`absolute left-[11px] top-6 bottom-0 w-0.5 ${idx === filteredEvents.length - 1 ? 'bg-transparent' : 'bg-[#1f1f28]'}`}></div>
                      
                      {/* Dot */}
                      <div className={`absolute left-0 top-1 p-1.5 rounded-lg border bg-[#13131a] z-10 transition-colors ${
                        event.severity === 'CRITICAL' ? 'border-destructive/30 group-hover:border-destructive/60' :
                        event.severity === 'WARNING' ? 'border-warning/30 group-hover:border-warning/60' :
                        'border-blue-500/30 group-hover:border-blue-500/60'
                      }`}>
                        {getEventIcon(event.severity)}
                      </div>

                      {/* Content Card */}
                      <div className={`bg-card/50 backdrop-blur border rounded-xl p-4 transition-all border-border/50 group-hover:border-[#2d2d3d] group-hover:bg-[#13131a]`}>
                        <div className="flex justify-between items-start mb-2">
                          <h3 className="font-semibold text-foreground group-hover:text-blue-400 transition-colors">{event.type}</h3>
                          <span className="text-xs font-mono text-muted-foreground bg-[#0a0a0f] px-2 py-1 rounded border border-[#1f1f28]">
                            {new Date(event.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
                          </span>
                        </div>
                        <p className="text-sm text-muted-foreground mb-3">{event.message}</p>
                        
                        {event.correlationId && (
                          <div className="flex items-center gap-2 mt-2 pt-2 border-t border-[#1f1f28]/50 text-xs font-mono text-muted-foreground">
                            <Hash size={12} className="text-purple-400" />
                            {event.correlationId}
                            <button
                              onClick={(e) => {
                                e.stopPropagation();
                                navigate(`/pipeline?correlationId=${encodeURIComponent(event.correlationId)}`);
                              }}
                              className="ml-auto text-[10px] text-blue-400 opacity-0 group-hover:opacity-100 transition-opacity hover:underline"
                            >
                              View Trace &rarr;
                            </button>
                          </div>
                        )}
                      </div>
                    </motion.div>
                  );
                })
              )}
            </AnimatePresence>
          </div>
        </div>
      </div>
    </div>
  );
}
