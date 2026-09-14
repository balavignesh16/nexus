import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useRepositories } from '../repositories/RepositoryProvider';
import { useConnectionStatus } from '../services/ConnectionManager';
import { PipelineCanvas } from '../components/pipeline/PipelineCanvas';
import { PlatformObservability } from '../components/pipeline/PlatformObservability';
import { PipelineTimeline } from '../components/pipeline/PipelineTimeline';
import { CorrelationInspector } from '../components/pipeline/CorrelationInspector';
import type { PipelineTracePayload } from '../repositories/types';

export default function PipelinePage() {
  const { pipeline } = useRepositories();
  const connection = useConnectionStatus();
  const [searchParams, setSearchParams] = useSearchParams();

  // Phase 3 (preserved): deep-linked investigation entry point from a real
  // event's "View Trace" link. If the correlationId turns out not to
  // exist, CorrelationInspector's own real empty state is shown.
  const [selectedCorrelationId, setSelectedCorrelationId] = useState<string | null>(
    () => searchParams.get('correlationId')
  );
  const [isPaused, setIsPaused] = useState(false);

  const selectCorrelation = (correlationId: string | null) => {
    setSelectedCorrelationId(correlationId);
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        if (correlationId) next.set('correlationId', correlationId);
        else next.delete('correlationId');
        return next;
      },
      { replace: true }
    );
  };

  // Real observability metrics (Step 6: only real fields are ever passed
  // to PlatformObservability - see that component for the exact contract).
  const { data: metrics } = useQuery({
    queryKey: ['pipeline-metrics'],
    queryFn: () => pipeline.getPipelineMetrics(),
    refetchInterval: connection.realtimeStatus === 'connected' ? false : 5000,
  });

  // Step 6C - historical backfill: a real, one-time fetch into the exact
  // same 'global-pipeline-traces' cache key RealtimeRepositorySync (owned
  // elsewhere, unmodified) already writes into via setQueryData. Once
  // seeded, RealtimeRepositorySync's prepend logic naturally continues
  // from this real starting point - no second state system, no new
  // polling (staleTime: Infinity means React Query fetches this exactly
  // once per mount and never again on its own).
  const {
    isLoading: historyLoading,
    error: historyError,
  } = useQuery<PipelineTracePayload[]>({
    queryKey: ['global-pipeline-traces'],
    queryFn: () => pipeline.getPipelineHistory(100),
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  });

  return (
    <div className="flex flex-col h-full overflow-hidden" style={{ backgroundColor: 'var(--foundation-bg)' }}>
      <div className="flex flex-col gap-4 p-4 sm:p-6 h-full overflow-hidden max-w-[1800px] w-full mx-auto">

        {/* Top: Observability */}
        <PlatformObservability
          metrics={metrics}
          connection={connection}
          isPaused={isPaused}
          setIsPaused={setIsPaused}
        />

        {/* Main investigation workspace */}
        <div className="flex-1 flex flex-col lg:flex-row gap-4 min-h-0 overflow-hidden">
          <div className="flex-1 flex flex-col gap-4 min-h-0 overflow-hidden">
            {/* Live Pipeline Canvas - real 3-stage visualization */}
            <div className="relative rounded-[var(--radius-panel-lg)] overflow-hidden shrink-0 h-[220px]" style={{ border: '1px solid var(--foundation-border)' }}>
              <PipelineCanvas
                isPaused={isPaused}
                onSelectCorrelation={selectCorrelation}
                selectedCorrelationId={selectedCorrelationId}
              />
            </div>

            {/* Recent traces */}
            <div className="flex-1 min-h-0">
              <PipelineTimeline
                onSelectCorrelation={selectCorrelation}
                isLoading={historyLoading}
                error={historyError}
              />
            </div>
          </div>

          {/* Investigation detail - full-width below lg, fixed sidebar above */}
          {selectedCorrelationId && (
            <div className="w-full lg:w-[380px] shrink-0 min-h-0" style={{ maxHeight: '100%' }}>
              <CorrelationInspector
                correlationId={selectedCorrelationId}
                onClose={() => selectCorrelation(null)}
              />
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
