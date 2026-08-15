import { useState, useEffect } from 'react';
import { Search, Activity, Plus } from 'lucide-react';
import { ExplorerTree } from '../components/explorer/ExplorerTree';
import { SiteWorkspace } from '../components/explorer/SiteWorkspace';
import { BuildingWorkspace } from '../components/explorer/BuildingWorkspace';
import { SpaceWorkspace } from '../components/explorer/SpaceWorkspace';
import { DeviceWorkspace } from '../components/explorer/device/DeviceWorkspace';
import { EmptyWorkspace } from '../components/explorer/EmptyWorkspace';
import { useSites } from '../hooks/queries';

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
    <>
      {/* PANE 2: INFRASTRUCTURE EXPLORER PANEL */}
      <div className="w-[260px] flex-shrink-0 border-r border-[#1f1f28] bg-[#0a0f1d] flex flex-col shadow-[4px_0_24px_rgba(0,0,0,0.2)] z-10">
        <div className="h-14 flex items-center justify-between px-4 shrink-0">
          <h2 className="font-semibold text-sm tracking-wide text-white">Infrastructure Explorer</h2>
          <div className="flex gap-1 text-muted-foreground">
            <button className="p-1 hover:bg-[#13131a] rounded transition-colors"><Activity size={14} /></button>
          </div>
        </div>
        
        <div className="px-3 pb-3 shrink-0">
          <div className="relative group">
            <Search size={14} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-muted-foreground" />
            <input 
              id="tree-search"
              type="text" 
              placeholder="Search hierarchy..." 
              className="w-full h-8 pl-8 pr-10 bg-[#13131a] border border-[#1f1f28] rounded text-xs focus:outline-none focus:border-blue-500/50 transition-all placeholder:text-muted-foreground"
            />
            <div className="absolute right-2 top-1/2 -translate-y-1/2 flex items-center gap-1 opacity-50">
              <kbd className="text-[9px] font-sans px-1 py-0.5 rounded border border-[#1f1f28] bg-background">Ctrl K</kbd>
            </div>
          </div>
        </div>

        <div className="flex-1 overflow-hidden relative border-t border-[#1f1f28]">
          <ExplorerTree 
            selectedNode={selectedNode} 
            onSelect={setSelectedNode} 
          />
        </div>
        
        <div className="p-3 border-t border-[#1f1f28] shrink-0 bg-[#0a0f1d]">
          <button className="w-full flex items-center justify-center gap-2 px-3 py-2 bg-transparent border border-dashed border-[#1f1f28] text-muted-foreground hover:border-muted-foreground hover:text-foreground rounded-md text-xs font-medium transition-all">
            <Plus size={14} /> Add New
          </button>
        </div>
      </div>
      
      {/* PANE 3: MAIN OPERATIONS WORKSPACE */}
      <div className="flex-1 min-w-0 bg-[#060b14] flex flex-col relative overflow-hidden z-0">
        {/* Workspace Content */}
        <div className="flex-1 overflow-y-auto custom-scrollbar relative">
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
    </>
  );
}
