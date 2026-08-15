import { motion, AnimatePresence } from 'framer-motion';
import { Zap, Play, CheckCircle2, XCircle, Search, RotateCw } from 'lucide-react';
import { useState } from 'react';
import { Skeleton } from '../../../ui/Skeleton';
import { useRepositories } from '../../../../repositories/RepositoryProvider';
import { usePolling } from '../../../../hooks/usePolling';
import { DebugOverlay } from '../DebugOverlay';

export function ActionsTab({ deviceId }: { deviceId: string }) {
  const { actions } = useRepositories();
  const { data: deviceActions = [], isLoading } = usePolling(
    ['actions-history', deviceId],
    () => actions.getActions(deviceId, 50),
    { interval: 2000 }
  );
    
  const [filter, setFilter] = useState('ALL');

  if (isLoading) return <Skeleton className="w-full h-full" />;

  const getActionIcon = (status: string) => {
    switch (status) {
      case 'SUCCESS': return <CheckCircle2 size={16} className="text-success" />;
      case 'FAILED': return <XCircle size={16} className="text-destructive" />;
      case 'PENDING': return <RotateCw size={16} className="text-warning animate-spin" />;
      default: return <Play size={16} className="text-primary" />;
    }
  };

  const filteredActions = deviceActions.filter((a: any) => filter === 'ALL' || a.status === filter);

  return (
    <div className="p-6 h-full flex flex-col space-y-6 animate-in slide-in-from-bottom-2 fade-in duration-300">
      
      {/* Header & Controls */}
      <div className="flex items-center justify-between bg-card/50 backdrop-blur border border-border/50 rounded-xl p-4 shadow-sm shrink-0 relative group">
        <DebugOverlay 
          title="Action Execution History" 
          data={deviceActions}
          repository="DataSourceResolver"
        />
        <div className="flex items-center gap-3">
          <div className="p-2 bg-primary/10 rounded-lg">
            <Zap size={20} className="text-primary" />
          </div>
          <div>
            <h3 className="font-semibold text-foreground">Action Execution History</h3>
            <p className="text-xs text-muted-foreground">Commands sent to device</p>
          </div>
        </div>
        
        <div className="flex items-center gap-4">
          <div className="relative group">
            <Search size={14} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-muted-foreground group-focus-within:text-primary transition-colors" />
            <input 
              type="text" 
              placeholder="Search actions..." 
              className="w-48 bg-background/50 border border-border/50 rounded-md pl-8 pr-3 py-1.5 text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-primary focus:border-primary transition-all"
            />
          </div>
          <div className="flex bg-muted/50 rounded-md p-1 border border-border/50">
            {['ALL', 'SUCCESS', 'FAILED', 'PENDING'].map(f => (
              <button 
                key={f}
                onClick={() => setFilter(f)}
                className={`px-3 py-1.5 text-xs font-medium rounded transition-colors ${filter === f ? 'bg-background shadow-sm text-foreground' : 'text-muted-foreground hover:text-foreground'}`}
              >
                {f}
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* Action Feed */}
      <div className="flex-1 min-h-0 bg-card border border-border/50 rounded-xl overflow-hidden shadow-sm relative">
        <div className="absolute inset-0 overflow-y-auto custom-scrollbar p-6">
          <div className="relative before:absolute before:inset-0 before:ml-5 before:-translate-x-px md:before:mx-auto md:before:translate-x-0 before:h-full before:w-0.5 before:bg-gradient-to-b before:from-transparent before:via-border/50 before:to-transparent">
            
            <AnimatePresence initial={false}>
              {filteredActions.length === 0 && (
                <div className="flex flex-col items-center justify-center py-20 text-muted-foreground gap-4">
                  <Zap size={48} className="opacity-20 animate-pulse" />
                  <div className="text-center">
                    <p className="text-sm font-medium text-foreground uppercase tracking-wider mb-1">Command Queue Empty</p>
                    <p className="text-xs">No actions have been executed against this device yet.</p>
                  </div>
                  <div className="flex gap-4 text-[10px] font-mono mt-2 bg-muted/20 px-3 py-1.5 rounded border border-border/50">
                    <span>Queue: LISTENING</span>
                    <span>Filter: {filter}</span>
                  </div>
                </div>
              )}
              {filteredActions.map((action: any, i: number) => (
                <motion.div
                  key={action.id || `${action.timestamp}-${i}`}
                  initial={{ opacity: 0, y: -20 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={{ opacity: 0, scale: 0.95 }}
                  transition={{ duration: 0.3 }}
                  className="relative flex items-center justify-between md:justify-normal md:odd:flex-row-reverse group is-active mb-8"
                >
                  <div className="flex items-center justify-center w-10 h-10 rounded-full border-4 border-background bg-card shrink-0 md:order-1 md:group-odd:-translate-x-1/2 md:group-even:translate-x-1/2 shadow-sm z-10">
                    {getActionIcon(action.status)}
                  </div>
                  
                  <div className="w-[calc(100%-4rem)] md:w-[calc(50%-2.5rem)] bg-card border border-border/50 p-4 rounded-xl shadow-sm hover:shadow-md hover:border-primary/50 transition-all">
                    <div className="flex items-center justify-between mb-2">
                      <span className="text-xs font-bold font-mono text-muted-foreground">
                        {new Date(action.timestamp).toLocaleTimeString([], { hour12: false, hour: '2-digit', minute:'2-digit', second:'2-digit' })}
                      </span>
                      <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full ${
                        action.status === 'FAILED' ? 'bg-destructive/10 text-destructive' :
                        action.status === 'PENDING' ? 'bg-warning/10 text-warning' :
                        'bg-success/10 text-success'
                      }`}>
                        {action.status}
                      </span>
                    </div>
                    <p className="text-sm text-foreground/90 font-medium">
                      {action.message || 'Action executed'}
                    </p>
                    {action.payload && Object.keys(action.payload).length > 0 && (
                      <div className="mt-3 bg-muted/30 rounded p-2 text-xs font-mono text-muted-foreground overflow-hidden text-ellipsis whitespace-nowrap">
                        {JSON.stringify(action.payload)}
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
