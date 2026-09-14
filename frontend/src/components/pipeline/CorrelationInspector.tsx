import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { X, Clock, Activity, Hash, Zap, Info, AlertTriangle, AlertCircle, Sparkles } from 'lucide-react';
import { useRepositories } from '../../repositories/RepositoryProvider';
import { aiApi } from '../../api/aiApi';
import type { PipelineTracePayload } from '../../repositories/types';
import { GlassPanel } from '../foundation/GlassPanel';
import { StatusIndicator } from '../foundation/StatusIndicator';
import { EmptyState } from '../foundation/EmptyState';
import { ErrorState } from '../foundation/ErrorState';
import { LoadingBlock } from '../foundation/LoadingState';

export function CorrelationInspector({ correlationId, onClose }: { correlationId: string; onClose: () => void }) {
  const { pipeline, events } = useRepositories();

  // Real fetch, unchanged contract (getPipelineByCorrelationId) - only
  // scoped to this one correlationId, never the whole trace history.
  const { data: rawTraces = [], isLoading, error } = useQuery<PipelineTracePayload[]>({
    queryKey: ['correlation', correlationId],
    queryFn: () => pipeline.getPipelineByCorrelationId(correlationId),
  });

  // Real ordering, derived from each trace's own stageIndex - never
  // assumed from response array order.
  const traces = useMemo(() => [...rawTraces].sort((a, b) => a.stageIndex - b.stageIndex), [rawTraces]);

  const totalDuration = traces.reduce((acc, t) => acc + t.durationMs, 0);
  const deviceId = traces.find(t => t.deviceId)?.deviceId ?? null;
  const hasErrors = traces.some(t => t.status === 'ERROR' || t.status === 'FAILURE');

  // Related Events - one correlation-scoped real request (Step 6), never
  // the full event history filtered client-side.
  const { data: relatedEvents = [], isLoading: eventsLoading, error: eventsError } = useQuery({
    queryKey: ['correlation-events', correlationId],
    queryFn: () => events.getEvents(undefined, 20, correlationId),
  });

  // Related Rule Matches - the same real, already-established API
  // (AiInsightsPage / RulesTab / RulesPage), scoped to this correlationId.
  const { data: relatedMatches = [], isLoading: matchesLoading, error: matchesError } = useQuery({
    queryKey: ['correlation-rule-matches', correlationId],
    queryFn: () => aiApi.getRuleMatchHistory(20, undefined, correlationId),
  });

  const getEventIcon = (severity: string) => {
    if (severity === 'CRITICAL') return <AlertCircle size={13} style={{ color: 'var(--danger)' }} />;
    if (severity === 'WARNING') return <AlertTriangle size={13} style={{ color: 'var(--warning)' }} />;
    return <Info size={13} style={{ color: 'var(--accent)' }} />;
  };

  return (
    <GlassPanel level="glass-strong" radius="panel-lg" className="h-full flex flex-col overflow-hidden">
      {/* Header */}
      <div className="shrink-0 p-4 border-b border-foundation-border flex items-start justify-between gap-2">
        <div className="min-w-0">
          <h3 className="text-sm font-bold text-foundation-fg flex items-center gap-2">
            <Activity size={16} style={{ color: 'var(--accent)' }} />
            Trace Investigation
          </h3>
          <p className="text-[10px] font-mono text-foundation-muted-fg mt-1 break-all">{correlationId}</p>
        </div>
        <button onClick={onClose} className="p-1.5 rounded-[var(--radius-panel)] text-foundation-muted-fg hover:text-foundation-fg hover:bg-black/[0.04] transition-colors shrink-0">
          <X size={16} />
        </button>
      </div>

      {isLoading ? (
        <div className="p-4"><LoadingBlock className="h-40" /></div>
      ) : error ? (
        <div className="p-6"><ErrorState message="Could not load trace details." /></div>
      ) : traces.length === 0 ? (
        <div className="p-6"><EmptyState icon={<Activity size={28} />} title="No trace details found." /></div>
      ) : (
        <div className="flex-1 overflow-y-auto custom-scrollbar">
          {/* Summary */}
          <div className="shrink-0 p-4 border-b border-foundation-border flex flex-col gap-2.5">
            <div className="flex justify-between items-center text-xs">
              <span className="text-foundation-muted-fg">Device ID</span>
              <span className="font-mono text-foundation-fg">{deviceId ?? 'System'}</span>
            </div>
            <div className="flex justify-between items-center text-xs">
              <span className="text-foundation-muted-fg">Total Duration</span>
              <span className="font-mono text-foundation-fg flex items-center gap-1"><Clock size={12} /> {totalDuration}ms</span>
            </div>
            <div className="flex justify-between items-center text-xs">
              <span className="text-foundation-muted-fg">Trace Status</span>
              <StatusIndicator tone={hasErrors ? 'offline' : 'online'} label={hasErrors ? 'FAILED' : 'SUCCESS'} />
            </div>
          </div>

          {/* Real stage sequence - TELEMETRY / TWIN / ACTION only, ordered
              by real stageIndex */}
          <div className="p-4 border-b border-foundation-border">
            <h4 className="text-[10px] font-bold uppercase tracking-wider text-foundation-muted-fg mb-3">Stage Sequence</h4>
            <div className="flex flex-col">
              {traces.map((trace, i) => (
                <div key={trace.traceId} className="flex gap-3">
                  <div className="flex flex-col items-center">
                    <div
                      className="w-2.5 h-2.5 rounded-full shrink-0"
                      style={{ backgroundColor: trace.status === 'SUCCESS' ? 'var(--success)' : 'var(--danger)' }}
                    />
                    {i < traces.length - 1 && <div className="w-px flex-1 min-h-[24px]" style={{ backgroundColor: 'var(--foundation-border)' }} />}
                  </div>
                  <div className="flex-1 pb-4 min-w-0">
                    <div className="flex items-center justify-between mb-0.5">
                      <span className="text-xs font-bold text-foundation-fg">{trace.stage}</span>
                      <span className="text-[10px] font-mono text-foundation-muted-fg">{trace.durationMs}ms</span>
                    </div>
                    <p className="text-[10px] text-foundation-tertiary-fg font-mono">
                      {new Date(trace.startTimestamp).toLocaleTimeString()}
                      {typeof trace.payloadSize === 'number' && ` · ${trace.payloadSize}B`}
                    </p>
                    {trace.metadata && Object.keys(trace.metadata).length > 0 && (
                      <div className="rounded-[var(--radius-panel)] p-2 mt-2" style={{ backgroundColor: 'var(--foundation-bg)', border: '1px solid var(--foundation-border)' }}>
                        <pre className="text-[10px] font-mono text-foundation-muted-fg overflow-x-auto">{JSON.stringify(trace.metadata, null, 2)}</pre>
                      </div>
                    )}
                  </div>
                </div>
              ))}
            </div>
          </div>

          {/* Related Events - a related record, never a fourth pipeline stage */}
          <div className="p-4 border-b border-foundation-border">
            <h4 className="text-[10px] font-bold uppercase tracking-wider text-foundation-muted-fg mb-3 flex items-center gap-1.5">
              <Hash size={11} /> Related Events
            </h4>
            {eventsLoading ? (
              <LoadingBlock className="h-12" />
            ) : eventsError ? (
              <p className="text-xs" style={{ color: 'var(--danger)' }}>Could not load related events.</p>
            ) : relatedEvents.length === 0 ? (
              <p className="text-xs text-foundation-tertiary-fg">No events recorded for this correlation.</p>
            ) : (
              <div className="flex flex-col gap-2">
                {relatedEvents.map((e: any) => (
                  <div key={e.eventId} className="flex items-center gap-2 text-xs">
                    {getEventIcon(e.severity)}
                    <span className="text-foundation-fg truncate flex-1">{e.eventType}</span>
                    <span className="text-foundation-tertiary-fg font-mono text-[10px] shrink-0">{new Date(e.timestamp).toLocaleTimeString()}</span>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* Related Rule Matches - a rule's conditions were satisfied,
              never called a "decision". Links to the existing real AI
              explanation route. */}
          <div className="p-4">
            <h4 className="text-[10px] font-bold uppercase tracking-wider text-foundation-muted-fg mb-3 flex items-center gap-1.5">
              <Sparkles size={11} /> Related Rule Matches
            </h4>
            {matchesLoading ? (
              <LoadingBlock className="h-12" />
            ) : matchesError ? (
              <p className="text-xs" style={{ color: 'var(--danger)' }}>Could not load related rule matches.</p>
            ) : relatedMatches.length === 0 ? (
              <p className="text-xs text-foundation-tertiary-fg">No rule conditions were satisfied for this correlation.</p>
            ) : (
              <div className="flex flex-col gap-2">
                {relatedMatches.map((m: any) => (
                  <Link
                    key={m.matchId}
                    to={`/ai?matchId=${m.matchId}`}
                    className="flex items-center justify-between gap-2 rounded-[var(--radius-panel)] px-3 py-2 text-xs transition-colors"
                    style={{ backgroundColor: 'var(--foundation-surface)', border: '1px solid var(--foundation-border)' }}
                  >
                    <span className="flex items-center gap-1.5 text-foundation-fg truncate">
                      <Zap size={11} style={{ color: 'var(--accent)' }} />
                      {m.ruleName ?? 'Unnamed rule'} matched
                    </span>
                    <span className="text-[10px] text-foundation-muted-fg shrink-0">{new Date(m.matchedAt).toLocaleTimeString()}</span>
                  </Link>
                ))}
              </div>
            )}
          </div>
        </div>
      )}
    </GlassPanel>
  );
}
