export type DataClassification = 'LIVE' | 'CALCULATED' | 'SIMULATED' | 'UNAVAILABLE';

const STYLES: Record<DataClassification, { label: string; dot: string; text: string }> = {
  LIVE: { label: 'Live', dot: 'bg-[var(--data-live)]', text: 'text-[var(--data-live)]' },
  CALCULATED: { label: 'Calculated', dot: 'bg-[var(--data-calculated)]', text: 'text-[var(--data-calculated)]' },
  SIMULATED: { label: 'Simulated', dot: 'bg-[var(--data-simulated)]', text: 'text-[var(--data-simulated)]' },
  UNAVAILABLE: { label: 'Unavailable', dot: 'bg-[var(--data-unavailable)]', text: 'text-[var(--data-unavailable)]' },
};

/**
 * Explicit data-integrity classification (redesign requirement): every
 * screen showing a number or chart must be able to say whether it is
 * LIVE (real backend data), CALCULATED (derived from real data), SIMULATED
 * (demo/fixture data), or UNAVAILABLE (no real source exists) - simulated
 * data must never visually pass as production telemetry.
 */
export function DataSourceBadge({ classification }: { classification: DataClassification }) {
  const s = STYLES[classification];
  return (
    <span className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded border border-surface-border bg-raised text-[10px] font-semibold uppercase tracking-wider ${s.text}`}>
      <span className={`w-1.5 h-1.5 rounded-full ${s.dot}`} />
      {s.label}
    </span>
  );
}
