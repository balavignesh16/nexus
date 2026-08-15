import { siteApi } from '../api/siteApi';
import { deviceApi } from '../api/deviceApi';
import { eventApi } from '../api/eventApi';
import { actionApi } from '../api/actionApi';
import { telemetryApi } from '../api/telemetryApi';
import { twinApi } from '../api/twinApi';
import { usePolling } from './usePolling';

export function useSites() {
  return usePolling(['sites'], () => siteApi.getSites(), { interval: 10000 });
}

export function useActiveDevices() {
  return usePolling(['activeDevices'], () => deviceApi.getActiveDevices(), { interval: 5000 });
}

export function useEvents(limit = 20) {
  return usePolling(['events', limit], () => eventApi.getEvents(limit), { interval: 3000 });
}

export function useActionHistory(limit = 20) {
  return usePolling(['actions', limit], () => actionApi.getActionHistory(limit), { interval: 3000 });
}

export function useLatestTelemetry(limit = 100) {
  return usePolling(['telemetry', limit], () => telemetryApi.getLatestTelemetry(limit), { interval: 3000 });
}

export function useLatestTwins(limit = 12) {
  return usePolling(['twins', limit], () => twinApi.getLatestTwins(limit), { interval: 3000 });
}

// Hierarchy Queries
import { buildingApi } from '../api/buildingApi';
import { spaceApi } from '../api/spaceApi';

export function useBuildingsBySite(siteId: string) {
  return usePolling(['buildings', siteId], () => buildingApi.getBuildingsBySiteId(siteId), { interval: 10000 });
}

export function useSpacesByBuilding(buildingId: string) {
  return usePolling(['spaces', buildingId], () => spaceApi.getSpacesByBuildingId(buildingId), { interval: 10000 });
}

export function useDevicesBySpace(spaceId: string) {
  return usePolling(['devices', spaceId], () => deviceApi.getDevicesBySpaceId(spaceId), { interval: 10000 });
}

import { useRepositories } from '../repositories/RepositoryProvider';

export function useDevice(deviceId: string) {
  const { device } = useRepositories();
  return usePolling(['device', deviceId], () => device.getDevice(deviceId), { interval: 2000 });
}

