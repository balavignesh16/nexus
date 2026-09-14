import type { AnalyticsRangeParam } from '../../api/analyticsApi';
import { Pill } from '../foundation/Pill';

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
    <div className="flex flex-wrap items-center gap-1.5" role="group" aria-label="Time range">
      {RANGES.map((r) => (
        <Pill key={r.value} active={value === r.value} onClick={() => onChange(r.value)}>
          {r.label}
        </Pill>
      ))}
    </div>
  );
}
