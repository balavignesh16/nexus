import type { SelectedNode } from '../../pages/ExplorerPage';
import { ChevronRight, Server, Building2, Layers3, Cpu } from 'lucide-react';

interface WorkspaceBreadcrumbProps {
  selectedNode: SelectedNode | null;
}

export function WorkspaceBreadcrumb({ selectedNode }: WorkspaceBreadcrumbProps) {
  if (!selectedNode) {
    return (
      <div className="h-10 border-b border-border bg-card/50 flex items-center px-4">
        <span className="text-sm font-medium text-muted-foreground">Select a node to explore</span>
      </div>
    );
  }

  const getIcon = (type: string) => {
    switch (type) {
      case 'SITE': return <Server size={14} className="text-primary" />;
      case 'BUILDING': return <Building2 size={14} className="text-success" />;
      case 'SPACE': return <Layers3 size={14} className="text-warning" />;
      case 'DEVICE': return <Cpu size={14} className="text-[#8b5cf6]" />;
      default: return null;
    }
  };

  return (
    <div className="h-10 border-b border-border bg-card/50 flex items-center px-4 overflow-x-auto whitespace-nowrap scrollbar-hide">
      <div className="flex items-center text-sm">
        {selectedNode.path.map((node, index) => (
          <div key={node.id} className="flex items-center">
            {index > 0 && <ChevronRight size={14} className="mx-2 text-muted-foreground shrink-0" />}
            <div className={`flex items-center gap-1.5 ${index === selectedNode.path.length - 1 ? 'text-foreground font-medium' : 'text-muted-foreground hover:text-foreground cursor-pointer transition-colors'}`}>
              {getIcon(node.type)}
              <span>{node.name}</span>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
