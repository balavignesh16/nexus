import { useState, useMemo } from 'react';
import { useRepositories } from '../repositories/RepositoryProvider';
import { usePolling } from '../hooks/usePolling';
import { Signal, Download, Calendar, Activity, Database, Clock, RefreshCw, BarChart2 } from 'lucide-react';
import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { Skeleton } from '../components/ui/Skeleton';

type ChartPoint = { time: string; value: number; timestamp: number };

export default function TelemetryPage() {
  const { device } = useRepositories();
  const { data: devices = [], isLoading: isLoadingDevices } = usePolling(
    ['all-devices'],
    () => device.getDevices(),
    { interval: 10000 }
  );

  const [selectedDevice, setSelectedDevice] = useState<string>('');
  const [selectedMetric, setSelectedMetric] = useState<string>('TEMPERATURE_SENSOR');
  const [isLive, setIsLive] = useState(true);
  const [windowSize, setWindowSize] = useState(200);

  // Default to first device if none selected
  const activeDeviceId = selectedDevice || (devices.length > 0 ? devices[0].id : null);

  const { telemetry } = useRepositories();
  const { data: telemetryData = [], isLoading: isLoadingTelemetry } = usePolling(
    ['telemetry-page', activeDeviceId, selectedMetric],
    () => activeDeviceId ? telemetry.getTelemetryHistory(activeDeviceId) : Promise.resolve([]),
    { interval: 5000 }
  );

  const chartData = useMemo<ChartPoint[]>(() => {
    if (!telemetryData || telemetryData.length === 0) return [];
    
    // Filter by metric (some repositories might return all metrics)
    const filtered = telemetryData.filter(t => t.payload && t.payload[selectedMetric] !== undefined);
    
    // Sort chronologically for chart
    return [...filtered]
      .sort((a, b) => a.timestamp - b.timestamp)
      .map(t => ({
        time: new Date(t.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }),
        value: Number(t.payload[selectedMetric]) || 0,
        timestamp: t.timestamp,
      }));
  }, [telemetryData, selectedMetric]);

  const displayedChartData = useMemo(() => chartData.slice(-windowSize), [chartData, windowSize]);

  const stats = useMemo(() => {
    if (displayedChartData.length === 0) return { min: 0, max: 0, avg: 0, current: 0 };
    const values = displayedChartData.map(d => d.value);
    return {
      min: Math.min(...values).toFixed(1),
      max: Math.max(...values).toFixed(1),
      avg: (values.reduce((a, b) => a + b, 0) / values.length).toFixed(1),
      current: values[values.length - 1].toFixed(1)
    };
  }, [displayedChartData]);

  const isLoading = isLoadingDevices || (isLoadingTelemetry && chartData.length === 0);

  if (isLoading && devices.length === 0) {
    return (
      <div className="p-8 h-full">
        <Skeleton className="w-full h-full rounded-xl" />
      </div>
    );
  }

  return (
    <div className="flex-1 flex flex-col h-full bg-[#060b14] overflow-hidden text-foreground animate-in fade-in duration-300">
      
      {/* TOOLBAR */}
      <div className="shrink-0 h-16 border-b border-[#1f1f28] flex items-center justify-between px-6 bg-[#0a0a0f]">
        <div className="flex items-center gap-3">
          <div className="p-2 bg-blue-500/10 rounded-lg">
            <Signal size={20} className="text-blue-400" />
          </div>
          <div>
            <h1 className="text-lg font-bold">Telemetry Explorer</h1>
            <p className="text-xs text-muted-foreground">Real-time metric visualization</p>
          </div>
        </div>

        <div className="flex items-center gap-4">
          <select 
            className="bg-[#13131a] border border-[#1f1f28] rounded-md text-sm px-3 py-1.5 focus:outline-none focus:border-blue-500"
            value={activeDeviceId || ''}
            onChange={e => { setSelectedDevice(e.target.value); }}
          >
            {devices.map(d => (
              <option key={d.id} value={d.id}>{d.name}</option>
            ))}
          </select>
          
          <select 
            className="bg-[#13131a] border border-[#1f1f28] rounded-md text-sm px-3 py-1.5 focus:outline-none focus:border-blue-500 capitalize"
            value={selectedMetric}
            onChange={e => { setSelectedMetric(e.target.value); }}
          >
            <option value="TEMPERATURE_SENSOR">Temperature Sensor</option>
            <option value="HUMIDITY_SENSOR">Humidity Sensor</option>
            <option value="GAS_SENSOR">Gas Sensor</option>
            <option value="MOTION_SENSOR">Motion Sensor</option>
          </select>

          <div className="h-6 w-px bg-[#1f1f28]"></div>

          <select value={windowSize} onChange={e => setWindowSize(Number(e.target.value))} className="bg-[#13131a] border border-[#1f1f28] rounded-md text-sm px-3 py-1.5">
            {[200, 500, 1000, 5000].map(size => <option key={size} value={size}>Window: {size}</option>)}
          </select>
          <button className="flex items-center gap-2 px-3 py-1.5 bg-[#13131a] border border-[#1f1f28] rounded-md text-sm text-muted-foreground hover:text-foreground">
            <Calendar size={14} /> Last 1 Hour
          </button>
          
          <button 
            onClick={() => setIsLive(!isLive)}
            className={`flex items-center gap-2 px-3 py-1.5 border rounded-md text-sm font-medium transition-colors ${isLive ? 'bg-blue-500/10 border-blue-500/30 text-blue-400' : 'bg-[#13131a] border-[#1f1f28] text-muted-foreground'}`}
          >
            {isLive ? (
              <><div className="w-2 h-2 rounded-full bg-blue-500 animate-pulse"></div> Live</>
            ) : (
              <><Clock size={14} /> Paused</>
            )}
          </button>
          
          <button className="flex items-center gap-2 px-3 py-1.5 bg-[#13131a] border border-[#1f1f28] rounded-md text-sm text-muted-foreground hover:text-foreground">
            <Download size={14} /> Export
          </button>
        </div>
      </div>

      <div className="flex-1 p-6 flex flex-col gap-6 overflow-y-auto custom-scrollbar">
        
        {/* KPI CARDS */}
        <div className="grid grid-cols-4 gap-4 shrink-0">
          {[
            { label: 'Current Value', value: stats.current, unit: '', icon: Activity, color: 'text-blue-400' },
            { label: 'Average', value: stats.avg, unit: '', icon: BarChart2, color: 'text-purple-400' },
            { label: 'Minimum', value: stats.min, unit: '', icon: Signal, color: 'text-success' },
            { label: 'Maximum', value: stats.max, unit: '', icon: Database, color: 'text-warning' },
          ].map((stat, idx) => (
            <div key={idx} className="bg-card/50 backdrop-blur border border-border/50 rounded-xl p-5 flex items-center gap-4">
              <div className={`p-3 rounded-lg bg-[#13131a] border border-[#1f1f28] ${stat.color}`}>
                <stat.icon size={20} />
              </div>
              <div>
                <p className="text-xs text-muted-foreground font-medium uppercase tracking-wider">{stat.label}</p>
                <div className="flex items-baseline gap-1 mt-1">
                  <span className="text-2xl font-bold font-mono">{stat.value}</span>
                  <span className="text-sm text-muted-foreground">{stat.unit}</span>
                </div>
              </div>
            </div>
          ))}
        </div>

        {/* CHART SECTION */}
        <div className="flex-1 min-h-[400px] bg-card/50 backdrop-blur border border-border/50 rounded-xl p-6 flex flex-col">
          <div className="flex items-center justify-between mb-6">
            <h3 className="font-semibold capitalize">{selectedMetric} Trend</h3>
            <div className="flex items-center gap-2 text-xs text-muted-foreground">
              <RefreshCw size={12} className={isLive ? 'animate-spin' : ''} />
              {displayedChartData.length} points
            </div>
          </div>
          <div className="flex-1 min-h-0">
            {displayedChartData.length > 0 ? (
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={displayedChartData} margin={{ top: 10, right: 10, left: -20, bottom: 0 }}>
                  <defs>
                    <linearGradient id="colorValue" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="#3b82f6" stopOpacity={0.3}/>
                      <stop offset="95%" stopColor="#3b82f6" stopOpacity={0}/>
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="3 3" stroke="#1f1f28" vertical={false} />
                  <XAxis 
                    dataKey="time" 
                    stroke="#52525b" 
                    fontSize={10} 
                    tickMargin={10}
                    minTickGap={30}
                  />
                  <YAxis 
                    stroke="#52525b" 
                    fontSize={10}
                    tickFormatter={(val) => val.toFixed(0)}
                  />
                  <Tooltip 
                    contentStyle={{ backgroundColor: '#0a0a0f', borderColor: '#1f1f28', borderRadius: '8px', fontSize: '12px' }}
                    itemStyle={{ color: '#60a5fa' }}
                    labelStyle={{ color: '#a1a1aa', marginBottom: '4px' }}
                  />
                  <Area 
                    type="monotone" 
                    dataKey="value" 
                    stroke="#3b82f6" 
                    strokeWidth={2}
                    fillOpacity={1} 
                    fill="url(#colorValue)" 
                    isAnimationActive={false}
                  />
                </AreaChart>
              </ResponsiveContainer>
            ) : (
              <div className="w-full h-full flex flex-col items-center justify-center text-muted-foreground">
                <Activity size={48} className="opacity-20 mb-4" />
                <p>No telemetry data available for this metric.</p>
              </div>
            )}
          </div>
        </div>

      </div>
    </div>
  );
}
