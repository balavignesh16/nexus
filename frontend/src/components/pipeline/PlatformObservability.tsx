import { Play, Pause, Activity } from 'lucide-react';

interface PlatformObservabilityProps {
  metrics: any;
  connection: any;
  isPaused: boolean;
  setIsPaused: (val: boolean) => void;
  replaySpeed: number;
  setReplaySpeed: (val: number) => void;
}

export function PlatformObservability({ 
  metrics, connection, isPaused, setIsPaused, replaySpeed, setReplaySpeed 
}: PlatformObservabilityProps) {
  
  return (
    <div className="border-b border-[#1f1f28] bg-[#0a0a0f] p-4 flex flex-col gap-4">
      
      {/* Top Row: Title & Controls */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="w-8 h-8 rounded bg-blue-500/10 border border-blue-500/30 flex items-center justify-center">
            <Activity className="text-blue-400" size={18} />
          </div>
          <div>
            <h1 className="text-lg font-bold text-white tracking-tight">Platform Observability</h1>
            <p className="text-xs text-muted-foreground">Real-time enterprise pipeline monitoring</p>
          </div>
        </div>
        
        {/* Replay Controls */}
        <div className="flex items-center gap-2 bg-[#13131a] p-1 rounded-md border border-[#1f1f28]">
          <button 
            onClick={() => setIsPaused(!isPaused)}
            className={`px-3 py-1.5 rounded text-xs font-medium flex items-center gap-2 transition-colors ${
              isPaused ? 'bg-amber-500/20 text-amber-400' : 'hover:bg-[#1f1f28] text-muted-foreground hover:text-white'
            }`}
          >
            {isPaused ? <Play size={14} /> : <Pause size={14} />}
            {isPaused ? 'Paused' : 'Live'}
          </button>
          <div className="w-px h-4 bg-[#1f1f28] mx-1"></div>
          {[0.5, 1, 2, 4].map(speed => (
            <button
              key={speed}
              onClick={() => { setReplaySpeed(speed); setIsPaused(false); }}
              className={`px-2 py-1.5 rounded text-xs font-medium transition-colors ${
                replaySpeed === speed && !isPaused ? 'bg-[#1f1f28] text-white' : 'text-muted-foreground hover:text-white'
              }`}
            >
              {speed}x
            </button>
          ))}
        </div>
      </div>

      {/* Bottom Row: Metrics Cards */}
      <div className="grid grid-cols-4 md:grid-cols-8 gap-4">
        <MetricCard label="Throughput" value={`${connection.messagesPerSec}/s`} />
        <MetricCard label="Avg Latency" value={`${Math.round(metrics?.averageLatencyMs || connection.latencyMs || 0)}ms`} />
        <MetricCard label="Error Rate" value={`${metrics?.recentErrorCount || 0}`} trend={metrics?.recentErrorCount > 0 ? 'bad' : 'good'} />
        <MetricCard label="Active Devices" value="42" />
        <MetricCard label="Slowest Stage" value="Rules Engine" valueClass="text-amber-400" />
        <MetricCard label="Dropped Packets" value={connection.droppedMessages.toString()} />
        <MetricCard label="Platform Health" value="100%" valueClass="text-success" />
        <MetricCard label="Total Stored" value={metrics?.totalStored?.toLocaleString() || '0'} />
      </div>

    </div>
  );
}

function MetricCard({ label, value, trend, valueClass }: { label: string, value: string, trend?: 'good'|'bad', valueClass?: string }) {
  return (
    <div className="flex flex-col gap-1 border-r border-[#1f1f28] last:border-0 pr-4">
      <span className="text-[10px] uppercase tracking-wider text-muted-foreground font-semibold">{label}</span>
      <div className="flex items-center gap-2">
        <span className={`text-lg font-mono font-bold ${valueClass || 'text-white'}`}>
          {value}
        </span>
        {trend === 'bad' && <span className="w-1.5 h-1.5 rounded-full bg-red-500 animate-pulse"></span>}
      </div>
    </div>
  );
}
