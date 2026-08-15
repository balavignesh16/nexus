import { useState } from 'react';
import { AreaChart, Area, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid } from 'recharts';
import { Maximize2, Download } from 'lucide-react';
import { LiveWidgetWrapper } from '../../live/LiveWidgetWrapper';

interface LiveTelemetryChartProps {
  data: any[];
  dataKey: string;
  timeKey?: string;
  title: string;
  color?: string;
  unit?: string;
}

export function LiveTelemetryChart({ 
  data, 
  dataKey, 
  timeKey = 'timestamp', 
  title,
  color = '#10b981',
  unit = ''
}: LiveTelemetryChartProps) {
  const [isPaused, setIsPaused] = useState(false);
  const [frozenData, setFrozenData] = useState<any[] | null>(null);

  const handlePauseToggle = () => {
    if (isPaused) {
      setFrozenData(null);
      setIsPaused(false);
    } else {
      setFrozenData([...data]);
      setIsPaused(true);
    }
  };

  const displayData = isPaused && frozenData ? frozenData : data;

  const handleExport = () => {
    if (displayData.length === 0) return;
    const headers = `${timeKey},${dataKey}\n`;
    const csv = displayData.map(d => `${d[timeKey]},${d[dataKey]}`).join('\n');
    const blob = new Blob([headers + csv], { type: 'text/csv' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${title.toLowerCase().replace(/\s+/g, '_')}_export.csv`;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <LiveWidgetWrapper 
      title={title} 
      state={isPaused ? 'PAUSED' : (data.length > 0 ? 'LIVE' : 'LOADING')}
      onPauseToggle={handlePauseToggle}
      actions={
        <div className="flex items-center gap-2 text-muted-foreground mr-2">
          <button onClick={handleExport} className="hover:text-foreground transition-colors" title="Export CSV">
            <Download size={12} />
          </button>
          <button className="hover:text-foreground transition-colors" title="Fullscreen">
            <Maximize2 size={12} />
          </button>
        </div>
      }
    >
      <div className="h-full w-full p-4 relative group">
        {displayData.length === 0 ? (
          <div className="absolute inset-0 flex items-center justify-center text-muted-foreground text-xs">
            Waiting for telemetry...
          </div>
        ) : (
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={displayData} margin={{ top: 5, right: 0, left: -20, bottom: 0 }}>
              <defs>
                <linearGradient id={`color-${dataKey}`} x1="0" y1="0" x2="0" y2="1">
                  <stop offset="5%" stopColor={color} stopOpacity={0.3}/>
                  <stop offset="95%" stopColor={color} stopOpacity={0}/>
                </linearGradient>
              </defs>
              <CartesianGrid strokeDasharray="3 3" stroke="#27272a" vertical={false} />
              <XAxis 
                dataKey={timeKey} 
                tickFormatter={(time) => new Date(time).toLocaleTimeString([], { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' })}
                stroke="#52525b"
                fontSize={10}
                tickMargin={8}
                minTickGap={30}
              />
              <YAxis 
                stroke="#52525b" 
                fontSize={10}
                tickFormatter={(val) => `${val}${unit}`}
              />
              <Tooltip 
                contentStyle={{ backgroundColor: '#09090b', borderColor: '#27272a', borderRadius: '6px', fontSize: '12px' }}
                labelFormatter={(time: any) => new Date(time).toLocaleString()}
                formatter={(val: any) => {
                  const numVal = typeof val === 'number' ? val : parseFloat(val);
                  return [`${numVal.toFixed(2)}${unit}`, title];
                }}
              />
              <Area 
                type="monotone" 
                dataKey={dataKey} 
                stroke={color} 
                strokeWidth={2}
                fillOpacity={1} 
                fill={`url(#color-${dataKey})`} 
                isAnimationActive={!isPaused}
                animationDuration={300}
              />
            </AreaChart>
          </ResponsiveContainer>
        )}
      </div>
    </LiveWidgetWrapper>
  );
}
