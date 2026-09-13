import type { AnalyticsRangeParam } from '../../api/analyticsApi';

const RANGES: { value: AnalyticsRangeParam; label: string }[] = [
  { value: '1h', label: '1h' },
  { value: '6h', label: '6h' },
  { value: '24h', label: '24h' },
  { value: '7d', label: '7d' },
  { value: '30d', label: '30d' },
];

interface TimeRangeSelectorProps {
  value: AnalyticsRangeParam;
  onChange: (range: AnalyticsRangeParam) => void;
}

/**
 * The single source of truth for the selected analytics range. Changing
 * it must trigger a real backend refetch for every section - there is no
 * client-side reslicing of an over-fetched dataset anywhere in this page.
 */
export function TimeRangeSelector({ value, onChange }: TimeRangeSelectorProps) {
  return (
    <div className="flex items-center bg-surface border border-surface-border rounded-md p-0.5" role="group" aria-label="Time range">
      {RANGES.map((r) => (
        <button
          key={r.value}
          type="button"
          onClick={() => onChange(r.value)}
          aria-pressed={value === r.value}
          className={`px-3 py-1 text-xs font-medium rounded transition-colors ${
            value === r.value ? 'bg-primary/15 text-primary' : 'text-muted-foreground hover:text-foreground'
          }`}
        >
          {r.label}
        </button>
      ))}
    </div>
  );
}
