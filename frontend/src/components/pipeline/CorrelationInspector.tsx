import { useQuery } from '@tanstack/react-query';
import { useRepositories } from '../../repositories/RepositoryProvider';
import { X, Clock, Activity } from 'lucide-react';
import type { PipelineTracePayload } from '../../repositories/types';

export function CorrelationInspector({ correlationId, onClose }: { correlationId: string, onClose: () => void }) {
  const { pipeline } = useRepositories();

  // Fetch full details of this specific correlation
  const { data: traces = [], isLoading } = useQuery<PipelineTracePayload[]>({
    queryKey: ['correlation', correlationId],
    queryFn: () => pipeline.getPipelineByCorrelationId(correlationId),
  });

  const totalDuration = traces.reduce((acc, t) => acc + t.durationMs, 0);
  const deviceId = traces[0]?.deviceId || 'SYS';
  const hasErrors = traces.some(t => t.status === 'ERROR' || t.status === 'FAILURE');

  return (
    <div className="h-full flex flex-col">
      {/* Header */}
      <div className="shrink-0 p-4 border-b border-[#1f1f28] flex items-center justify-between">
        <div>
          <h3 className="text-sm font-bold text-white flex items-center gap-2">
            <Activity size={16} className="text-blue-400" />
            Trace Inspector
          </h3>
          <p className="text-[10px] font-mono text-muted-foreground mt-1 truncate max-w-[200px]">
            {correlationId}
          </p>
        </div>
        <button onClick={onClose} className="p-1 hover:bg-[#1f1f28] rounded text-muted-foreground transition-colors">
          <X size={16} />
        </button>
      </div>

      {/* Summary */}
      <div className="shrink-0 p-4 border-b border-[#1f1f28] bg-[#0a0a0f] flex flex-col gap-3">
        <div className="flex justify-between items-center text-xs">
          <span className="text-muted-foreground">Device ID</span>
          <span className="font-mono text-white">{deviceId.split('-')[0]}</span>
        </div>
        <div className="flex justify-between items-center text-xs">
          <span className="text-muted-foreground">Total Time</span>
          <span className="font-mono text-white flex items-center gap-1"><Clock size={12}/> {totalDuration}ms</span>
        </div>
        <div className="flex justify-between items-center text-xs">
          <span className="text-muted-foreground">Overall Status</span>
          <span className={`font-bold ${hasErrors ? 'text-red-500' : 'text-success'}`}>
            {hasErrors ? 'FAILED' : 'SUCCESS'}
          </span>
        </div>
      </div>

      {/* Trace Lifecycle Timeline */}
      <div className="flex-1 overflow-auto p-4 flex flex-col gap-0">
        {isLoading && <div className="text-center text-xs text-muted-foreground py-4 animate-pulse">Loading trace details...</div>}
        
        {traces.map((trace, i) => (
          <div key={trace.traceId} className="flex gap-4 group">
            {/* Left timeline indicator */}
            <div className="flex flex-col items-center">
              <div className={`w-3 h-3 rounded-full border-2 ${trace.status === 'SUCCESS' ? 'bg-[#13131a] border-success' : 'bg-red-500 border-red-500 shadow-[0_0_8px_red]'}`}></div>
              {i < traces.length - 1 && <div className="w-px h-full min-h-[30px] bg-[#1f1f28] group-hover:bg-[#2d2d3d] transition-colors my-1"></div>}
            </div>
            
            {/* Right content */}
            <div className="flex-1 pb-6">
              <div className="flex items-center justify-between mb-1">
                <span className="text-xs font-bold text-white">{trace.stage}</span>
                <span className="text-[10px] font-mono text-muted-foreground">{trace.durationMs}ms</span>
              </div>
              
              {trace.metadata && Object.keys(trace.metadata).length > 0 && (
                <div className="bg-[#0a0a0f] border border-[#1f1f28] rounded-md p-2 mt-2">
                  <pre className="text-[10px] font-mono text-muted-foreground overflow-x-auto">
                    {JSON.stringify(trace.metadata, null, 2)}
                  </pre>
                </div>
              )}
            </div>
          </div>
        ))}
        
        {!isLoading && traces.length === 0 && (
          <div className="text-center text-xs text-muted-foreground py-4">
            No trace details found.
          </div>
        )}
      </div>
    </div>
  );
}
