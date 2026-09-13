import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import type { AnalyticsTimeSeries } from '../../api/analyticsApi';

interface TimeSeriesChartProps {
  data: AnalyticsTimeSeries;
  color: string;
  unit?: string;
}

/** A real time-bucketed count chart - every point is a database GROUP BY result, never client-generated. */
export function TimeSeriesChart({ data, color, unit }: TimeSeriesChartProps) {
  const points = data.points.map((p) => ({
    time: new Date(p.timestamp).toLocaleString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }),
    value: p.value,
  }));

  return (
    <div className="h-40 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={points} margin={{ top: 4, right: 8, left: -20, bottom: 0 }}>
          <defs>
            <linearGradient id={`grad-${color.replace('#', '')}`} x1="0" y1="0" x2="0" y2="1">
              <stop offset="5%" stopColor={color} stopOpacity={0.3} />
              <stop offset="95%" stopColor={color} stopOpacity={0} />
            </linearGradient>
          </defs>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--surface-border)" vertical={false} />
          <XAxis dataKey="time" stroke="var(--muted-foreground)" fontSize={10} tickLine={false} axisLine={false} minTickGap={40} />
          <YAxis stroke="var(--muted-foreground)" fontSize={10} tickLine={false} axisLine={false} allowDecimals={false} />
          <Tooltip
            contentStyle={{ backgroundColor: 'var(--sunken)', borderColor: 'var(--surface-border)', borderRadius: 8, fontSize: 12 }}
            formatter={(value: number) => [`${value}${unit ? ` ${unit}` : ''}`, 'Count']}
          />
          <Area type="monotone" dataKey="value" stroke={color} strokeWidth={2} fillOpacity={1} fill={`url(#grad-${color.replace('#', '')})`} />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}
