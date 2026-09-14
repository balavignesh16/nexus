import type { ReactNode } from 'react';

interface EmptyStateProps {
  icon?: ReactNode;
  title: string;
  description?: string;
  action?: ReactNode;
}

/** Foundation empty state - icon + honest message + optional action. Callers supply the real, specific message ("No events recorded in selected period."); this component never invents one. */
export function EmptyState({ icon, title, description, action }: EmptyStateProps) {
  return (
    <div className="flex flex-col items-center justify-center text-center gap-3 py-10 px-4">
      {icon && <div className="text-foundation-muted-fg/40">{icon}</div>}
      <div>
        <p className="text-sm font-medium text-foundation-fg">{title}</p>
        {description && <p className="text-xs text-foundation-muted-fg mt-1 max-w-sm">{description}</p>}
      </div>
      {action}
    </div>
  );
}
