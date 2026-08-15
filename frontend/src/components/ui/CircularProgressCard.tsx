interface CircularProgressCardProps {
  title: string;
  value: number;
  subtitle: string;
  color?: string;
}

export function CircularProgressCard({ title, value, subtitle, color = '#3b82f6' }: CircularProgressCardProps) {
  const radius = 35;
  const circumference = 2 * Math.PI * radius;
  const strokeDashoffset = circumference - (value / 100) * circumference;

  return (
    <div className="bg-[#12121a] border border-[#1f1f28] rounded-xl p-5 flex items-center shadow-sm h-full gap-5">
      
      {/* SVG Ring */}
      <div className="relative w-20 h-20 shrink-0">
        <svg className="w-full h-full -rotate-90 transform" viewBox="0 0 100 100">
          <circle
            cx="50"
            cy="50"
            r={radius}
            stroke="currentColor"
            strokeWidth="8"
            fill="none"
            className="text-[#1f1f28]"
          />
          <circle
            cx="50"
            cy="50"
            r={radius}
            stroke={color}
            strokeWidth="8"
            fill="none"
            strokeDasharray={circumference}
            strokeDashoffset={strokeDashoffset}
            strokeLinecap="round"
            className="drop-shadow-[0_0_8px_rgba(59,130,246,0.3)] transition-all duration-1000 ease-out"
            style={{ stroke: color }}
          />
        </svg>
        <div className="absolute inset-0 flex items-center justify-center">
          <span className="text-xl font-bold text-foreground">{value}</span>
        </div>
      </div>
      
      {/* Text Info */}
      <div className="flex flex-col">
        <span className="text-sm font-semibold text-foreground">{title}</span>
        <span className="text-xs text-muted-foreground mt-1">{subtitle}</span>
      </div>
    </div>
  );
}
