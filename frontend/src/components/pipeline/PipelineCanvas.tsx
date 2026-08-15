import { useQuery } from '@tanstack/react-query';
import { motion, AnimatePresence } from 'framer-motion';
import type { PipelineTracePayload } from '../../repositories/types';
import { PipelineStageNode } from './PipelineStageNode';

interface PipelineCanvasProps {
  isPaused: boolean;
  replaySpeed: number;
  onSelectCorrelation: (id: string) => void;
  selectedCorrelationId: string | null;
}

const STAGES = [
  { id: 'MQTT_IN', label: 'MQTT Gateway', x: '10%' },
  { id: 'TELEMETRY', label: 'Telemetry', x: '25%' },
  { id: 'TWIN', label: 'Digital Twin', x: '40%' },
  { id: 'RULE', label: 'Rules Engine', x: '55%' },
  { id: 'ACTION', label: 'Action Engine', x: '70%' },
  { id: 'ALERT', label: 'Alert Manager', x: '85%' },
  { id: 'ANALYTICS', label: 'Analytics', x: '100%' }, // Not fully rendered physically, just off screen
];

export function PipelineCanvas({ isPaused, selectedCorrelationId }: PipelineCanvasProps) {
  
  // React query data is already updated by RealtimeRepositorySync
  const { data: traces = [] } = useQuery<PipelineTracePayload[]>({
    queryKey: ['global-pipeline-traces'],
    staleTime: Infinity, // handled by realtime
  });

  // Take recent traces for animation
  const activeTraces = isPaused ? [] : traces.slice(0, 50).filter(t => t.endTimestamp > Date.now() - 5000);

  return (
    <div className="absolute inset-0 bg-[#060b14] overflow-hidden">
      
      {/* Grid Background */}
      <div className="absolute inset-0 bg-[linear-gradient(to_right,#1f1f28_1px,transparent_1px),linear-gradient(to_bottom,#1f1f28_1px,transparent_1px)] bg-[size:40px_40px] opacity-20"></div>

      {/* Connection Lines between stages */}
      <svg className="absolute inset-0 w-full h-full pointer-events-none" style={{ zIndex: 0 }}>
        {STAGES.slice(0, -2).map((stage, i) => (
          <line 
            key={i}
            x1={stage.x} 
            y1="50%" 
            x2={STAGES[i+1].x} 
            y2="50%" 
            stroke="#1f1f28" 
            strokeWidth="2"
            strokeDasharray="4 4"
          />
        ))}
      </svg>

      {/* Stages */}
      <div className="absolute inset-0 flex items-center w-full" style={{ zIndex: 10 }}>
        {STAGES.slice(0, -1).map(stage => (
          <PipelineStageNode key={stage.id} stage={stage} traces={traces} />
        ))}
      </div>

      {/* Packets (Traces) */}
      <div className="absolute inset-0 pointer-events-none" style={{ zIndex: 20 }}>
        <AnimatePresence>
          {activeTraces.map(trace => {
            const currentStageIdx = STAGES.findIndex(s => s.id === trace.stage);
            if (currentStageIdx === -1) return null;
            
            const x = STAGES[currentStageIdx].x;
            const isError = trace.status === 'ERROR' || trace.status === 'FAILURE';
            const isSelected = trace.correlationId === selectedCorrelationId;

            return (
              <motion.div
                key={trace.traceId}
                initial={{ left: currentStageIdx > 0 ? STAGES[currentStageIdx - 1].x : '0%', top: '50%', opacity: 0, scale: 0.5 }}
                animate={{ left: x, top: '50%', opacity: 1, scale: isSelected ? 1.5 : 1 }}
                exit={{ opacity: 0, scale: 0 }}
                transition={{ duration: 0.5, ease: 'easeOut' }}
                className={`absolute w-3 h-3 -mt-1.5 -ml-1.5 rounded-full ${isError ? 'bg-red-500 shadow-[0_0_10px_red]' : 'bg-blue-400 shadow-[0_0_10px_#60a5fa]'}`}
                style={{ zIndex: isSelected ? 50 : 20 }}
              />
            );
          })}
        </AnimatePresence>
      </div>
    </div>
  );
}
