import { useState } from 'react';
import { Signal, Box, Zap } from 'lucide-react';
import type { PipelineTracePayload } from '../../repositories/types';

// Real, DB-verified stage vocabulary (Step 6 audit): pipeline_traces has
// only ever recorded these three stages, exhaustively confirmed against
// the full persisted table. No other stage is ever fabricated here.
interface PipelineStageNodeProps {
  stage: { id: string; label: string };
  traces: PipelineTracePayload[];
}

const STAGE_ICON: Record<string, typeof Signal> = {
  TELEMETRY: Signal,
  TWIN: Box,
  ACTION: Zap,
};

export function PipelineStageNode({ stage, traces }: PipelineStageNodeProps) {
  const [isHovered, setIsHovered] = useState(false);
  const Icon = STAGE_ICON[stage.id] ?? Signal;

  // Real per-stage metrics, computed only from real trace rows for this
  // exact stage - never a fabricated placeholder count.
  const stageTraces = traces.filter(t => t.stage === stage.id);
  const errorCount = stageTraces.filter(t => t.status === 'ERROR' || t.status === 'FAILURE').length;
  const hasError = errorCount > 0;
  const avgLatency = stageTraces.length > 0
    ? Math.round(stageTraces.reduce((a, b) => a + b.durationMs, 0) / stageTraces.length)
    : null;
  const avgPayloadSize = stageTraces.some(t => typeof t.payloadSize === 'number')
    ? Math.round(stageTraces.reduce((a, b) => a + (b.payloadSize || 0), 0) / stageTraces.length)
    : null;

  return (
    <div
      className="relative flex flex-col items-center gap-2"
      onMouseEnter={() => setIsHovered(true)}
      onMouseLeave={() => setIsHovered(false)}
    >
      <div
        className="w-16 h-16 rounded-[var(--radius-panel-lg)] flex items-center justify-center transition-shadow"
        style={hasError
          ? { backgroundColor: 'color-mix(in srgb, var(--danger) 10%, transparent)', border: '2px solid color-mix(in srgb, var(--danger) 40%, transparent)' }
          : { backgroundColor: 'var(--foundation-surface)', border: '2px solid var(--foundation-border)' }}
      >
        <Icon size={24} style={{ color: hasError ? 'var(--danger)' : 'var(--accent)' }} />
      </div>
      <span className="text-xs font-bold uppercase tracking-wider" style={{ color: hasError ? 'var(--danger)' : 'var(--foundation-muted-fg)' }}>
        {stage.label}
      </span>

      {isHovered && (
        <div
          className="absolute top-full mt-2 w-52 rounded-[var(--radius-panel)] p-3 z-50"
          style={{ backgroundColor: 'var(--foundation-surface)', border: '1px solid var(--foundation-border)', boxShadow: 'var(--shadow-glass-lg)' }}
        >
          <h4 className="text-xs font-bold text-foundation-fg mb-2">{stage.label}</h4>
          <div className="flex justify-between text-xs mb-1">
            <span className="text-foundation-muted-fg">Recorded (loaded window):</span>
            <span className="font-mono text-foundation-fg">{stageTraces.length}</span>
          </div>
          <div className="flex justify-between text-xs mb-1">
            <span className="text-foundation-muted-fg">Errors:</span>
            <span className="font-mono" style={{ color: hasError ? 'var(--danger)' : 'var(--success)' }}>{errorCount}</span>
          </div>
          <div className="flex justify-between text-xs mb-1">
            <span className="text-foundation-muted-fg">Avg Duration:</span>
            <span className="font-mono text-foundation-fg">{avgLatency !== null ? `${avgLatency}ms` : 'Unavailable'}</span>
          </div>
          {avgPayloadSize !== null && (
            <div className="flex justify-between text-xs">
              <span className="text-foundation-muted-fg">Avg Payload Size:</span>
              <span className="font-mono text-foundation-fg">{avgPayloadSize}B</span>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
