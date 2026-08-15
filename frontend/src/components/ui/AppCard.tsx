import { forwardRef } from 'react';
import { cn } from '../../lib/utils';

export interface AppCardProps extends React.HTMLAttributes<HTMLDivElement> {
  noPadding?: boolean;
}

export const AppCard = forwardRef<HTMLDivElement, AppCardProps>(
  ({ className, noPadding = false, children, ...props }, ref) => {
    return (
      <div
        ref={ref}
        className={cn(
          'bg-card text-card-foreground border border-border rounded-lg shadow-sm',
          !noPadding && 'p-4 sm:p-5',
          className
        )}
        {...props}
      >
        {children}
      </div>
    );
  }
);
AppCard.displayName = 'AppCard';
