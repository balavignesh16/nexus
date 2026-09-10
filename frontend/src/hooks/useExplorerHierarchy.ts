import { siteApi } from '../api/siteApi';
import { buildingApi } from '../api/buildingApi';
import { spaceApi } from '../api/spaceApi';
import { deviceApi } from '../api/deviceApi';
import { usePolling } from './usePolling';

export type HierarchyNodeType = 'SITE' | 'BUILDING' | 'SPACE' | 'DEVICE';

export interface HierarchyNode {
  id: string;
  name: string;
  type: HierarchyNodeType;
  children?: HierarchyNode[];
}

export interface ExplorerHierarchy {
  tree: HierarchyNode[];
  siteCount: number;
  buildingCount: number;
  spaceCount: number;
  deviceCount: number;
}

function countByType(nodes: HierarchyNode[], type: HierarchyNodeType): number {
  let count = 0;
  for (const node of nodes) {
    if (node.type === type) count += 1;
    if (node.children) count += countByType(node.children, type);
  }
  return count;
}

async function fetchHierarchy(): Promise<ExplorerHierarchy> {
  const sites = await siteApi.getSites();

  const tree: HierarchyNode[] = await Promise.all(
    sites.map(async (site) => {
      const buildings = await buildingApi.getBuildingsBySiteId(site.id);
      const buildingNodes: HierarchyNode[] = await Promise.all(
        buildings.map(async (building) => {
          const spaces = await spaceApi.getSpacesByBuildingId(building.id);
          const spaceNodes: HierarchyNode[] = await Promise.all(
            spaces.map(async (space) => {
              const devices = await deviceApi.getDevicesBySpaceId(space.id);
              const deviceNodes: HierarchyNode[] = devices.map((device) => ({
                id: device.id,
                name: device.name,
                type: 'DEVICE',
              }));
              return { id: space.id, name: space.name, type: 'SPACE', children: deviceNodes } as HierarchyNode;
            })
          );
          return { id: building.id, name: building.name, type: 'BUILDING', children: spaceNodes } as HierarchyNode;
        })
      );
      return { id: site.id, name: site.name, type: 'SITE', children: buildingNodes } as HierarchyNode;
    })
  );

  return {
    tree,
    siteCount: sites.length,
    buildingCount: countByType(tree, 'BUILDING'),
    spaceCount: countByType(tree, 'SPACE'),
    deviceCount: countByType(tree, 'DEVICE'),
  };
}

/** Real site -> building -> space -> device hierarchy, fetched from the backend. */
export function useExplorerHierarchy() {
  return usePolling(['explorer-hierarchy'], fetchHierarchy, { interval: 15000 });
}
