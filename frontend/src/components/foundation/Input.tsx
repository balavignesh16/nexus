import type { InputHTMLAttributes, SelectHTMLAttributes, ReactNode } from 'react';
import { cn } from '../../lib/utils';

const FIELD_BASE =
  'w-full h-9 px-3 text-sm rounded-[var(--radius-panel)] border border-foundation-border bg-black/[0.02] ' +
  'text-foundation-fg placeholder:text-foundation-muted-fg/60 outline-none transition-colors ' +
  'focus:border-[var(--accent)] focus:bg-black/[0.04]';

export function Input({ className, ...props }: InputHTMLAttributes<HTMLInputElement>) {
  return <input className={cn(FIELD_BASE, className)} {...props} />;
}

interface SelectProps extends SelectHTMLAttributes<HTMLSelectElement> {
  children: ReactNode;
}

export function Select({ className, children, ...props }: SelectProps) {
  return (
    <select className={cn(FIELD_BASE, 'appearance-none cursor-pointer', className)} {...props}>
      {children}
    </select>
  );
}
