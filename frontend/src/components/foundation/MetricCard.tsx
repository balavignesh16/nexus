import type { ReactNode } from 'react';
import { Card } from './Card';

interface MetricCardProps {
  label: string;
  value: string | number;
  unit?: string;
  icon?: ReactNode;
  /** Caller-supplied, must be a real computed delta - this component never invents a trend. */
  trend?: { direction: 'up' | 'down' | 'flat'; text: string };
  badge?: ReactNode;
}

/**
 * Foundation metric presentation (Step 1). Distinct from the existing
 * production StatCard/MetricCard in components/ui/, which stay untouched.
 * `trend` is optional and must be passed in by the caller from a real
 * computed value - this component has no default and never synthesizes
 * one just to look populated.
 */
export function MetricCard({ label, value, unit, icon, trend, badge }: MetricCardProps) {
  return (
    <Card padding="md" icon={icon} title={label} action={badge}>
      <div className="flex items-baseline gap-1.5">
        <span className="text-3xl font-semibold text-foundation-fg tabular-nums">{value}</span>
        {unit && <span className="text-sm text-foundation-muted-fg">{unit}</span>}
      </div>
      {trend && (
        <span
          className="text-xs font-medium"
          style={{ color: trend.direction === 'up' ? 'var(--success)' : trend.direction === 'down' ? 'var(--danger)' : 'var(--foundation-muted-fg)' }}
        >
          {trend.direction === 'up' ? '↑' : trend.direction === 'down' ? '↓' : '→'} {trend.text}
        </span>
      )}
    </Card>
  );
}
