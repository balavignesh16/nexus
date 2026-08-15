import { useQuery } from '@tanstack/react-query';
import { useLiveManager } from './LiveUpdateProvider';

/**
 * A custom hook that wrappers react-query's useQuery.
 * It does NOT poll the backend itself; it relies on the PollingManager 
 * to fetch and populate the cache. Thus, it acts purely as a local state 
 * subscription tied to the global live updates.
 */
export function useLiveUpdates<T>(queryKey: any[], fallbackData: T): T {
  // We call useLiveManager just to trigger re-renders if global status changes
  // and to ensure we are inside a LiveUpdateProvider context.
  useLiveManager();

  const { data } = useQuery<T>({
    queryKey,
    // We don't provide a queryFn because PollingManager does the fetching.
    // If it's not in the cache, it'll return undefined initially.
    enabled: false, 
  });

  return data ?? fallbackData;
}
