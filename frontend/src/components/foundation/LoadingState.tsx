import { cn } from '../../lib/utils';

interface LoadingBlockProps {
  className?: string;
}

/** Foundation skeleton block - glass-aware shimmer, for the new surface levels. Never renders fabricated content; purely a loading placeholder shape. */
export function LoadingBlock({ className }: LoadingBlockProps) {
  return (
    <div
      className={cn(
        'relative overflow-hidden rounded-[var(--radius-panel)]',
        'before:absolute before:inset-0 before:-translate-x-full before:animate-[shimmer_2s_infinite]',
        'before:bg-gradient-to-r before:from-transparent before:via-white/[0.06] before:to-transparent',
        className
      )}
      style={{ backgroundColor: 'var(--glass-bg)', border: '1px solid var(--glass-border)' }}
      aria-hidden="true"
    />
  );
}
