import { useQuery, type UseQueryOptions, type QueryKey } from '@tanstack/react-query';
import { useConnectionStatus } from '../services/ConnectionManager';

interface PollingOptions<TData, TError = unknown> extends Omit<UseQueryOptions<TData, TError, TData, QueryKey>, 'queryKey' | 'queryFn'> {
  interval?: number;
}

export function usePolling<TData, TError = unknown>(
  queryKey: QueryKey,
  queryFn: () => Promise<TData>,
  options?: PollingOptions<TData, TError>
) {
  const { interval = 3000, ...rest } = options || {};
  const connection = useConnectionStatus();
  
  // Disable polling completely if we have a healthy realtime connection (backend or demo)
  const shouldPoll = connection.realtimeStatus !== 'connected' && connection.realtimeStatus !== 'demo';
  const effectiveInterval = shouldPoll && interval > 0 ? interval : false;

  return useQuery<TData, TError, TData, QueryKey>({
    queryKey,
    queryFn,
    refetchInterval: effectiveInterval,
    ...rest,
  });
}
