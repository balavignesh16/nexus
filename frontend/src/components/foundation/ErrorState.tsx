import type { ReactNode } from 'react';

interface ErrorStateProps {
  icon?: ReactNode;
  message: string;
  action?: ReactNode;
}

/** Foundation error state - real backend error message + optional retry slot. Never a generic "Something went wrong" placeholder; callers pass the actual error. */
export function ErrorState({ icon, message, action }: ErrorStateProps) {
  return (
    <div className="flex flex-col items-center justify-center text-center gap-3 py-10 px-4">
      {icon && <div style={{ color: 'var(--danger)' }}>{icon}</div>}
      <p className="text-sm" style={{ color: 'var(--danger)' }}>{message}</p>
      {action}
    </div>
  );
}
