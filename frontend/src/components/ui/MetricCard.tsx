interface MetricCardProps {
  label: string;
  value: string | number;
  unit: string;
  trend: string;
  trendLabel: string;
  color: string;
  stroke: string;
  type?: 'sparkline' | 'progress';
}

export function MetricCard({ label, value, unit, trend, trendLabel, color, stroke, type = 'sparkline' }: MetricCardProps) {
  return (
    <div className="bg-[#13131a] border border-[#1f1f28] rounded-xl p-4 shadow-sm flex flex-col justify-between hover:border-[#1f1f28]/60 transition-colors h-full">
      <span className="text-xs text-muted-foreground font-medium uppercase tracking-wider">{label}</span>
      <div className="flex items-baseline gap-1 mt-1">
        <span className="text-3xl font-bold text-foreground">{value}</span>
        <span className="text-sm text-muted-foreground">{unit}</span>
      </div>
      
      {type === 'progress' ? (
        <div className="h-10 mt-3 w-full flex items-center relative overflow-hidden rounded">
          <div className="w-full bg-[#0a0a0f] h-2.5 rounded-full overflow-hidden border border-[#1f1f28] relative z-10">
            <div className={`h-full shadow-[0_0_8px_currentColor] ${color.replace('text-', 'bg-')}`} style={{ width: `${value}%` }}></div>
          </div>
        </div>
      ) : (
        <div className="h-10 mt-3 w-full relative overflow-hidden">
          <svg viewBox="0 0 100 30" className="w-full h-full preserve-3d" preserveAspectRatio="none">
            <path 
              d="M0,20 Q10,25 20,15 T40,10 T60,20 T80,5 T100,15" 
              fill="none" 
              className={stroke} 
              strokeWidth="2" 
              strokeLinecap="round" 
            />
            <path 
              d="M0,30 L0,20 Q10,25 20,15 T40,10 T60,20 T80,5 T100,15 L100,30 Z" 
              fill="currentColor" 
              className={`opacity-10 ${color}`} 
            />
          </svg>
        </div>
      )}

      <div className="flex items-center justify-between mt-3 text-[10px]">
        <span className={`${color} font-medium`}>{trend}</span>
        <span className="text-muted-foreground">{trendLabel}</span>
      </div>
    </div>
  );
}
