import { useQueryClient } from '@tanstack/react-query';
import { useRepositories } from '../repositories/RepositoryProvider';
import { usePolling } from './usePolling';
import { deviceApi, type DeviceConnectivityResult } from '../api/deviceApi';
import { FEATURES } from '../config/features';
import { useDeviceConnectivityStream } from '../realtime/streams/useDeviceConnectivityStream';

export interface DeviceTwinState {
  connectivity: DeviceConnectivityResult | undefined;
  connectivityLoading: boolean;
  connectivityError: unknown;
  /** Real timestamp of the most recent telemetry record, or null if none exists. */
  lastTelemetryAt: string | null;
  /** Field names present in the most recent telemetry payload - a real count, never compared to an invented "expected" total. */
  reportedFields: string[];
  telemetryLoading: boolean;
  telemetryError: unknown;
}

/**
 * A small, evidence-based Digital Twin state (Phase 8) - replaces the
 * removed client-side healthScore/twinQuality heuristic. Every field here
 * is either read directly from a real backend response (connectivity) or
 * derived from one (telemetry recency/field count). Nothing here is
 * invented: there is no "expected field count", no freshness threshold,
 * and no 0-100 quality score.
 */
export function useDeviceTwinState(deviceId: string): DeviceTwinState {
  const { telemetry } = useRepositories();
  const queryClient = useQueryClient();

  const connectivityQuery = usePolling<DeviceConnectivityResult>(
    ['device-connectivity', deviceId],
    () => deviceApi.getDeviceConnectivity(deviceId),
    { interval: 10000, enabled: !FEATURES.DEMO_MODE }
  );

  // Step 9C: usePolling above disables its own refetch once realtime is
  // connected, which previously left every "LIVE" connectivity badge
  // sourced from this hook (TwinsPage, DigitalTwinTab, OverviewTab) frozen
  // at whatever value was fetched on mount - exactly when the connection
  // is healthiest. A real DeviceOnline/DeviceOffline push (the same real
  // stream DeviceWorkspace's own connectivity badge already uses) is
  // written into the same ['device-connectivity', deviceId] cache entry,
  // so every consumer of that query key sees the live value from then on.
  // Only `status` is updated - `lastChangedAt` is never fabricated with a
  // client-side timestamp; it keeps the last real value the backend
  // reported, or `null` if none has been fetched yet.
  useDeviceConnectivityStream(deviceId, (payload) => {
    queryClient.setQueryData<DeviceConnectivityResult>(['device-connectivity', deviceId], (old) =>
      old ? { ...old, status: payload.status } : { deviceId, status: payload.status, lastChangedAt: null }
    );
  });

  const telemetryQuery = usePolling<any[]>(
    ['device-twin-telemetry', deviceId],
    () => telemetry.getTelemetryHistory(deviceId),
    { interval: 10000 }
  );

  const history = telemetryQuery.data ?? [];
  // Both real sources return most-recent-first: the backend repository
  // requests ?sort=desc explicitly; the demo repository returns a single
  // synthetic "now" record. Index 0 is always the latest either way.
  const latest = history.length > 0 ? history[0] : null;

  return {
    connectivity: connectivityQuery.data,
    connectivityLoading: connectivityQuery.isLoading,
    connectivityError: connectivityQuery.error,
    lastTelemetryAt: latest?.timestamp ?? null,
    reportedFields: latest?.payload ? Object.keys(latest.payload) : [],
    telemetryLoading: telemetryQuery.isLoading,
    telemetryError: telemetryQuery.error,
  };
}
