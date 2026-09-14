import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { motion, AnimatePresence } from 'framer-motion';
import type { PipelineTracePayload } from '../../repositories/types';
import { PipelineStageNode } from './PipelineStageNode';

interface PipelineCanvasProps {
  isPaused: boolean;
  onSelectCorrelation: (id: string) => void;
  selectedCorrelationId: string | null;
}

// Real, DB-verified stage vocabulary (Step 6 audit) - the only three stage
// values that have ever been persisted in pipeline_traces. Order is
// re-derived from each trace's real stageIndex below, never assumed from
// this array's own position; this array only supplies the canonical
// fallback order before any real data has loaded.
const REAL_STAGES: { id: string; label: string }[] = [
  { id: 'TELEMETRY', label: 'Telemetry' },
  { id: 'TWIN', label: 'Digital Twin' },
  { id: 'ACTION', label: 'Action' },
];

const POSITIONS = ['18%', '50%', '82%'];

export function PipelineCanvas({ isPaused, selectedCorrelationId }: PipelineCanvasProps) {
  // React query data is already updated by RealtimeRepositorySync and
  // seeded once with real history by PipelinePage - this component only
  // reads the shared cache.
  const { data: traces = [] } = useQuery<PipelineTracePayload[]>({
    queryKey: ['global-pipeline-traces'],
    staleTime: Infinity, // handled by realtime + the one-time history seed
  });

  // Real stage ordering, derived from each stage's minimum observed
  // stageIndex in the currently loaded traces - never assumed from array
  // position. Falls back to the canonical real order when no data has
  // loaded yet for a given stage.
  const orderedStages = useMemo(() => {
    const minIndex = new Map<string, number>();
    for (const t of traces) {
      const current = minIndex.get(t.stage);
      if (current === undefined || t.stageIndex < current) minIndex.set(t.stage, t.stageIndex);
    }
    return [...REAL_STAGES].sort((a, b) => {
      const ai = minIndex.get(a.id);
      const bi = minIndex.get(b.id);
      if (ai === undefined && bi === undefined) return 0;
      if (ai === undefined) return 1;
      if (bi === undefined) return -1;
      return ai - bi;
    });
  }, [traces]);

  const stagePosition = (stageId: string) => {
    const idx = orderedStages.findIndex(s => s.id === stageId);
    return idx === -1 ? null : POSITIONS[idx];
  };

  // Only real, currently-in-flight traces (within the last 5s) are
  // animated - never a fabricated packet.
  const activeTraces = isPaused ? [] : traces.slice(0, 50).filter(t => t.endTimestamp > Date.now() - 5000);

  return (
    <div className="absolute inset-0 overflow-hidden" style={{ backgroundColor: 'var(--foundation-bg)' }}>
      {/* Connection lines between the real stages only */}
      <svg className="absolute inset-0 w-full h-full pointer-events-none" style={{ zIndex: 0 }}>
        {orderedStages.slice(0, -1).map((stage, i) => (
          <line
            key={stage.id}
            x1={POSITIONS[i]}
            y1="50%"
            x2={POSITIONS[i + 1]}
            y2="50%"
            stroke="var(--foundation-border)"
            strokeWidth="2"
            strokeDasharray="4 4"
          />
        ))}
      </svg>

      {/* Real stages */}
      <div className="absolute inset-0 flex items-center w-full" style={{ zIndex: 10 }}>
        {orderedStages.map((stage, i) => (
          <div key={stage.id} className="absolute -translate-x-1/2 -translate-y-1/2" style={{ left: POSITIONS[i], top: '50%' }}>
            <PipelineStageNode stage={stage} traces={traces} />
          </div>
        ))}
      </div>

      {/* Live trace packets - real data only */}
      <div className="absolute inset-0 pointer-events-none" style={{ zIndex: 20 }}>
        <AnimatePresence>
          {activeTraces.map(trace => {
            const x = stagePosition(trace.stage);
            if (x === null) return null;
            const isError = trace.status === 'ERROR' || trace.status === 'FAILURE';
            const isSelected = trace.correlationId === selectedCorrelationId;
            const stageIdx = orderedStages.findIndex(s => s.id === trace.stage);
            const prevX = stageIdx > 0 ? POSITIONS[stageIdx - 1] : x;

            return (
              <motion.div
                key={trace.traceId}
                initial={{ left: prevX, top: '50%', opacity: 0, scale: 0.5 }}
                animate={{ left: x, top: '50%', opacity: 1, scale: isSelected ? 1.6 : 1 }}
                exit={{ opacity: 0, scale: 0 }}
                transition={{ duration: 0.5, ease: 'easeOut' }}
                className="absolute w-3 h-3 -mt-1.5 -ml-1.5 rounded-full"
                style={{
                  backgroundColor: isError ? 'var(--danger)' : 'var(--accent)',
                  boxShadow: isError ? '0 0 8px color-mix(in srgb, var(--danger) 60%, transparent)' : '0 0 8px color-mix(in srgb, var(--accent) 60%, transparent)',
                  zIndex: isSelected ? 50 : 20,
                }}
              />
            );
          })}
        </AnimatePresence>
      </div>
    </div>
  );
}
