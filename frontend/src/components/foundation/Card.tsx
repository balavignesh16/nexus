import type { ReactNode } from 'react';
import { GlassPanel, type GlassLevel } from './GlassPanel';
import { cn } from '../../lib/utils';

interface CardProps {
  level?: GlassLevel;
  icon?: ReactNode;
  title?: ReactNode;
  subtitle?: ReactNode;
  action?: ReactNode;
  children?: ReactNode;
  className?: string;
  padding?: 'sm' | 'md' | 'lg';
}

const PADDING = { sm: 'p-3', md: 'p-4', lg: 'p-5' };

/**
 * The foundation card: icon-chip header (icon + title/subtitle + trailing
 * action slot) over a GlassPanel body. Matches the reference material's
 * card-composition pattern (icon chip, title, compact controls) without
 * copying its literal smart-home content.
 */
export function Card({ level = 'glass', icon, title, subtitle, action, children, className, padding = 'md' }: CardProps) {
  const hasHeader = icon || title || subtitle || action;
  return (
    <GlassPanel level={level} className={cn('flex flex-col gap-3', PADDING[padding], className)}>
      {hasHeader && (
        <div className="flex items-center justify-between gap-3">
          <div className="flex items-center gap-3 min-w-0">
            {icon && (
              <div
                className="shrink-0 w-9 h-9 rounded-[var(--radius-panel)] flex items-center justify-center"
                style={{ backgroundColor: 'color-mix(in srgb, var(--accent) 14%, transparent)', color: 'var(--accent)' }}
              >
                {icon}
              </div>
            )}
            <div className="min-w-0">
              {title && <div className="text-sm font-semibold text-foundation-fg truncate">{title}</div>}
              {subtitle && <div className="text-xs text-foundation-muted-fg truncate">{subtitle}</div>}
            </div>
          </div>
          {action && <div className="shrink-0">{action}</div>}
        </div>
      )}
      {children}
    </GlassPanel>
  );
}
