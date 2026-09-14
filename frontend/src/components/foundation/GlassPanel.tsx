import type { HTMLAttributes, ReactNode } from 'react';
import { cn } from '../../lib/utils';

export type GlassLevel = 'base' | 'elevated' | 'glass' | 'glass-strong' | 'float';

interface GlassPanelProps extends HTMLAttributes<HTMLDivElement> {
  level?: GlassLevel;
  radius?: 'panel' | 'panel-lg' | 'pill' | 'none';
  children?: ReactNode;
}

/**
 * The single primitive every new-foundation surface is built from
 * (Step 1). Blur/opacity/border/shadow always travel together as one
 * "level" rather than being composed ad hoc from raw utility classes -
 * this is what keeps glass a deliberate 5-level system instead of a
 * blanket effect applied everywhere.
 *
 * Levels:
 *  1. base     - opaque page background (--foundation-bg, warm graphite).
 *                No blur. Deliberately NOT the legacy --canvas (cool
 *                blue-black) - nothing production-facing consumes
 *                GlassPanel yet, so the whole ladder can carry one
 *                consistent warm-neutral identity without touching any
 *                existing page's background.
 *  2. elevated - opaque card (--foundation-surface/--foundation-border).
 *                No blur.
 *  3. glass    - translucent surface, backdrop-blur, subtle border. The
 *                default "premium panel" look.
 *  4. glass-strong - modal/overlay: less see-through, heavier blur.
 *  5. float    - floating control layer (toolbars, command palette):
 *                strong blur, faint accent-tinted edge, softest shadow.
 */
export function GlassPanel({ level = 'glass', radius = 'panel', className, style, children, ...props }: GlassPanelProps) {
  const radiusClass =
    radius === 'panel' ? 'rounded-[var(--radius-panel)]' :
    radius === 'panel-lg' ? 'rounded-[var(--radius-panel-lg)]' :
    radius === 'pill' ? 'rounded-[var(--radius-pill)]' : '';

  const levelStyle: React.CSSProperties = (() => {
    switch (level) {
      case 'base':
        return { backgroundColor: 'var(--foundation-bg)' };
      case 'elevated':
        return {
          backgroundColor: 'var(--foundation-surface)',
          border: '1px solid var(--foundation-border)',
          boxShadow: 'var(--shadow-glass-sm)',
        };
      case 'glass':
        return {
          backgroundColor: 'var(--glass-bg)',
          border: '1px solid var(--glass-border)',
          backdropFilter: 'blur(20px)',
          WebkitBackdropFilter: 'blur(20px)',
          boxShadow: 'var(--shadow-glass-md)',
        };
      case 'glass-strong':
        return {
          backgroundColor: 'var(--glass-strong-bg)',
          border: '1px solid var(--glass-strong-border)',
          backdropFilter: 'blur(32px)',
          WebkitBackdropFilter: 'blur(32px)',
          boxShadow: 'var(--shadow-glass-lg)',
        };
      case 'float':
        return {
          backgroundColor: 'var(--glass-float-bg)',
          border: '1px solid var(--glass-float-border)',
          backdropFilter: 'blur(24px)',
          WebkitBackdropFilter: 'blur(24px)',
          boxShadow: 'var(--shadow-glass-md)',
        };
    }
  })();

  return (
    <div
      data-glass-level={level}
      className={cn('relative', radiusClass, className)}
      style={{ ...levelStyle, ...style }}
      {...props}
    >
      {/* Subtle inset top highlight - only on translucent levels, never on opaque base/elevated. */}
      {(level === 'glass' || level === 'glass-strong' || level === 'float') && (
        <div
          aria-hidden="true"
          className={cn('pointer-events-none absolute inset-0', radiusClass)}
          style={{ boxShadow: `inset 0 1px 0 0 var(--glass-highlight)` }}
        />
      )}
      {children}
    </div>
  );
}
