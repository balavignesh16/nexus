import { Play, Pause, Activity } from 'lucide-react';
import { GlassPanel } from '../foundation/GlassPanel';
import { MetricCard } from '../foundation/MetricCard';
import { Button } from '../foundation/Button';
import { DataSourceBadge } from '../ui/DataSourceBadge';

interface PlatformObservabilityProps {
  metrics: { totalStored?: number; averageLatencyMs?: number; recentErrorCount?: number } | undefined;
  connection: any;
  isPaused: boolean;
  setIsPaused: (val: boolean) => void;
}

// Real pipeline metrics only (Step 6 audit, confirmed live against
// GET /pipeline/metrics): totalStored, averageLatencyMs, recentErrorCount.
// "Active Devices", "Slowest Stage", and "Platform Health" have been
// removed entirely - none exist in the real contract, and no replacement
// fabricated metric was introduced in their place.
export function PlatformObservability({ metrics, connection, isPaused, setIsPaused }: PlatformObservabilityProps) {
  return (
    <GlassPanel level="glass" className="p-4 flex flex-col gap-4 shrink-0">
      {/* Top Row: Title & Live/Paused (real, not "replay") */}
      <div className="flex items-center justify-between flex-wrap gap-3">
        <div className="flex items-center gap-3">
          <div
            className="w-9 h-9 rounded-[var(--radius-panel)] flex items-center justify-center"
            style={{ backgroundColor: 'color-mix(in srgb, var(--accent) 14%, transparent)', color: 'var(--accent)' }}
          >
            <Activity size={18} />
          </div>
          <div>
            <h1 className="text-lg font-bold text-foundation-fg tracking-tight">Pipeline Observability</h1>
            <p className="text-xs text-foundation-muted-fg">Real-time trace stream monitoring</p>
          </div>
        </div>

        <Button variant={isPaused ? 'outline' : 'secondary'} size="sm" onClick={() => setIsPaused(!isPaused)}>
          {isPaused ? <Play size={14} /> : <Pause size={14} />}
          {isPaused ? 'Paused' : 'Live'}
        </Button>
      </div>

      {/* Real metrics only */}
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3">
        <MetricCard label="Throughput" value={connection.messagesPerSec} unit="msg/s" badge={<DataSourceBadge classification="LIVE" variant="foundation" />} />
        <MetricCard
          label="Avg Latency"
          value={metrics?.averageLatencyMs !== undefined ? metrics.averageLatencyMs.toFixed(1) : '—'}
          unit={metrics?.averageLatencyMs !== undefined ? 'ms' : undefined}
          badge={<DataSourceBadge classification={metrics?.averageLatencyMs !== undefined ? 'CALCULATED' : 'UNAVAILABLE'} variant="foundation" />}
        />
        <MetricCard
          label="Recent Errors"
          value={metrics?.recentErrorCount ?? '—'}
          badge={<DataSourceBadge classification={metrics?.recentErrorCount !== undefined ? 'CALCULATED' : 'UNAVAILABLE'} variant="foundation" />}
        />
        <MetricCard label="Dropped Packets" value={connection.droppedMessages} badge={<DataSourceBadge classification="LIVE" variant="foundation" />} />
        <MetricCard
          label="Total Stored"
          value={metrics?.totalStored !== undefined ? metrics.totalStored.toLocaleString() : '—'}
          badge={<DataSourceBadge classification={metrics?.totalStored !== undefined ? 'CALCULATED' : 'UNAVAILABLE'} variant="foundation" />}
        />
      </div>
    </GlassPanel>
  );
}
