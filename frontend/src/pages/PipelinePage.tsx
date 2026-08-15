import { useState } from 'react';
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
  
  const [selectedCorrelationId, setSelectedCorrelationId] = useState<string | null>(null);
  const [isPaused, setIsPaused] = useState(false);
  const [replaySpeed, setReplaySpeed] = useState<number>(1);

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
          onSelectCorrelation={setSelectedCorrelationId} 
          selectedCorrelationId={selectedCorrelationId}
        />
        
        {/* Right Sidebar: Correlation Inspector */}
        {selectedCorrelationId && (
          <div className="absolute top-0 right-0 bottom-0 w-80 bg-[#13131a] border-l border-[#1f1f28] shadow-2xl z-10">
            <CorrelationInspector 
              correlationId={selectedCorrelationId} 
              onClose={() => setSelectedCorrelationId(null)} 
            />
          </div>
        )}
      </div>

      {/* Bottom: Timeline */}
      <div className="h-64 shrink-0 border-t border-[#1f1f28] bg-[#0a0a0f]">
        <PipelineTimeline onSelectCorrelation={setSelectedCorrelationId} />
      </div>
      
    </div>
  );
}
