import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useRepositories } from '../repositories/RepositoryProvider';
import { useConnectionStatus } from '../services/ConnectionManager';
import { PipelineCanvas } from '../components/pipeline/PipelineCanvas';
import { PlatformObservability } from '../components/pipeline/PlatformObservability';
import { PipelineTimeline } from '../components/pipeline/PipelineTimeline';
import { CorrelationInspector } from '../components/pipeline/CorrelationInspector';

export default function PipelinePage() {
  const { pipeline } = useRepositories();
  const connection = useConnectionStatus();
  const [searchParams, setSearchParams] = useSearchParams();

  // Phase 3: deep-linked investigation entry point - e.g. from a real
  // event's "View Trace" link (EventsPage.tsx). Opens the same real
  // CorrelationInspector a user would reach by clicking a live trace; if
  // the correlationId turns out not to exist, CorrelationInspector's own
  // real empty state ("No trace details found.") is shown - never fabricated.
  const [selectedCorrelationId, setSelectedCorrelationId] = useState<string | null>(
    () => searchParams.get('correlationId')
  );
  const [isPaused, setIsPaused] = useState(false);
  const [replaySpeed, setReplaySpeed] = useState<number>(1);

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

  // Fetch initial history or metrics
  const { data: metrics } = useQuery({
    queryKey: ['pipeline-metrics'],
    queryFn: () => pipeline.getPipelineMetrics(),
    refetchInterval: connection.realtimeStatus === 'connected' ? false : 5000,
  });

  return (
    <div className="flex flex-col h-full w-full bg-[#0a0a0f] text-foreground overflow-hidden">
      
      {/* Top: Observability Dashboard */}
      <div className="shrink-0">
        <PlatformObservability 
          metrics={metrics} 
          connection={connection}
          isPaused={isPaused}
          setIsPaused={setIsPaused}
          replaySpeed={replaySpeed}
          setReplaySpeed={setReplaySpeed}
        />
      </div>

      {/* Center: Live Pipeline Canvas */}
      <div className="flex-1 relative overflow-hidden">
        <PipelineCanvas
          isPaused={isPaused}
          replaySpeed={replaySpeed}
          onSelectCorrelation={selectCorrelation}
          selectedCorrelationId={selectedCorrelationId}
        />

        {/* Right Sidebar: Correlation Inspector */}
        {selectedCorrelationId && (
          <div className="absolute top-0 right-0 bottom-0 w-80 bg-[#13131a] border-l border-[#1f1f28] shadow-2xl z-10">
            <CorrelationInspector
              correlationId={selectedCorrelationId}
              onClose={() => selectCorrelation(null)}
            />
          </div>
        )}
      </div>

      {/* Bottom: Timeline */}
      <div className="h-64 shrink-0 border-t border-[#1f1f28] bg-[#0a0a0f]">
        <PipelineTimeline onSelectCorrelation={selectCorrelation} />
      </div>
      
    </div>
  );
}
