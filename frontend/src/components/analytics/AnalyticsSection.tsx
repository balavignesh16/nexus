import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Card } from '../foundation/Card';
import { EmptyState } from '../foundation/EmptyState';
import { ErrorState } from '../foundation/ErrorState';
import { LoadingBlock } from '../foundation/LoadingState';
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
 * blanks the rest of the page. Loading uses the real LoadingBlock component
 * (never fabricated chart points); empty means the backend genuinely
 * calculated zero for the selected population/range, never a client-side
 * guess. Every value on this page is CALCULATED (database aggregation),
 * never LIVE - LIVE is reserved for realtime/current-state values.
 */
export function AnalyticsSection({
  title, subtitle, isLoading, error, isEmpty, emptyMessage, drillDownTo, drillDownLabel, children,
}: AnalyticsSectionProps) {
  return (
    <Card
      level="glass"
      padding="lg"
      title={<span className="text-xs font-semibold uppercase tracking-wider">{title}</span>}
      subtitle={subtitle}
      action={
        <div className="flex items-center gap-3">
          <DataSourceBadge classification="CALCULATED" variant="foundation" />
          {drillDownTo && (
            <Link to={drillDownTo} className="text-[11px] font-medium whitespace-nowrap" style={{ color: 'var(--accent)' }}>
              {drillDownLabel ?? 'Investigate →'}
            </Link>
          )}
        </div>
      }
    >
      {isLoading ? (
        <LoadingBlock className="h-32 w-full" />
      ) : error ? (
        <div data-testid="section-error">
          <ErrorState message={error instanceof Error ? error.message : 'Failed to load this section.'} />
        </div>
      ) : isEmpty ? (
        <div data-testid="section-empty">
          <EmptyState title={emptyMessage} />
        </div>
      ) : (
        children
      )}
    </Card>
  );
}
