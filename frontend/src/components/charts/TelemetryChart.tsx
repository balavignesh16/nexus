import { useEffect, useState } from 'react';
import { AreaChart, Area, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';

// Simulates or tracks real telemetry rate per second.
// In a true prod app, this would use WebSockets or a dedicated time-series backend endpoint.
export function TelemetryChart() {
  const [data, setData] = useState<{ time: string, rate: number }[]>([]);

  useEffect(() => {
    // Initialize with 30 data points (mock historical data for immediate visual impact)
    const initialData = Array.from({ length: 30 }).map((_, i) => {
      const d = new Date();
      d.setSeconds(d.getSeconds() - (30 - i));
      return {
        time: d.toLocaleTimeString([], { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' }),
        rate: Math.floor(Math.random() * 20) + 10 // Mock baseline 10-30 msg/sec
      };
    });
    setData(initialData);

    const interval = setInterval(() => {
      setData(prev => {
        const newData = [...prev];
        if (newData.length >= 30) newData.shift(); // Keep last 30 seconds
        
        const d = new Date();
        // Fluctuate around previous value to look realistic
        const lastRate = newData[newData.length - 1]?.rate || 15;
        const change = Math.floor(Math.random() * 11) - 5; // -5 to +5
        let newRate = lastRate + change;
        if (newRate < 5) newRate = 5;
        if (newRate > 100) newRate = 100;

        newData.push({
          time: d.toLocaleTimeString([], { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' }),
          rate: newRate
        });
        return newData;
      });
    }, 1000); // Update every second

    return () => clearInterval(interval);
  }, []);

  return (
    <div className="flex-1 w-full h-full min-h-[200px]">
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={data} margin={{ top: 5, right: 0, left: -20, bottom: 0 }}>
          <defs>
            <linearGradient id="colorRate" x1="0" y1="0" x2="0" y2="1">
              <stop offset="5%" stopColor="#2563EB" stopOpacity={0.3}/>
              <stop offset="95%" stopColor="#2563EB" stopOpacity={0}/>
            </linearGradient>
          </defs>
          <XAxis 
            dataKey="time" 
            axisLine={false} 
            tickLine={false} 
            tick={{ fill: '#A1A1AA', fontSize: 10 }}
            minTickGap={30}
          />
          <YAxis 
            axisLine={false} 
            tickLine={false} 
            tick={{ fill: '#A1A1AA', fontSize: 10 }} 
          />
          <Tooltip 
            contentStyle={{ backgroundColor: '#111113', borderColor: '#27272A', color: '#FAFAFA' }}
            itemStyle={{ color: '#2563EB' }}
          />
          <Area 
            type="monotone" 
            dataKey="rate" 
            stroke="#2563EB" 
            strokeWidth={2}
            fillOpacity={1} 
            fill="url(#colorRate)" 
            isAnimationActive={false}
          />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}
