import { spaceApi } from '../api/spaceApi';
import { buildingApi } from '../api/buildingApi';
import { siteApi } from '../api/siteApi';
import { usePolling } from './usePolling';

export interface DeviceLocationPath {
  site: string | null;
  building: string | null;
  space: string | null;
}

async function resolvePath(spaceId: string): Promise<DeviceLocationPath> {
  const space = await spaceApi.getSpace(spaceId);
  const building = await buildingApi.getBuilding(space.buildingId);
  const site = await siteApi.getSite(building.siteId);
  return { site: site.name, building: building.name, space: space.name };
}

/**
 * Resolves a device's real Site -> Building -> Space path from its
 * spaceId (Phase 8) - the same canonical hierarchy Explorer already uses,
 * not a duplicate/derived copy. If any level cannot be resolved, the query
 * fails honestly (the caller renders "Location unavailable") rather than
 * falling back to a placeholder location.
 */
export function useDeviceLocationPath(spaceId: string | undefined) {
  return usePolling<DeviceLocationPath>(
    ['device-location-path', spaceId],
    () => resolvePath(spaceId as string),
    { interval: 0, enabled: !!spaceId }
  );
}
