import { useLatestTwins } from '../../hooks/queries';
import { TwinCard } from '../ui/TwinCard';
import { WidgetWrapper } from './WidgetWrapper';

export function TwinOverviewWidget() {
  const { data: twins = [], isLoading, isError, refetch } = useLatestTwins(12);

  return (
    <WidgetWrapper
      title="Live Digital Twins"
      headerAction={<span className="text-xs bg-muted/20 px-2 py-1 rounded text-foreground">Showing latest {twins.length}</span>}
      isLoading={isLoading}
      isError={isError}
      isEmpty={twins.length === 0}
      onRetry={() => refetch()}
      className="bg-transparent border-0 shadow-none p-0"
      noPadding
    >
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
        {twins.map(twin => (
          <TwinCard key={twin.deviceId} twin={twin} />
        ))}
      </div>
    </WidgetWrapper>
  );
}
