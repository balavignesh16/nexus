import { useEvents } from '../../hooks/queries';
import { EventFeed } from '../ui/EventFeed';
import { WidgetWrapper } from './WidgetWrapper';

export function RecentEventsWidget() {
  const { data: events = [], isLoading, isError, refetch } = useEvents(20);

  return (
    <WidgetWrapper
      title="Recent Events"
      isLoading={isLoading}
      isError={isError}
      isEmpty={events.length === 0}
      onRetry={() => refetch()}
      className="h-[400px]"
      noPadding
    >
      <EventFeed events={events} />
    </WidgetWrapper>
  );
}
