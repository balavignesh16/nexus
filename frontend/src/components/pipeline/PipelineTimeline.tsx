import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import type { PipelineTracePayload } from '../../repositories/types';
import { Activity, Zap, Box, FileJson, AlertTriangle, TrendingUp, Download } from 'lucide-react';

export function PipelineTimeline({ onSelectCorrelation }: { onSelectCorrelation: (id: string) => void }) {
  const queryClient = useQueryClient();
  const [traces, setTraces] = useState<PipelineTracePayload[]>([]);

  // We subscribe to the cache directly to render the infinite list efficiently
  useEffect(() => {
    const unsubscribe = queryClient.getQueryCache().subscribe((event) => {
      if (event.query.queryKey[0] === 'global-pipeline-traces') {
        setTraces(event.query.state.data as PipelineTracePayload[] || []);
      }
    });
    // initial
    setTraces(queryClient.getQueryData<PipelineTracePayload[]>(['global-pipeline-traces']) || []);
    return unsubscribe;
  }, [queryClient]);

  const getStageIcon = (stage: string) => {
    switch (stage) {
      case 'TELEMETRY': return <SignalIcon />;
      case 'TWIN': return <Box size={14} className="text-green-400" />;
      case 'RULE': return <FileJson size={14} className="text-orange-400" />;
      case 'ACTION': return <Zap size={14} className="text-purple-400" />;
      case 'ALERT': return <AlertTriangle size={14} className="text-red-400" />;
      case 'ANALYTICS': return <TrendingUp size={14} className="text-blue-400" />;
      default: return <Activity size={14} className="text-gray-400" />;
    }
  };

  const getStatusColor = (status: string) => {
    if (status === 'SUCCESS') return 'text-success bg-success/10 border-success/20';
    if (status === 'FAILURE' || status === 'ERROR') return 'text-destructive bg-destructive/10 border-destructive/20';
    return 'text-muted-foreground bg-muted border-muted-foreground/20';
  };

  return (
    <div className="h-full flex flex-col">
      <div className="shrink-0 flex items-center justify-between p-3 border-b border-[#1f1f28] bg-[#13131a]">
        <h3 className="text-xs font-bold text-white uppercase tracking-wider flex items-center gap-2">
          <Activity size={14} className="text-blue-400" />
          Chronological Execution Log
        </h3>
        <div className="flex gap-2">
           <button className="text-xs text-muted-foreground hover:text-white flex items-center gap-1">
             <Download size={14} /> Export CSV
           </button>
        </div>
      </div>
      
      <div className="flex-1 overflow-auto p-2">
        <table className="w-full text-left text-xs whitespace-nowrap">
          <thead className="sticky top-0 bg-[#0a0a0f] text-muted-foreground z-10 border-b border-[#1f1f28]">
            <tr>
              <th className="font-medium p-2 w-32">Timestamp</th>
              <th className="font-medium p-2 w-32">Stage</th>
              <th className="font-medium p-2 w-32">Device</th>
              <th className="font-medium p-2 w-48">Correlation ID</th>
              <th className="font-medium p-2 w-24">Duration</th>
              <th className="font-medium p-2 w-24">Status</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[#1f1f28]/50">
            {traces.slice(0, 100).map((trace) => (
              <tr 
                key={trace.traceId} 
                onClick={() => onSelectCorrelation(trace.correlationId)}
                className="hover:bg-[#13131a] transition-colors cursor-pointer group"
              >
                <td className="p-2 text-muted-foreground font-mono">
                  {new Date(trace.startTimestamp).toISOString().split('T')[1].replace('Z', '')}
                </td>
                <td className="p-2">
                  <div className="flex items-center gap-2">
                    {getStageIcon(trace.stage)}
                    <span className="font-medium text-white group-hover:text-blue-400 transition-colors">{trace.stage}</span>
                  </div>
                </td>
                <td className="p-2 text-muted-foreground font-mono">{trace.deviceId ? trace.deviceId.split('-')[0] : 'SYS'}</td>
                <td className="p-2 font-mono text-[10px] text-muted-foreground group-hover:text-blue-300">{trace.correlationId}</td>
                <td className="p-2 font-mono">{trace.durationMs}ms</td>
                <td className="p-2">
                  <span className={`px-2 py-0.5 rounded text-[10px] font-bold border ${getStatusColor(trace.status)}`}>
                    {trace.status}
                  </span>
                </td>
              </tr>
            ))}
            {traces.length === 0 && (
              <tr>
                <td colSpan={6} className="p-8 text-center text-muted-foreground">
                  No pipeline traces available. Waiting for live events...
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function SignalIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="text-blue-400">
      <path d="M2 20h.01"/><path d="M7 20v-4"/><path d="M12 20v-8"/><path d="M17 20V8"/><path d="M22 4v16"/>
    </svg>
  );
}
