export type DataClassification = 'LIVE' | 'CALCULATED' | 'SIMULATED' | 'UNAVAILABLE' | 'AI';

const STYLES: Record<DataClassification, { label: string; dot: string; text: string }> = {
  LIVE: { label: 'Live', dot: 'bg-[var(--data-live)]', text: 'text-[var(--data-live)]' },
  CALCULATED: { label: 'Calculated', dot: 'bg-[var(--data-calculated)]', text: 'text-[var(--data-calculated)]' },
  SIMULATED: { label: 'Simulated', dot: 'bg-[var(--data-simulated)]', text: 'text-[var(--data-simulated)]' },
  UNAVAILABLE: { label: 'Unavailable', dot: 'bg-[var(--data-unavailable)]', text: 'text-[var(--data-unavailable)]' },
  AI: { label: 'AI-Generated', dot: 'bg-[var(--data-ai)]', text: 'text-[var(--data-ai)]' },
};

/**
 * Explicit data-integrity classification (redesign requirement): every
 * screen showing a number or chart must be able to say whether it is
 * LIVE (real backend data), CALCULATED (derived from real data), SIMULATED
 * (demo/fixture data), UNAVAILABLE (no real source exists), or AI
 * (provider-generated reasoning grounded in NEXUS evidence). AI is
 * deliberately distinct from CALCULATED - a deterministic fallback
 * explanation must never render as AI, and vice versa.
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
