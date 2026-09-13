interface DistributionBarProps {
  data: Record<string, number>;
  colorFor: (key: string) => string;
  labelFor?: (key: string) => string;
}

/**
 * A compact, information-dense distribution readout (segment label, real
 * count, proportional bar) - deliberately not a decorative donut/pie
 * chart. Every count here is a real database GROUP BY result.
 */
export function DistributionBar({ data, colorFor, labelFor }: DistributionBarProps) {
  const entries = Object.entries(data);
  const total = entries.reduce((sum, [, v]) => sum + v, 0);

  if (total === 0 || entries.length === 0) {
    return null;
  }

  return (
    <div className="flex flex-col gap-2">
      <div className="flex h-2 rounded-full overflow-hidden bg-raised">
        {entries.map(([key, value]) => (
          <div
            key={key}
            style={{ width: `${(value / total) * 100}%`, backgroundColor: colorFor(key) }}
            title={`${labelFor ? labelFor(key) : key}: ${value}`}
          />
        ))}
      </div>
      <div className="flex flex-wrap gap-x-4 gap-y-1">
        {entries.map(([key, value]) => (
          <div key={key} className="flex items-center gap-1.5 text-xs">
            <span className="w-2 h-2 rounded-full shrink-0" style={{ backgroundColor: colorFor(key) }} />
            <span className="text-muted-foreground">{labelFor ? labelFor(key) : key}</span>
            <span className="font-mono text-foreground/90">{value}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
