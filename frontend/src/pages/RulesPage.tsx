import { useState } from 'react';
import { useRepositories } from '../repositories/RepositoryProvider';
import { usePolling } from '../hooks/usePolling';
import { FileJson, Search, Filter, Play, Plus, Activity, Zap, CheckCircle2, Clock } from 'lucide-react';
import { Skeleton } from '../components/ui/Skeleton';
import { motion } from 'framer-motion';

export function RulesPage() {
  const { rules } = useRepositories();
  const { data: globalRules = [], isLoading } = usePolling(
    ['global-rules'],
    () => rules.getRules(),
    { interval: 5000 }
  );

  const [search, setSearch] = useState('');

  const filteredRules = globalRules.filter(r => 
    r.name?.toLowerCase().includes(search.toLowerCase()) || 
    r.description?.toLowerCase().includes(search.toLowerCase())
  );

  if (isLoading && globalRules.length === 0) {
    return (
      <div className="p-8 h-full flex flex-col gap-4">
        <Skeleton className="w-full h-32 rounded-xl" />
        <Skeleton className="w-full h-full rounded-xl" />
      </div>
    );
  }

  return (
    <div className="flex-1 flex flex-col h-full bg-[#060b14] overflow-hidden text-foreground animate-in fade-in duration-300">
      
      {/* TOOLBAR */}
      <div className="shrink-0 h-16 border-b border-[#1f1f28] flex items-center justify-between px-6 bg-[#0a0a0f]">
        <div className="flex items-center gap-3">
          <div className="p-2 bg-blue-500/10 rounded-lg">
            <FileJson size={20} className="text-blue-400" />
          </div>
          <div>
            <h1 className="text-lg font-bold">Automation Rules</h1>
            <p className="text-xs text-muted-foreground">Enterprise rule engine configuration</p>
          </div>
        </div>

        <div className="flex items-center gap-4">
          <div className="relative">
            <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
            <input 
              type="text" 
              placeholder="Search rules..."
              value={search}
              onChange={e => setSearch(e.target.value)}
              className="pl-9 pr-4 py-1.5 bg-[#13131a] border border-[#1f1f28] rounded-md text-sm focus:outline-none focus:border-blue-500 transition-colors w-64"
            />
          </div>
          
          <button className="flex items-center gap-2 px-3 py-1.5 bg-[#13131a] border border-[#1f1f28] rounded-md text-sm text-muted-foreground hover:text-foreground">
            <Filter size={14} /> Filter
          </button>

          <button className="flex items-center gap-2 px-3 py-1.5 bg-blue-600 hover:bg-blue-700 text-white rounded-md text-sm font-medium transition-colors">
            <Plus size={14} /> Create Rule
          </button>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto custom-scrollbar p-6 space-y-6">
        
        {/* STATISTICS */}
        <div className="grid grid-cols-4 gap-4">
          <div className="bg-card/50 backdrop-blur border border-border/50 rounded-xl p-5 flex items-center justify-between">
            <div>
              <p className="text-xs text-muted-foreground font-medium uppercase tracking-wider mb-1">Total Rules</p>
              <span className="text-2xl font-bold">{globalRules.length}</span>
            </div>
            <div className="w-10 h-10 rounded-lg bg-blue-500/10 flex items-center justify-center">
              <FileJson size={20} className="text-blue-400" />
            </div>
          </div>
          
          <div className="bg-card/50 backdrop-blur border border-border/50 rounded-xl p-5 flex items-center justify-between">
            <div>
              <p className="text-xs text-muted-foreground font-medium uppercase tracking-wider mb-1">Executions (24h)</p>
              <span className="text-2xl font-bold font-mono">14,203</span>
            </div>
            <div className="w-10 h-10 rounded-lg bg-purple-500/10 flex items-center justify-center">
              <Zap size={20} className="text-purple-400" />
            </div>
          </div>
          
          <div className="bg-card/50 backdrop-blur border border-border/50 rounded-xl p-5 flex items-center justify-between">
            <div>
              <p className="text-xs text-muted-foreground font-medium uppercase tracking-wider mb-1">Success Rate</p>
              <span className="text-2xl font-bold font-mono text-success">99.8%</span>
            </div>
            <div className="w-10 h-10 rounded-lg bg-success/10 flex items-center justify-center">
              <CheckCircle2 size={20} className="text-success" />
            </div>
          </div>
          
          <div className="bg-card/50 backdrop-blur border border-border/50 rounded-xl p-5 flex items-center justify-between">
            <div>
              <p className="text-xs text-muted-foreground font-medium uppercase tracking-wider mb-1">Avg Execution Time</p>
              <span className="text-2xl font-bold font-mono text-blue-400">42ms</span>
            </div>
            <div className="w-10 h-10 rounded-lg bg-blue-500/10 flex items-center justify-center">
              <Clock size={20} className="text-blue-400" />
            </div>
          </div>
        </div>

        {/* RULE CARDS */}
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
          {filteredRules.length === 0 ? (
            <div className="col-span-full py-20 flex flex-col items-center justify-center text-muted-foreground">
              <FileJson size={48} className="opacity-20 mb-4" />
              <p>No automation rules found.</p>
            </div>
          ) : (
            filteredRules.map((rule, idx) => (
              <motion.div 
                key={rule.id || idx}
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: idx * 0.05 }}
                className="bg-card/50 backdrop-blur border border-border/50 hover:border-blue-500/30 rounded-xl p-5 transition-colors group cursor-pointer flex flex-col justify-between"
              >
                <div>
                  <div className="flex justify-between items-start mb-2">
                    <h3 className="font-semibold text-foreground group-hover:text-blue-400 transition-colors">
                      {rule.name || rule.id}
                    </h3>
                    <div className="flex items-center gap-2">
                      <span className="text-[10px] bg-blue-500/10 text-blue-400 px-2 py-0.5 rounded font-bold uppercase tracking-wider">
                        Active
                      </span>
                    </div>
                  </div>
                  <p className="text-xs text-muted-foreground mb-4">
                    {rule.description || "Monitors telemetry streams and triggers automated actions."}
                  </p>
                  
                  <div className="bg-[#13131a] rounded-lg p-3 border border-[#1f1f28] text-xs font-mono space-y-1 mb-4">
                    <div className="flex gap-2">
                      <span className="text-purple-400">IF</span>
                      <span className="text-muted-foreground">temperature &gt; 25</span>
                    </div>
                    <div className="flex gap-2">
                      <span className="text-success">THEN</span>
                      <span className="text-muted-foreground">EXECUTE hvac-cool</span>
                    </div>
                  </div>
                </div>
                
                <div className="flex items-center justify-between pt-4 border-t border-[#1f1f28]">
                  <div className="flex items-center gap-4 text-xs text-muted-foreground">
                    <div className="flex items-center gap-1">
                      <Activity size={14} className="text-success" /> Healthy
                    </div>
                    <div className="flex items-center gap-1">
                      <Zap size={14} /> 142 triggers (24h)
                    </div>
                  </div>
                  <button className="text-blue-400 hover:text-blue-300 text-xs font-medium flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                    <Play size={14} /> Open Studio
                  </button>
                </div>
              </motion.div>
            ))
          )}
        </div>
      </div>
    </div>
  );
}

export default RulesPage;
