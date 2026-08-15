import { ChevronRight, ChevronDown, Server, Building2, Layers3, Cpu } from 'lucide-react';
import type { NodeType, SelectedNode } from '../../pages/ExplorerPage';

export interface NodeData {
  id: string;
  type: NodeType;
  name: string;
  status?: string;
  children?: NodeData[];
  hasChildren: boolean;
  isLoaded: boolean;
  deviceCount?: number;
  healthScore?: number;
}

interface TreeNodeProps {
  node: NodeData;
  level: number;
  selectedNode: SelectedNode | null;
  onSelect: (node: SelectedNode) => void;
  onExpand: () => void;
  path: { id: string; name: string; type: NodeType }[];
  isExpanded: boolean;
}

export function TreeNode({ node, level, selectedNode, onSelect, onExpand, path, isExpanded }: TreeNodeProps) {
  const isSelected = selectedNode?.id === node.id;
  const currentPath = [...path, { id: node.id, name: node.name, type: node.type }];

  const handleToggle = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (node.hasChildren) {
      onExpand();
    }
  };

  const handleSelect = () => {
    onSelect({
      id: node.id,
      type: node.type,
      name: node.name,
      path: currentPath
    });
  };

  const getIcon = (type: NodeType) => {
    switch (type) {
      case 'SITE': return <Server size={14} className="text-primary" />;
      case 'BUILDING': return <Building2 size={14} className="text-success" />;
      case 'SPACE': return <Layers3 size={14} className="text-warning" />;
      case 'DEVICE': return <Cpu size={14} className="text-[#8b5cf6]" />;
    }
  };

  return (
    <div 
      className={`flex items-start py-2.5 px-2 cursor-pointer select-none transition-all group relative border-l-2 ${isSelected ? 'bg-primary/10 border-primary' : 'hover:bg-card/50 border-transparent'}`}
      style={{ paddingLeft: `${level * 16 + 8}px` }}
      onClick={handleSelect}
      onContextMenu={(e) => { e.preventDefault(); }}
    >
      {isSelected && (
        <div className="absolute inset-0 bg-primary/5 blur-md pointer-events-none" />
      )}
      
      <div 
        className={`w-4 h-4 mr-1 mt-0.5 flex items-center justify-center shrink-0 transition-transform ${node.hasChildren ? 'hover:bg-muted rounded text-muted-foreground hover:text-foreground cursor-pointer' : 'opacity-0 cursor-default'}`}
        onClick={handleToggle}
      >
        {isExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
      </div>
      
      <div className="mr-2 mt-0.5 opacity-90 group-hover:opacity-100 transition-opacity shrink-0">
        {getIcon(node.type)}
      </div>
      
      <div className="flex-1 min-w-0 flex flex-col justify-center space-y-1.5">
        <div className="flex items-center justify-between gap-2">
          <span className={`text-[13px] truncate ${isSelected ? 'font-semibold text-foreground' : 'font-medium text-foreground/90 group-hover:text-foreground'}`}>
            {node.name}
          </span>
          <span className="relative flex h-2 w-2 shrink-0">
            {node.type === 'DEVICE' ? (
               node.status === 'ACTIVE' ? <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-success opacity-75"></span> : null
            ) : (
               (node.healthScore || 100) > 90 ? <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-success opacity-75"></span> : null
            )}
            <span className={`relative inline-flex rounded-full h-2 w-2 ${
              node.type === 'DEVICE' 
                ? (node.status === 'ACTIVE' ? 'bg-success' : node.status === 'OFFLINE' ? 'bg-danger' : 'bg-warning')
                : ((node.healthScore || 100) > 90 ? 'bg-success' : 'bg-warning')
            }`}></span>
          </span>
        </div>
        
        {/* Rich Metadata Row */}
        {node.type !== 'DEVICE' ? (
          <div className="flex items-center flex-wrap gap-2 text-[10px] text-muted-foreground font-medium">
            <span className="bg-muted/50 px-1.5 py-0.5 rounded border border-border/50 text-foreground/80 shadow-sm">{node.deviceCount || 0} Devices</span>
            <span className={`px-1.5 py-0.5 rounded border shadow-sm ${node.healthScore && node.healthScore > 90 ? 'bg-success/10 text-success border-success/20' : 'bg-warning/10 text-warning border-warning/20'}`}>{node.healthScore || 100}% Healthy</span>
            <span className="bg-danger/10 text-danger border border-danger/20 px-1.5 py-0.5 rounded shadow-sm">3 Alerts</span>
          </div>
        ) : (
          <div className="flex items-center gap-2 text-[10px] font-medium">
            <span className="bg-muted/50 px-1.5 py-0.5 rounded border border-border/50 text-muted-foreground uppercase tracking-wider">{node.status || 'UNKNOWN'}</span>
            <span className="text-muted-foreground/60">{node.id.substring(0,8)}</span>
          </div>
        )}
      </div>
    </div>
  );
}
