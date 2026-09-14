import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { cn } from '../../lib/utils';

export type IconButtonVariant = 'ghost' | 'glass' | 'solid';

interface IconButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: IconButtonVariant;
  'aria-label': string;
  children: ReactNode;
}

const VARIANT: Record<IconButtonVariant, string> = {
  ghost: 'bg-transparent text-foundation-muted-fg hover:text-foundation-fg hover:bg-black/[0.04]',
  glass: 'text-foundation-fg/90 hover:text-foundation-fg',
  solid: 'bg-[var(--accent)] text-[var(--accent-foreground)] hover:brightness-105',
};

/** Reference-driven: circular by default (rounded-full), matching the reference images' circular icon controls - not the panel radius used by cards. */
export function IconButton({ variant = 'ghost', className, children, style, ...props }: IconButtonProps) {
  const glassStyle: React.CSSProperties | undefined =
    variant === 'glass'
      ? {
          backgroundColor: 'var(--glass-bg)',
          border: '1px solid var(--glass-border)',
          backdropFilter: 'blur(16px)',
          WebkitBackdropFilter: 'blur(16px)',
          boxShadow: 'var(--shadow-glass-sm)',
        }
      : undefined;
  return (
    <button
      className={cn(
        'inline-flex items-center justify-center w-9 h-9 rounded-full transition-colors',
        'disabled:opacity-40 disabled:pointer-events-none',
        VARIANT[variant],
        className
      )}
      style={{ ...glassStyle, ...style }}
      {...props}
    >
      {children}
    </button>
  );
}
