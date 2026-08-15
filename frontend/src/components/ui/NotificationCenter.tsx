import { useState, useMemo } from 'react';
import { Bell, CheckCircle2, Zap, AlertTriangle, AlertCircle, Info, Settings, Search, X, Hash, ChevronRight } from 'lucide-react';
import { motion, AnimatePresence } from 'framer-motion';
import { usePolling } from '../../hooks/usePolling';
import { useRepositories } from '../../repositories/RepositoryProvider';

export function NotificationCenter({ open, setOpen }: { open: boolean, setOpen: (open: boolean) => void }) {
  const { events } = useRepositories();
  const { data: globalEvents = [], isLoading } = usePolling(
    ['global-events-history'],
    () => events.getEvents(undefined, 50),
    { interval: 3000 }
  );

  const [activeTab, setActiveTab] = useState('all'); // all, unread, alerts

  const filteredEvents = useMemo(() => {
    return globalEvents.filter((e: any) => {
      if (activeTab === 'unread') return true; // Pretend all are unread for now
      if (activeTab === 'alerts') return e.severity === 'CRITICAL' || e.severity === 'WARNING';
      return true;
    });
  }, [globalEvents, activeTab]);

  const getEventIcon = (severity: string, type: string) => {
    if (type.includes('Action')) return <Zap size={14} className="text-purple-400" />;
    switch (severity) {
      case 'CRITICAL': return <AlertCircle size={14} className="text-destructive" />;
      case 'WARNING': return <AlertTriangle size={14} className="text-warning" />;
      default: return <Info size={14} className="text-blue-400" />;
    }
  };

  return (
    <AnimatePresence>
      {open && (
        <>
          {/* Backdrop */}
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={() => setOpen(false)}
            className="fixed inset-0 bg-background/80 backdrop-blur-sm z-50"
          />

          {/* Slide-over */}
          <motion.div
            initial={{ x: '100%' }}
            animate={{ x: 0 }}
            exit={{ x: '100%' }}
            transition={{ type: 'spring', bounce: 0, duration: 0.3 }}
            className="fixed inset-y-0 right-0 z-50 w-full max-w-md bg-[#0a0a0f] border-l border-[#1f1f28] shadow-2xl flex flex-col"
          >
            {/* Header */}
            <div className="shrink-0 p-4 border-b border-[#1f1f28] flex items-center justify-between bg-[#060b14]">
              <div className="flex items-center gap-3">
                <div className="relative">
                  <Bell size={20} className="text-foreground" />
                  <span className="absolute -top-1 -right-1 flex h-3 w-3">
                    <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-blue-400 opacity-75"></span>
                    <span className="relative inline-flex rounded-full h-3 w-3 bg-blue-500"></span>
                  </span>
                </div>
                <h2 className="font-semibold text-lg">Notifications</h2>
              </div>
              <div className="flex items-center gap-2">
                <button className="p-2 hover:bg-[#13131a] rounded-md text-muted-foreground hover:text-foreground transition-colors" title="Settings">
                  <Settings size={16} />
                </button>
                <button 
                  onClick={() => setOpen(false)}
                  className="p-2 hover:bg-[#13131a] rounded-md text-muted-foreground hover:text-foreground transition-colors"
                >
                  <X size={16} />
                </button>
              </div>
            </div>

            {/* Tabs & Filters */}
            <div className="shrink-0 p-4 border-b border-[#1f1f28] space-y-4 bg-[#0a0a0f]">
              <div className="flex items-center gap-1 bg-[#13131a] p-1 rounded-lg">
                {[
                  { id: 'all', label: 'All' },
                  { id: 'unread', label: 'Unread' },
                  { id: 'alerts', label: 'Alerts' }
                ].map(t => (
                  <button
                    key={t.id}
                    onClick={() => setActiveTab(t.id)}
                    className={`flex-1 px-3 py-1.5 text-xs font-medium rounded-md transition-colors ${
                      activeTab === t.id ? 'bg-[#2d2d3d] text-foreground' : 'text-muted-foreground hover:text-foreground'
                    }`}
                  >
                    {t.label}
                  </button>
                ))}
              </div>
              
              <div className="relative">
                <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
                <input 
                  type="text" 
                  placeholder="Filter notifications..."
                  className="w-full bg-[#13131a] border border-[#1f1f28] rounded-md pl-9 pr-4 py-1.5 text-sm focus:outline-none focus:border-blue-500 transition-colors placeholder:text-muted-foreground"
                />
              </div>
            </div>

            {/* Notification List */}
            <div className="flex-1 overflow-y-auto custom-scrollbar bg-[#060b14]">
              {isLoading && globalEvents.length === 0 ? (
                <div className="p-6 text-center text-sm text-muted-foreground">Loading...</div>
              ) : filteredEvents.length === 0 ? (
                <div className="p-12 text-center text-sm text-muted-foreground flex flex-col items-center">
                  <CheckCircle2 size={40} className="mb-4 opacity-20 text-success" />
                  <p>You're all caught up!</p>
                  <p className="text-xs mt-1">No new notifications in this category.</p>
                </div>
              ) : (
                <div className="divide-y divide-[#1f1f28]">
                  {filteredEvents.map((event: any, idx: number) => (
                    <div 
                      key={event.id || idx}
                      className={`p-4 hover:bg-[#13131a] transition-colors cursor-pointer group relative ${idx < 3 ? 'bg-blue-500/5' : ''}`}
                    >
                      {idx < 3 && <div className="absolute left-0 top-0 bottom-0 w-0.5 bg-blue-500"></div>}
                      
                      <div className="flex items-start gap-3">
                        <div className={`mt-1 p-2 rounded-lg bg-[#13131a] border border-[#1f1f28]`}>
                          {getEventIcon(event.severity, event.type)}
                        </div>
                        
                        <div className="flex-1 min-w-0">
                          <div className="flex justify-between items-start mb-1">
                            <h4 className="text-sm font-semibold text-foreground truncate pr-4">{event.type}</h4>
                            <span className="text-[10px] text-muted-foreground whitespace-nowrap shrink-0">
                              {new Date(event.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                            </span>
                          </div>
                          <p className="text-xs text-muted-foreground leading-relaxed line-clamp-2 mb-2">
                            {event.message}
                          </p>
                          
                          {event.correlationId && (
                            <div className="flex items-center gap-1.5 text-[10px] font-mono text-muted-foreground bg-[#0a0a0f] border border-[#1f1f28] w-fit px-2 py-0.5 rounded">
                              <Hash size={10} className="text-purple-400" /> {event.correlationId.split('-')[0]}
                            </div>
                          )}
                        </div>
                        
                        <ChevronRight size={14} className="text-muted-foreground opacity-0 group-hover:opacity-100 transition-opacity mt-2 shrink-0" />
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>

            {/* Footer */}
            <div className="shrink-0 p-3 bg-[#0a0a0f] border-t border-[#1f1f28] text-center">
              <button className="text-xs text-blue-400 hover:text-blue-300 font-medium transition-colors">
                Mark all as read
              </button>
            </div>
            
          </motion.div>
        </>
      )}
    </AnimatePresence>
  );
}
