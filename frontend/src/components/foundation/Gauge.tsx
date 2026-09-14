interface GaugeProps {
  /** Real current value - caller-supplied, never fabricated by this component. */
  value: number;
  min: number;
  max: number;
  label?: string;
  unit?: string;
  size?: number;
  /** Arc sweep in degrees (270 = classic thermostat-style dial, 360 = full ring). */
  sweep?: number;
  color?: string;
}

/**
 * Generic gauge/dial primitive (Step 1 foundation). Purely presentational:
 * takes real min/max/value via props and draws a proportional arc. Must
 * only be used later where the metric is real, has meaningful numeric
 * bounds, and the visualization genuinely improves comprehension over a
 * plain number - never to manufacture a value for visual effect.
 */
export function Gauge({ value, min, max, label, unit, size = 140, sweep = 270, color = 'var(--accent)' }: GaugeProps) {
  const clamped = Math.min(max, Math.max(min, value));
  const fraction = max > min ? (clamped - min) / (max - min) : 0;

  const radius = 44;
  const sweepFraction = sweep / 360;
  const rotation = -90 - ((360 - sweep) / 2);

  const trackDash = `${sweepFraction * 100} 100`;
  const valueDash = `${fraction * sweepFraction * 100} 100`;

  return (
    <div className="flex flex-col items-center gap-1" style={{ width: size }}>
      <div className="relative" style={{ width: size, height: size }}>
        <svg viewBox="0 0 100 100" width={size} height={size} className="-rotate-90">
          <circle
            cx="50" cy="50" r={radius}
            fill="none"
            stroke="var(--foundation-border)"
            strokeWidth="8"
            strokeLinecap="round"
            pathLength={100}
            strokeDasharray={trackDash}
            transform={`rotate(${rotation + 90} 50 50)`}
          />
          <circle
            cx="50" cy="50" r={radius}
            fill="none"
            stroke={color}
            strokeWidth="8"
            strokeLinecap="round"
            pathLength={100}
            strokeDasharray={valueDash}
            transform={`rotate(${rotation + 90} 50 50)`}
            style={{ transition: 'stroke-dasharray 300ms ease' }}
          />
        </svg>
        <div className="absolute inset-0 flex flex-col items-center justify-center">
          <span className="text-2xl font-semibold text-foundation-fg tabular-nums">{clamped}</span>
          {unit && <span className="text-[11px] text-foundation-muted-fg">{unit}</span>}
        </div>
      </div>
      {label && <span className="text-xs text-foundation-muted-fg">{label}</span>}
    </div>
  );
}
