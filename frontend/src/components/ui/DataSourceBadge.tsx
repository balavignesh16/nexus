export type DataClassification = 'LIVE' | 'CALCULATED' | 'SIMULATED' | 'UNAVAILABLE' | 'AI';

const STYLES: Record<DataClassification, { label: string; dot: string; text: string; color: string }> = {
  LIVE: { label: 'Live', dot: 'bg-[var(--data-live)]', text: 'text-[var(--data-live)]', color: 'var(--data-live)' },
  CALCULATED: { label: 'Calculated', dot: 'bg-[var(--data-calculated)]', text: 'text-[var(--data-calculated)]', color: 'var(--data-calculated)' },
  SIMULATED: { label: 'Simulated', dot: 'bg-[var(--data-simulated)]', text: 'text-[var(--data-simulated)]', color: 'var(--data-simulated)' },
  UNAVAILABLE: { label: 'Unavailable', dot: 'bg-[var(--data-unavailable)]', text: 'text-[var(--data-unavailable)]', color: 'var(--data-unavailable)' },
  AI: { label: 'AI-Generated', dot: 'bg-[var(--data-ai)]', text: 'text-[var(--data-ai)]', color: 'var(--data-ai)' },
};

/**
 * Explicit data-integrity classification (redesign requirement): every
 * screen showing a number or chart must be able to say whether it is
 * LIVE (real backend data), CALCULATED (derived from real data), SIMULATED
 * (demo/fixture data), UNAVAILABLE (no real source exists), or AI
 * (provider-generated reasoning grounded in NEXUS evidence). AI is
 * deliberately distinct from CALCULATED - a deterministic fallback
 * explanation must never render as AI, and vice versa.
 *
 * This mapping (which classification means what, and its color identity
 * via the --data-* tokens) is the authoritative part and is NEVER
 * touched by the `variant` prop below - only the surrounding chrome
 * (background/border) changes.
 *
 * `variant` (default 'dark'): every EXISTING call site on a production
 * page (Analytics, AI Insights, OverviewTab, etc.) is unaffected - they
 * still render the original dark chip, since those pages have not been
 * redesigned to the new light foundation yet. `variant="foundation"` is
 * a soft, tinted-background treatment matching the new light glass
 * design system (components/foundation/Badge.tsx's pattern) - used only
 * where a page has actually been redesigned onto that foundation (today,
 * only the DesignSystemPage showcase).
 */
export function DataSourceBadge({
  classification,
  variant = 'dark',
}: {
  classification: DataClassification;
  variant?: 'dark' | 'foundation';
}) {
  const s = STYLES[classification];

  if (variant === 'foundation') {
    return (
      <span
        className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-[var(--radius-pill)] border border-transparent text-[10px] font-semibold uppercase tracking-wider"
        style={{ backgroundColor: `color-mix(in srgb, ${s.color} 12%, white)`, color: s.color }}
      >
        <span className="w-1.5 h-1.5 rounded-full" style={{ backgroundColor: s.color }} />
        {s.label}
      </span>
    );
  }

  return (
    <span className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded border border-surface-border bg-raised text-[10px] font-semibold uppercase tracking-wider ${s.text}`}>
      <span className={`w-1.5 h-1.5 rounded-full ${s.dot}`} />
      {s.label}
    </span>
  );
}
