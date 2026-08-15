import { useState } from 'react';
import { Activity, Zap, Box, FileJson, AlertTriangle } from 'lucide-react';
import type { PipelineTracePayload } from '../../repositories/types';

interface PipelineStageNodeProps {
  stage: { id: string; label: string; x: string };
  traces: PipelineTracePayload[];
}

export function PipelineStageNode({ stage, traces }: PipelineStageNodeProps) {
  const [isHovered, setIsHovered] = useState(false);

  // Calculate metrics for this stage
  const stageTraces = traces.filter(t => t.stage === stage.id);
  const errorCount = stageTraces.filter(t => t.status === 'ERROR' || t.status === 'FAILURE').length;
  const isBroken = errorCount > 0 && stageTraces[0]?.status === 'ERROR';

  const getIcon = () => {
    switch (stage.id) {
      case 'TELEMETRY': return <SignalIcon />;
      case 'TWIN': return <Box size={24} className={isBroken ? 'text-red-400' : 'text-green-400'} />;
      case 'RULE': return <FileJson size={24} className={isBroken ? 'text-red-400' : 'text-orange-400'} />;
      case 'ACTION': return <Zap size={24} className={isBroken ? 'text-red-400' : 'text-purple-400'} />;
      case 'ALERT': return <AlertTriangle size={24} className={isBroken ? 'text-red-400' : 'text-red-500'} />;
      default: return <Activity size={24} className={isBroken ? 'text-red-400' : 'text-blue-400'} />;
    }
  };

  return (
    <div 
      className="absolute flex flex-col items-center justify-center -translate-x-1/2 -translate-y-1/2 cursor-pointer group"
      style={{ left: stage.x, top: '50%' }}
      onMouseEnter={() => setIsHovered(true)}
      onMouseLeave={() => setIsHovered(false)}
    >
      <div className={`w-16 h-16 rounded-xl flex items-center justify-center border-2 transition-all duration-300 ${
        isBroken 
          ? 'bg-red-500/10 border-red-500/50 shadow-[0_0_30px_rgba(239,68,68,0.3)]' 
          : 'bg-[#13131a] border-[#2d2d3d] group-hover:border-blue-500/50 group-hover:shadow-[0_0_20px_rgba(59,130,246,0.2)]'
      }`}>
        {getIcon()}
      </div>
      <span className={`mt-3 text-xs font-bold uppercase tracking-wider transition-colors ${
        isBroken ? 'text-red-400' : 'text-muted-foreground group-hover:text-white'
      }`}>
        {stage.label}
      </span>

      {/* Inspector Tooltip */}
      {isHovered && (
        <div className="absolute top-24 w-48 bg-[#0a0a0f] border border-[#1f1f28] rounded-xl p-3 shadow-2xl z-50 animate-in fade-in zoom-in-95 duration-200">
          <h4 className="text-xs font-bold text-white mb-2">{stage.label} Inspector</h4>
          <div className="flex justify-between text-xs mb-1">
            <span className="text-muted-foreground">Processed:</span>
            <span className="font-mono text-white">{stageTraces.length}</span>
          </div>
          <div className="flex justify-between text-xs mb-1">
            <span className="text-muted-foreground">Errors:</span>
            <span className={`font-mono ${errorCount > 0 ? 'text-red-400' : 'text-success'}`}>{errorCount}</span>
          </div>
          <div className="flex justify-between text-xs mb-1">
            <span className="text-muted-foreground">Avg Latency:</span>
            <span className="font-mono text-white">
              {stageTraces.length > 0 ? Math.round(stageTraces.reduce((a, b) => a + b.durationMs, 0) / stageTraces.length) : 0}ms
            </span>
          </div>
        </div>
      )}
    </div>
  );
}

function SignalIcon() {
  return (
    <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="text-blue-400">
      <path d="M2 20h.01"/><path d="M7 20v-4"/><path d="M12 20v-8"/><path d="M17 20V8"/><path d="M22 4v16"/>
    </svg>
  );
}
