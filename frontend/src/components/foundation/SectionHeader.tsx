import type { ReactNode } from 'react';

interface SectionHeaderProps {
  icon?: ReactNode;
  title: string;
  subtitle?: string;
  action?: ReactNode;
}

/** Reusable page/section header: icon + title + subtitle + trailing action slot. */
export function SectionHeader({ icon, title, subtitle, action }: SectionHeaderProps) {
  return (
    <div className="flex items-center justify-between gap-4">
      <div className="flex items-center gap-3 min-w-0">
        {icon && (
          <div
            className="shrink-0 w-10 h-10 rounded-[var(--radius-panel)] flex items-center justify-center"
            style={{ backgroundColor: 'color-mix(in srgb, var(--accent) 14%, transparent)', color: 'var(--accent)' }}
          >
            {icon}
          </div>
        )}
        <div className="min-w-0">
          <h2 className="text-lg font-semibold text-foundation-fg truncate">{title}</h2>
          {subtitle && <p className="text-xs text-foundation-muted-fg truncate">{subtitle}</p>}
        </div>
      </div>
      {action && <div className="shrink-0">{action}</div>}
    </div>
  );
}
