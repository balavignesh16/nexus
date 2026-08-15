import { LineChart, Line, ResponsiveContainer, YAxis } from 'recharts';

interface MiniSparklineProps {
  data: number[];
  color?: string;
  height?: number;
}

export function MiniSparkline({ data, color = '#3b82f6', height = 40 }: MiniSparklineProps) {
  const chartData = data.map((value, index) => ({ index, value }));
  
  if (data.length === 0) {
    return <div className="flex items-center justify-center text-muted-foreground text-[10px]" style={{ height }}>No data</div>;
  }

  return (
    <div style={{ height, width: '100%' }}>
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={chartData}>
          <YAxis domain={['auto', 'auto']} hide />
          <Line 
            type="monotone" 
            dataKey="value" 
            stroke={color} 
            strokeWidth={2} 
            dot={false}
            isAnimationActive={false} // Disable animation for live sparklines to avoid flickering
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}
