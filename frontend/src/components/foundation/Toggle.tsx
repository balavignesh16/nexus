import { cn } from '../../lib/utils';

interface ToggleProps {
  checked: boolean;
  onChange: (checked: boolean) => void;
  disabled?: boolean;
  label?: string;
  className?: string;
}

/** A real, accessible (role=switch) on/off control - the reusable primitive behind every boolean state in the redesigned pages (rule enabled, capability write toggles, etc). */
export function Toggle({ checked, onChange, disabled, label, className }: ToggleProps) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={cn(
        'relative inline-flex items-center h-6 w-11 rounded-[var(--radius-pill)] transition-colors shrink-0',
        'disabled:opacity-40 disabled:pointer-events-none',
        className
      )}
      style={{ backgroundColor: checked ? 'var(--accent)' : 'var(--foundation-border)' }}
    >
      <span
        className="inline-block h-4 w-4 transform rounded-full bg-white shadow transition-transform"
        style={{ transform: checked ? 'translateX(22px)' : 'translateX(4px)' }}
      />
    </button>
  );
}
