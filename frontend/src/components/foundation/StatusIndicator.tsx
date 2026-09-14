import { cn } from '../../lib/utils';

export type StatusTone = 'online' | 'offline' | 'warning' | 'unknown';

interface StatusIndicatorProps {
  tone: StatusTone;
  label: string;
  pulse?: boolean;
  className?: string;
}

const TONE_COLOR: Record<StatusTone, string> = {
  online: 'var(--success)',
  offline: 'var(--danger)',
  warning: 'var(--warning)',
  unknown: 'var(--data-unavailable)',
};

/**
 * A dot + label for real operational state (device/service/connection
 * status) - never used to imply data provenance (that is
 * DataSourceBadge's job). Status is conveyed by both color and the text
 * label, never color alone (accessibility requirement carried over from
 * earlier phases).
 */
export function StatusIndicator({ tone, label, pulse = false, className }: StatusIndicatorProps) {
  const color = TONE_COLOR[tone];
  return (
    <span className={cn('inline-flex items-center gap-1.5 text-xs font-medium', className)} style={{ color }}>
      <span className="relative flex w-1.5 h-1.5">
        {pulse && (
          <span
            className="animate-ping absolute inline-flex h-full w-full rounded-full opacity-60"
            style={{ backgroundColor: color }}
          />
        )}
        <span className="relative inline-flex rounded-full w-1.5 h-1.5" style={{ backgroundColor: color }} />
      </span>
      {label}
    </span>
  );
}
