import type { ReactNode } from 'react';
import { cn } from '../../lib/utils';

interface PillProps {
  active?: boolean;
  onClick?: () => void;
  children: ReactNode;
  className?: string;
  disabled?: boolean;
}

/** A single selectable/removable pill - the building block for segmented controls, filter chips, and tag lists. */
export function Pill({ active = false, onClick, children, className, disabled }: PillProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-pressed={onClick ? active : undefined}
      className={cn(
        'inline-flex items-center gap-1.5 px-3 py-1.5 rounded-[var(--radius-pill)] text-xs font-medium transition-colors',
        'disabled:opacity-40 disabled:pointer-events-none',
        active
          ? 'text-[var(--accent-foreground)]'
          : 'text-foundation-muted-fg hover:text-foundation-fg border border-foundation-border bg-black/[0.02]',
        className
      )}
      style={active ? { backgroundColor: 'var(--accent)' } : undefined}
    >
      {children}
    </button>
  );
}
