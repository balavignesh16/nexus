import { useState, useEffect } from 'react';
import { Boxes } from 'lucide-react';
import { ExplorerTree } from '../components/explorer/ExplorerTree';
import { SiteWorkspace } from '../components/explorer/SiteWorkspace';
import { BuildingWorkspace } from '../components/explorer/BuildingWorkspace';
import { SpaceWorkspace } from '../components/explorer/SpaceWorkspace';
import { DeviceWorkspace } from '../components/explorer/device/DeviceWorkspace';
import { EmptyWorkspace } from '../components/explorer/EmptyWorkspace';
import { useSites } from '../hooks/queries';
import { GlassPanel } from '../components/foundation/GlassPanel';

export type NodeType = 'SITE' | 'BUILDING' | 'SPACE' | 'DEVICE';

export interface SelectedNode {
  id: string;
  type: NodeType;
  name: string;
  path: { id: string; name: string; type: NodeType }[];
}

export default function ExplorerPage() {
  const [selectedNode, setSelectedNode] = useState<SelectedNode | null>(null);
  const { data: sites = [] } = useSites();

  useEffect(() => {
    if (!selectedNode && sites.length > 0) {
      const firstSite = sites[0];
      setSelectedNode({
        id: firstSite.id,
        type: 'SITE',
        name: firstSite.name,
        path: [{ id: firstSite.id, name: firstSite.name, type: 'SITE' }]
      });
    }
  }, [sites, selectedNode]);

  return (
    <div className="flex flex-1 min-h-0 gap-4 p-4" style={{ backgroundColor: 'var(--foundation-bg)' }}>
      {/* LEFT: Glass hierarchy rail */}
      <aside className="w-[280px] shrink-0 hidden md:flex flex-col min-h-0">
        <GlassPanel level="elevated" radius="panel-lg" className="flex-1 flex flex-col min-h-0 overflow-hidden">
          <div className="h-14 flex items-center gap-2.5 px-4 shrink-0 border-b border-foundation-border">
            <div
              className="w-8 h-8 rounded-full flex items-center justify-center shrink-0"
              style={{ backgroundColor: 'color-mix(in srgb, var(--accent) 14%, transparent)', color: 'var(--accent)' }}
            >
              <Boxes size={16} />
            </div>
            <h2 className="font-semibold text-sm text-foundation-fg">Infrastructure</h2>
          </div>

          <div className="flex-1 min-h-0 overflow-hidden">
            <ExplorerTree
              selectedNode={selectedNode}
              onSelect={setSelectedNode}
            />
          </div>
        </GlassPanel>
      </aside>

      {/* RIGHT: Light/glass contextual workspace */}
      <div className="flex-1 min-w-0 flex flex-col min-h-0 overflow-hidden">
        <div className="flex-1 overflow-y-auto custom-scrollbar">
          {!selectedNode ? (
            <EmptyWorkspace />
          ) : selectedNode.type === 'SITE' ? (
            <SiteWorkspace siteId={selectedNode.id} />
          ) : selectedNode.type === 'BUILDING' ? (
            <BuildingWorkspace buildingId={selectedNode.id} />
          ) : selectedNode.type === 'SPACE' ? (
            <SpaceWorkspace spaceId={selectedNode.id} />
          ) : (
            <DeviceWorkspace deviceId={selectedNode.id} />
          )}
        </div>
      </div>
    </div>
  );
}
