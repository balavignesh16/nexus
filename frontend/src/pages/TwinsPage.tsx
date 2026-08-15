import { useState, useMemo } from 'react';
import { useRepositories } from '../repositories/RepositoryProvider';
import { usePolling } from '../hooks/usePolling';
import { Box, Activity, Search, Filter, Cpu, Database } from 'lucide-react';
import { Skeleton } from '../components/ui/Skeleton';
import { DeviceStatusBadge } from '../components/DeviceStatusBadge';
import { motion } from 'framer-motion';
import { useInspector } from '../components/ui/InspectorProvider';
export default function TwinsPage() {
  const { device } = useRepositories();
  const { data: devices = [], isLoading } = usePolling(
    ['all-devices'],
    () => device.getDevices(),
    { interval: 5000 }
  );

  const [search, setSearch] = useState('');
  const { openInspector } = useInspector();

  const filteredDevices = useMemo(() => {
    return devices.filter(d => d.name.toLowerCase().includes(search.toLowerCase()) || d.id.toLowerCase().includes(search.toLowerCase()));
  }, [devices, search]);

  if (isLoading && devices.length === 0) {
    return (
      <div className="p-8 h-full">
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
            <Box size={20} className="text-blue-400" />
          </div>
          <div>
            <h1 className="text-lg font-bold">Digital Twins Center</h1>
            <p className="text-xs text-muted-foreground">Real-time asset state synchronization</p>
          </div>
        </div>

        <div className="flex items-center gap-4">
          <div className="relative">
            <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
            <input 
              type="text" 
              placeholder="Search twins..."
              value={search}
              onChange={e => setSearch(e.target.value)}
              className="pl-9 pr-4 py-1.5 bg-[#13131a] border border-[#1f1f28] rounded-md text-sm focus:outline-none focus:border-blue-500 transition-colors w-64"
            />
          </div>
          <button className="p-2 text-muted-foreground hover:text-foreground bg-[#13131a] border border-[#1f1f28] rounded-md">
            <Filter size={16} />
          </button>
          <div className="flex items-center gap-2 px-3 py-1.5 bg-[#13131a] border border-[#1f1f28] rounded-md text-sm font-medium">
            <div className="w-2 h-2 rounded-full bg-success animate-pulse"></div>
            Live Sync
          </div>
        </div>
      </div>

      <div className="flex-1 flex overflow-hidden">
        
        {/* TWIN GRID */}
        <div className="flex-1 overflow-y-auto custom-scrollbar p-6">
          <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4 gap-4">
            {filteredDevices.map((d: any, idx) => {
              const latestValue = d.value;
              // We'll just use the standard styling since React Query handles the update silently. 
              // Micro-animations can be added in CSS based on value changes if needed.
              return (
              <motion.div 
                key={d.id}
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: idx * 0.05 }}
                onClick={() => openInspector({
                  id: d.id,
                  type: 'Twin',
                  title: d.name,
                  subtitle: d.id,
                  status: d.status === 'ACTIVE' ? 'Active' : 'Offline',
                  statusColor: d.status === 'ACTIVE' ? 'success' : 'warning',
                  properties: {
                    'Type': d.deviceType || 'Generic',
                    'Firmware': 'v1.0.0',
                    'Last Seen': new Date(d.lastSeen).toLocaleString(),
                    'Topic': d.mqttTopic
                  },
                  rawJson: d,
                  history: [
                    { time: new Date().toLocaleString(), action: 'Sync', details: 'Reported state updated' },
                    { time: new Date(Date.now() - 3600000).toLocaleString(), action: 'Connect', details: 'Device connected' }
                  ]
                })}
                className={`bg-card/50 backdrop-blur border rounded-xl p-5 cursor-pointer hover:border-blue-500/50 hover:bg-blue-500/5 transition-all group relative overflow-hidden border-border/50`}
              >
                <div className="flex justify-between items-start mb-4">
                  <div className="flex items-center gap-3">
                    <div className="w-10 h-10 rounded-lg bg-primary/10 flex items-center justify-center">
                      <Cpu size={20} className="text-primary" />
                    </div>
                    <div>
                      <h3 className="font-semibold text-sm group-hover:text-blue-400 transition-colors">{d.name}</h3>
                      <p className="text-xs text-muted-foreground font-mono mt-0.5">{d.id}</p>
                    </div>
                  </div>
                  <DeviceStatusBadge status={d.status} />
                </div>
                
                <div className="grid grid-cols-2 gap-4 mt-6">
                  <div className="space-y-1">
                    <span className="text-[10px] text-muted-foreground uppercase tracking-wider">Health</span>
                    <div className="flex items-center gap-2">
                      <Activity size={14} className={d.status === 'ACTIVE' ? 'text-success' : 'text-warning'} />
                      <span className="font-mono text-sm font-medium">{d.status === 'ACTIVE' ? '100%' : 'N/A'}</span>
                    </div>
                  </div>
                  <div className="space-y-1">
                    <span className="text-[10px] text-muted-foreground uppercase tracking-wider">Twin Quality</span>
                    <div className="flex items-center gap-2">
                      <Database size={14} className="text-blue-400" />
                      <span className="font-mono text-sm font-medium">99.9%</span>
                    </div>
                  </div>
                </div>
                {latestValue !== undefined && <div className="mt-4 pt-3 border-t border-border/50 text-xs text-blue-300 animate-in fade-in">Latest value: <span className="font-mono font-semibold">{String(latestValue)}</span></div>}
              </motion.div>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}
