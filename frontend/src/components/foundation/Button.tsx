import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { cn } from '../../lib/utils';

export type ButtonVariant = 'primary' | 'secondary' | 'outline' | 'ghost' | 'destructive';
export type ButtonSize = 'sm' | 'md' | 'lg';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  children?: ReactNode;
}

const SIZE: Record<ButtonSize, string> = {
  sm: 'h-8 px-3 text-xs gap-1.5',
  md: 'h-9 px-4 text-sm gap-2',
  lg: 'h-11 px-5 text-sm gap-2',
};

/**
 * REFERENCE-DRIVEN CORRECTION: "primary" previously fell back to the
 * legacy --primary (blue) - any future page using <Button variant=
 * "primary"> would have gotten blue back, undermining the whole point of
 * this redesign. "primary" now means the new foundation's own restrained
 * accent (lavender). The legacy blue token is never used by this
 * component; it only still appears, explicitly labeled "legacy", in the
 * DesignSystemPage color reference swatch.
 */
const VARIANT: Record<ButtonVariant, string> = {
  primary: 'bg-[var(--accent)] text-[var(--accent-foreground)] hover:brightness-105 shadow-[var(--shadow-glass-sm)]',
  secondary: 'text-[var(--accent)] hover:brightness-95',
  outline: 'bg-transparent border border-foundation-border text-foundation-fg hover:bg-black/[0.04]',
  ghost: 'bg-transparent text-foundation-muted-fg hover:text-foundation-fg hover:bg-black/[0.04]',
  destructive: 'bg-danger text-white hover:brightness-110',
};

export function Button({ variant = 'primary', size = 'md', className, children, style, ...props }: ButtonProps) {
  // "secondary" is a soft, accent-tinted fill (not a solid color) - the
  // premium/soft-hover character the reference buttons have, distinct
  // from "primary"'s solid fill and "outline"'s plain border.
  const secondaryStyle: React.CSSProperties | undefined =
    variant === 'secondary' ? { backgroundColor: 'color-mix(in srgb, var(--accent) 14%, transparent)' } : undefined;
  return (
    <button
      className={cn(
        'inline-flex items-center justify-center rounded-[var(--radius-pill)] font-medium',
        'transition-colors disabled:opacity-40 disabled:pointer-events-none',
        SIZE[size],
        VARIANT[variant],
        className
      )}
      style={{ ...secondaryStyle, ...style }}
      {...props}
    >
      {children}
    </button>
  );
}
