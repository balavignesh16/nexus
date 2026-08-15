import { useActionHistory } from '../../hooks/queries';
import { ActionFeed } from '../ui/ActionFeed';
import { WidgetWrapper } from './WidgetWrapper';

export function ActionHistoryWidget() {
  const { data: actions = [], isLoading, isError, refetch } = useActionHistory(20);

  return (
    <WidgetWrapper
      title="Action Execution History"
      isLoading={isLoading}
      isError={isError}
      isEmpty={actions.length === 0}
      onRetry={() => refetch()}
      className="h-[400px]"
      noPadding
    >
      <ActionFeed actions={actions} />
    </WidgetWrapper>
  );
}
