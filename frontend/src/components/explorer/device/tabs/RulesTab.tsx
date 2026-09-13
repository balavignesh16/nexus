import { useState } from 'react';
import { Server, Activity, ArrowRight, CheckCircle2, Circle, Plus } from 'lucide-react';
import { Skeleton } from '../../../ui/Skeleton';
import { useRepositories } from '../../../../repositories/RepositoryProvider';
import { usePolling } from '../../../../hooks/usePolling';
import { DebugOverlay } from '../DebugOverlay';
import { CreateRuleModal } from '../CreateRuleModal';

export function RulesTab({ deviceId }: { deviceId: string }) {
  const { rules } = useRepositories();
  const { data: deviceRules = [], isLoading, refetch } = usePolling(
    ['rules', deviceId],
    () => rules.getRules(deviceId),
    { interval: 30000 }
  );
  const [modalOpen, setModalOpen] = useState(false);

  if (isLoading) return <Skeleton className="w-full h-full" />;

  return (
    <div className="p-6 h-full flex flex-col space-y-6 animate-in slide-in-from-bottom-2 fade-in duration-300">
      
      {/* Header & Controls */}
      <div className="flex items-center justify-between bg-card/50 backdrop-blur border border-border/50 rounded-xl p-4 shadow-sm shrink-0 relative group">
        <DebugOverlay 
          title="Active Automations" 
          data={deviceRules}
          repository="DataSourceResolver"
        />
        <div className="flex items-center gap-3">
          <div className="p-2 bg-primary/10 rounded-lg">
            <Server size={20} className="text-primary" />
          </div>
          <div>
            <h3 className="font-semibold text-foreground">Active Automations</h3>
            <p className="text-xs text-muted-foreground">Rules currently monitoring this device</p>
          </div>
        </div>
        <button
          onClick={() => setModalOpen(true)}
          className="flex items-center gap-2 px-3 py-1.5 bg-primary/10 hover:bg-primary/20 text-primary rounded-md text-xs font-medium transition-colors border border-primary/20"
        >
          <Plus size={14} /> Create Rule
        </button>
      </div>

      {/* Rules Grid */}
      <div className="flex-1 min-h-0 overflow-y-auto custom-scrollbar pb-6">
        {deviceRules.length === 0 ? (
          <div className="flex flex-col items-center justify-center h-full text-muted-foreground gap-4">
            <Server size={48} className="opacity-20" />
            <div className="text-center">
              <p className="text-sm font-medium text-foreground uppercase tracking-wider mb-1">No Automations Bound</p>
              <p className="text-xs">There are currently no active rules monitoring this device's telemetry stream.</p>
            </div>
            <button
              onClick={() => setModalOpen(true)}
              className="mt-2 px-4 py-2 bg-primary/10 hover:bg-primary/20 text-primary rounded-md text-xs font-medium transition-colors border border-primary/20"
            >
              Create Rule
            </button>
          </div>
        ) : (
          <div className="grid grid-cols-1 lg:grid-cols-2 xl:grid-cols-3 gap-6">
            {deviceRules.map((rule: any) => (
              <div key={rule.id} className="bg-card border border-border/50 rounded-xl overflow-hidden shadow-sm hover:shadow-md hover:border-primary/50 transition-all flex flex-col group">
                {/* Rule Header */}
                <div className="p-4 border-b border-border/50 bg-muted/20 flex items-start justify-between">
                  <div>
                    <h4 className="font-semibold text-foreground group-hover:text-primary transition-colors">{rule.name}</h4>
                    <p className="text-xs text-muted-foreground mt-1 line-clamp-2">{rule.description || 'No description provided.'}</p>
                  </div>
                  <div className="flex items-center gap-1 bg-success/10 text-success px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider shrink-0">
                    <CheckCircle2 size={10} /> Active
                  </div>
                </div>
                
                {/* Rule Content (Conditions -> Actions) */}
                <div className="p-4 flex-1 flex flex-col gap-4">
                  <div>
                    <span className="text-[10px] font-bold text-muted-foreground uppercase tracking-wider mb-2 block">Trigger Condition</span>
                    <div className="bg-background border border-border/50 rounded p-2 text-xs font-mono text-foreground/80 break-all">
                      {rule.condition}
                    </div>
                  </div>
                  
                  <div className="flex items-center justify-center text-muted-foreground/50">
                    <ArrowRight size={16} />
                  </div>
                  
                  <div>
                    <span className="text-[10px] font-bold text-muted-foreground uppercase tracking-wider mb-2 block">Actions</span>
                    <div className="flex flex-col gap-2">
                      {rule.actions.map((action: any, idx: number) => (
                        <div key={idx} className="bg-primary/5 border border-primary/20 rounded p-2 text-xs flex items-center gap-2">
                          <Activity size={12} className="text-primary shrink-0" />
                          <span className="font-medium text-foreground">{action.type}</span>
                          <span className="text-muted-foreground truncate">{JSON.stringify(action.payload)}</span>
                        </div>
                      ))}
                    </div>
                  </div>
                </div>
                
                {/* Rule Footer */}
                <div className="px-4 py-2 border-t border-border/50 bg-muted/10 flex items-center justify-between text-[10px] text-muted-foreground">
                  <div className="flex items-center gap-1">
                    <Circle size={8} className="fill-success text-success" /> Monitored Live
                  </div>
                  <span>Priority: {rule.priority}</span>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      <CreateRuleModal
        deviceId={deviceId}
        isOpen={modalOpen}
        onClose={() => setModalOpen(false)}
        onCreated={() => refetch()}
      />
    </div>
  );
}
