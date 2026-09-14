import type { ReactNode } from 'react';
import { cn } from '../../lib/utils';

export type BadgeTone = 'neutral' | 'accent' | 'success' | 'warning' | 'danger' | 'info';

interface BadgeProps {
  tone?: BadgeTone;
  children: ReactNode;
  className?: string;
}

/**
 * REFERENCE-DRIVEN CORRECTION: each tone now gets its own soft tinted
 * background (a pale wash of its color), not one shared neutral gray
 * chip - matching the reference images' "soft semantic backgrounds."
 * Colors are mixed at low percentage against white/transparent so they
 * stay pastel, never saturated.
 */
const TONE_COLOR: Record<Exclude<BadgeTone, 'neutral'>, string> = {
  accent: 'var(--accent)',
  success: 'var(--success)',
  warning: 'var(--warning)',
  danger: 'var(--danger)',
  info: 'var(--info)',
};

const TONE_TEXT: Record<BadgeTone, string> = {
  neutral: 'text-foundation-muted-fg',
  accent: 'text-[var(--accent)]',
  success: 'text-success',
  warning: 'text-warning',
  danger: 'text-danger',
  info: 'text-info',
};

/**
 * Generic labeling badge (a tag, a count, a category) - deliberately
 * distinct from DataSourceBadge, which remains the sole, authoritative
 * component for LIVE/CALCULATED/SIMULATED/UNAVAILABLE/AI data-provenance
 * labeling. DataSourceBadge's classification logic is untouched; only its
 * visual chrome gained an opt-in variant="foundation" to match this
 * component's soft-tinted-background treatment.
 */
export function Badge({ tone = 'neutral', children, className }: BadgeProps) {
  const color = tone === 'neutral' ? null : TONE_COLOR[tone];
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 px-2 py-0.5 rounded-[var(--radius-pill)] border',
        'text-[10px] font-semibold uppercase tracking-wider',
        tone === 'neutral' ? 'bg-black/[0.03] border-foundation-border' : 'border-transparent',
        TONE_TEXT[tone],
        className
      )}
      style={color ? { backgroundColor: `color-mix(in srgb, ${color} 12%, white)` } : undefined}
    >
      {children}
    </span>
  );
}
