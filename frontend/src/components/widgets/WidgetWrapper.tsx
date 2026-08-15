import type { ReactNode } from 'react';
import { AlertCircle, RefreshCw } from 'lucide-react';
import { AppCard } from '../ui/AppCard';
import { Skeleton } from '../ui/Skeleton';
import { cn } from '../../lib/utils';

interface WidgetWrapperProps {
  title?: ReactNode;
  headerAction?: ReactNode;
  isLoading: boolean;
  isError: boolean;
  isEmpty: boolean;
  onRetry?: () => void;
  children: ReactNode;
  className?: string;
  noPadding?: boolean;
}

export function WidgetWrapper({
  title,
  headerAction,
  isLoading,
  isError,
  isEmpty,
  onRetry,
  children,
  className,
  noPadding,
}: WidgetWrapperProps) {
  return (
    <AppCard className={cn('flex flex-col h-full', className)} noPadding={noPadding}>
      {(title || headerAction) && (
        <div className={cn("flex justify-between items-center mb-4", noPadding && "px-4 pt-4 mb-0 pb-4 border-b border-border")}>
          <div className="text-sm font-semibold text-foreground uppercase tracking-wider">{title}</div>
          {headerAction}
        </div>
      )}
      
      <div className={cn("flex-1 relative flex flex-col min-h-0 overflow-hidden", (isLoading || isError || isEmpty) && "min-h-[200px] justify-center items-center")}>
        {isLoading ? (
          <div className="absolute inset-0 flex flex-col gap-3 p-4">
            <Skeleton className="h-4 w-1/3" />
            <Skeleton className="h-full w-full flex-1" />
          </div>
        ) : isError ? (
          <div className="text-center p-6 flex flex-col items-center">
            <AlertCircle className="text-danger mb-3" size={32} />
            <h3 className="text-sm font-medium text-foreground mb-1">Failed to load data</h3>
            <p className="text-xs text-muted-foreground mb-4 max-w-[200px]">There was a problem retrieving the information for this widget.</p>
            {onRetry && (
              <button 
                onClick={onRetry}
                className="flex items-center gap-2 text-xs font-medium text-primary hover:text-primary-foreground bg-primary/10 hover:bg-primary px-3 py-1.5 rounded-md transition-colors"
              >
                <RefreshCw size={14} /> Retry
              </button>
            )}
          </div>
        ) : isEmpty ? (
          <div className="text-center p-6 text-muted-foreground">
            <p className="text-sm">No data available.</p>
          </div>
        ) : (
          children
        )}
      </div>
    </AppCard>
  );
}
