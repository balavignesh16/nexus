import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Skeleton } from '../ui/Skeleton';
import { DataSourceBadge } from '../ui/DataSourceBadge';

interface AnalyticsSectionProps {
  title: string;
  subtitle?: string;
  isLoading: boolean;
  error: unknown;
  isEmpty: boolean;
  emptyMessage: string;
  drillDownTo?: string;
  drillDownLabel?: string;
  children: ReactNode;
}

/**
 * Every analytics section is independent: one section's failure never
 * blanks the rest of the page. Loading uses the real Skeleton component
 * (never fabricated chart points); empty means the backend genuinely
 * calculated zero for the selected population/range, never a client-side
 * guess. Every value on this page is CALCULATED (database aggregation),
 * never LIVE - LIVE is reserved for realtime/current-state values.
 */
export function AnalyticsSection({
  title, subtitle, isLoading, error, isEmpty, emptyMessage, drillDownTo, drillDownLabel, children,
}: AnalyticsSectionProps) {
  return (
    <section className="bg-surface border border-surface-border rounded-lg p-4 flex flex-col gap-3">
      <div className="flex items-center justify-between">
        <div>
          <h3 className="text-xs font-semibold uppercase tracking-wider text-foreground/90">{title}</h3>
          {subtitle && <p className="text-[11px] text-muted-foreground mt-0.5">{subtitle}</p>}
        </div>
        <div className="flex items-center gap-3">
          <DataSourceBadge classification="CALCULATED" />
          {drillDownTo && (
            <Link to={drillDownTo} className="text-[11px] text-primary hover:underline whitespace-nowrap">
              {drillDownLabel ?? 'Investigate'}
            </Link>
          )}
        </div>
      </div>

      {isLoading ? (
        <Skeleton className="h-32 w-full" />
      ) : error ? (
        <div data-testid="section-error" className="h-24 flex items-center justify-center text-xs text-destructive">
          {error instanceof Error ? error.message : 'Failed to load this section.'}
        </div>
      ) : isEmpty ? (
        <div data-testid="section-empty" className="h-24 flex items-center justify-center text-xs text-muted-foreground">
          {emptyMessage}
        </div>
      ) : (
        children
      )}
    </section>
  );
}
