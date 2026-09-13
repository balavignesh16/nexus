import { useState, useMemo } from 'react';
import { useRepositories } from '../repositories/RepositoryProvider';
import { usePolling } from '../hooks/usePolling';
import { Box, Search, Filter, Cpu, Signal } from 'lucide-react';
import { Skeleton } from '../components/ui/Skeleton';
import { DeviceStatusBadge } from '../components/DeviceStatusBadge';
import { motion } from 'framer-motion';
import { useInspector } from '../components/ui/InspectorProvider';
import { useDeviceTwinState } from '../hooks/useDeviceTwinState';
import { formatAge } from '../utils/formatAge';
import { DataSourceBadge } from '../components/ui/DataSourceBadge';

function TwinCard({ device, idx, openInspector }: { device: any; idx: number; openInspector: (item: any) => void }) {
  const twinState = useDeviceTwinState(device.id);
  const connectivity = twinState.connectivity?.status ?? 'UNKNOWN';

  const handleOpen = () => openInspector({
    id: device.id,
    type: 'Twin',
    title: device.name,
    subtitle: device.id,
    status: device.status === 'ACTIVE' ? 'Active' : 'Offline',
    statusColor: device.status === 'ACTIVE' ? 'success' : 'warning',
    properties: {
      'Type': device.deviceType || 'Generic',
      'Connectivity': connectivity,
      'Last Telemetry': twinState.lastTelemetryAt ? new Date(twinState.lastTelemetryAt).toLocaleString() : 'Unavailable',
      'Reporting Fields': twinState.reportedFields.length > 0 ? twinState.reportedFields.join(', ') : 'None',
      'Topic': device.mqttTopic,
    },
    rawJson: device,
    // No real per-twin event history source exists yet - omitted rather
    // than fabricated (Phase 8). UniversalInspector renders nothing for
    // an absent history, which is the honest behavior here.
  });

  return (
    <motion.div
      initial={{ opacity: 0, y: 10 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ delay: idx * 0.05 }}
      onClick={handleOpen}
      className="bg-card/50 backdrop-blur border rounded-xl p-5 cursor-pointer hover:border-blue-500/50 hover:bg-blue-500/5 transition-all group relative overflow-hidden border-border/50"
    >
      <div className="flex justify-between items-start mb-4">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-lg bg-primary/10 flex items-center justify-center">
            <Cpu size={20} className="text-primary" />
          </div>
          <div>
            <h3 className="font-semibold text-sm group-hover:text-blue-400 transition-colors">{device.name}</h3>
            <p className="text-xs text-muted-foreground font-mono mt-0.5">{device.id}</p>
          </div>
        </div>
        <DeviceStatusBadge status={device.status} />
      </div>

      <div className="grid grid-cols-2 gap-4 mt-6">
        <div className="space-y-1">
          <span className="text-[10px] text-muted-foreground uppercase tracking-wider flex items-center gap-1">
            Connectivity <DataSourceBadge classification="LIVE" />
          </span>
          <div className="flex items-center gap-2">
            <Signal size={14} className={connectivity === 'ONLINE' ? 'text-success' : connectivity === 'OFFLINE' ? 'text-destructive' : 'text-[var(--data-unavailable)]'} />
            <span className="font-mono text-sm font-medium">{connectivity}</span>
          </div>
        </div>
        <div className="space-y-1">
          <span className="text-[10px] text-muted-foreground uppercase tracking-wider flex items-center gap-1">
            Last Telemetry <DataSourceBadge classification="CALCULATED" />
          </span>
          <div className="flex items-center gap-2">
            <span className="font-mono text-sm font-medium" title={twinState.lastTelemetryAt ? new Date(twinState.lastTelemetryAt).toLocaleString() : undefined}>
              {twinState.telemetryLoading ? '…' : twinState.lastTelemetryAt ? formatAge(twinState.lastTelemetryAt) : 'Unavailable'}
            </span>
          </div>
        </div>
      </div>
      <div className="mt-4 pt-3 border-t border-border/50 text-xs text-muted-foreground">
        {twinState.reportedFields.length > 0
          ? `Reporting ${twinState.reportedFields.length} field(s): ${twinState.reportedFields.join(', ')}`
          : 'No telemetry fields reported yet'}
      </div>
    </motion.div>
  );
}

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
        </div>
      </div>

      <div className="flex-1 flex overflow-hidden">

        {/* TWIN GRID */}
        <div className="flex-1 overflow-y-auto custom-scrollbar p-6">
          {filteredDevices.length === 0 ? (
            <div className="flex flex-col items-center justify-center h-full text-muted-foreground gap-3">
              <Box size={48} className="opacity-20" />
              <p className="text-sm">No devices to represent as a digital twin.</p>
            </div>
          ) : (
            <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4 gap-4">
              {filteredDevices.map((d: any, idx) => (
                <TwinCard key={d.id} device={d} idx={idx} openInspector={openInspector} />
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
