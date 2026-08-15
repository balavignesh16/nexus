import { useState } from 'react';
import { Signal, Download, Filter, Search, ArrowUpDown, MoreVertical } from 'lucide-react';
import { SkeletonChart } from '../../../ui/Skeletons';
import { useRepositories } from '../../../../repositories/RepositoryProvider';
import { usePolling } from '../../../../hooks/usePolling';
import { DebugOverlay } from '../DebugOverlay';

export function TelemetryTab({ deviceId }: { deviceId: string }) {
  const { telemetry } = useRepositories();
  const { data: deviceTelemetry = [], isLoading } = usePolling(
    ['telemetry-history', deviceId],
    () => telemetry.getTelemetryHistory(deviceId),
    { interval: 2000 }
  );

  const [searchTerm, setSearchTerm] = useState('');

  if (isLoading) return <SkeletonChart />;

  // Extract all unique keys from payloads to build columns
  const availableKeys: string[] = Array.from(
    new Set(deviceTelemetry.flatMap((t: any) => Object.keys(t.payload || {})))
  ).filter(k => k !== 'timestamp');

  return (
    <div className="p-6 h-full flex flex-col space-y-6 animate-in slide-in-from-bottom-2 fade-in duration-300">
      
      {/* Header & Controls */}
      <div className="flex items-center justify-between bg-card/50 backdrop-blur border border-border/50 rounded-xl p-4 shadow-sm shrink-0 relative group">
        <DebugOverlay 
          title="Telemetry Data" 
          data={deviceTelemetry}
          repository="DataSourceResolver"
        />
        <div className="flex items-center gap-3">
          <div className="p-2 bg-primary/10 rounded-lg">
            <Signal size={20} className="text-primary" />
          </div>
          <div>
            <h3 className="font-semibold text-foreground">Telemetry Data</h3>
            <p className="text-xs text-muted-foreground">Historical data points</p>
          </div>
        </div>
        
        <div className="flex items-center gap-4">
          <div className="relative group">
            <Search size={14} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-muted-foreground group-focus-within:text-primary transition-colors" />
            <input 
              type="text" 
              placeholder="Search payloads..." 
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              className="w-48 bg-background/50 border border-border/50 rounded-md pl-8 pr-3 py-1.5 text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-primary focus:border-primary transition-all"
            />
          </div>
          
          <button className="flex items-center gap-2 px-3 py-1.5 bg-muted/50 hover:bg-muted border border-border/50 rounded text-xs font-medium transition-colors text-muted-foreground hover:text-foreground">
            <Filter size={14} /> Filter
          </button>
          <button className="flex items-center gap-2 px-3 py-1.5 bg-success/10 hover:bg-success/20 text-success rounded text-xs font-medium transition-colors">
            <Download size={14} /> Export CSV
          </button>
        </div>
      </div>

      {/* Data Table */}
      <div className="flex-1 min-h-0 bg-card border border-border/50 rounded-xl overflow-hidden shadow-sm relative flex flex-col">
        <div className="overflow-auto flex-1 custom-scrollbar">
          <table className="w-full text-sm text-left">
            <thead className="text-xs text-muted-foreground uppercase bg-muted/50 sticky top-0 z-10 backdrop-blur shadow-sm">
              <tr>
                <th className="px-6 py-3 font-semibold cursor-pointer hover:text-foreground transition-colors group">
                  <div className="flex items-center gap-2">
                    Timestamp <ArrowUpDown size={12} className="opacity-0 group-hover:opacity-100 transition-opacity" />
                  </div>
                </th>
                {availableKeys.map((k: string) => (
                  <th key={k} className="px-6 py-3 font-semibold capitalize cursor-pointer hover:text-foreground transition-colors group">
                    <div className="flex items-center gap-2">
                      {k} <ArrowUpDown size={12} className="opacity-0 group-hover:opacity-100 transition-opacity" />
                    </div>
                  </th>
                ))}
                <th className="px-6 py-3 font-semibold text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {deviceTelemetry.length === 0 ? (
                <tr>
                  <td colSpan={availableKeys.length + 2} className="px-6 py-20 text-center">
                    <div className="flex flex-col items-center justify-center text-muted-foreground gap-4">
                      <Signal size={48} className="opacity-20 animate-pulse" />
                      <div className="text-center">
                        <p className="text-sm font-medium text-foreground uppercase tracking-wider mb-1">Awaiting Telemetry Packet</p>
                        <p className="text-xs">Device is connected but no telemetry data has been received yet.</p>
                      </div>
                      <div className="flex gap-4 text-[10px] font-mono mt-2 bg-muted/20 px-3 py-1.5 rounded border border-border/50">
                        <span>Status: CONNECTED</span>
                        <span>Stream: ACTIVE</span>
                      </div>
                    </div>
                  </td>
                </tr>
              ) : (
                deviceTelemetry.map((t: any, idx: number) => (
                  <tr key={t.id || idx} className="border-b border-border/50 hover:bg-muted/20 transition-colors">
                    <td className="px-6 py-3 font-mono text-xs whitespace-nowrap text-muted-foreground">
                      {new Date(t.timestamp).toLocaleString()}
                    </td>
                    {availableKeys.map((k: string) => (
                      <td key={k} className="px-6 py-3 font-mono text-xs">
                        {t.payload[k] !== undefined ? String(t.payload[k]) : '-'}
                      </td>
                    ))}
                    <td className="px-6 py-3 text-right">
                      <button className="p-1 hover:bg-muted rounded text-muted-foreground hover:text-foreground transition-colors">
                        <MoreVertical size={14} />
                      </button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
        <div className="bg-muted/30 border-t border-border/50 p-3 px-6 flex items-center justify-between text-xs text-muted-foreground">
          <span>Showing {deviceTelemetry.length} records</span>
          <div className="flex items-center gap-4">
            <button className="hover:text-foreground transition-colors disabled:opacity-50" disabled>Previous</button>
            <span className="font-medium text-foreground">Page 1 of 1</span>
            <button className="hover:text-foreground transition-colors disabled:opacity-50" disabled>Next</button>
          </div>
        </div>
      </div>
    </div>
  );
}
