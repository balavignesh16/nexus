interface HealthGaugeProps {
  score: number; // 0 to 100
  size?: number;
}

export function HealthGauge({ score, size = 120 }: HealthGaugeProps) {
  const radius = (size - 10) / 2;
  const circumference = radius * 2 * Math.PI;
  const strokeDashoffset = circumference - (score / 100) * circumference;

  let color = 'text-success';
  if (score < 70) color = 'text-warning';
  if (score < 50) color = 'text-destructive';

  return (
    <div className="relative flex items-center justify-center" style={{ width: size, height: size }}>
      {/* Background Circle */}
      <svg className="transform -rotate-90 w-full h-full">
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          stroke="currentColor"
          strokeWidth="8"
          fill="transparent"
          className="text-muted/20"
        />
        {/* Progress Circle */}
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          stroke="currentColor"
          strokeWidth="8"
          fill="transparent"
          strokeDasharray={circumference}
          strokeDashoffset={strokeDashoffset}
          className={`${color} transition-all duration-1000 ease-in-out`}
          strokeLinecap="round"
        />
      </svg>
      
      {/* Score Text */}
      <div className="absolute flex flex-col items-center justify-center">
        <span className={`text-2xl font-bold font-mono ${color}`}>
          {score}
        </span>
        <span className="text-[10px] text-muted-foreground uppercase tracking-widest font-semibold">
          Health
        </span>
      </div>
    </div>
  );
}
