import { useState, useMemo, useEffect } from 'react';
import { Folder, Building2, Box, ChevronRight, ChevronDown, MoreVertical, Loader2 } from 'lucide-react';
import { Virtuoso } from 'react-virtuoso';
import type { SelectedNode, NodeType } from '../../pages/ExplorerPage';
import { useExplorerHierarchy, type HierarchyNode } from '../../hooks/useExplorerHierarchy';

interface ExplorerTreeProps {
  selectedNode: SelectedNode | null;
  onSelect: (node: SelectedNode) => void;
}

interface FlatNode {
  node: HierarchyNode;
  level: number;
  path: { id: string; name: string; type: NodeType }[];
}

export function ExplorerTree({ selectedNode, onSelect }: ExplorerTreeProps) {
  const { data: hierarchy, isLoading } = useExplorerHierarchy();
  const tree = hierarchy?.tree ?? [];
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());

  // Auto-expand the first site once real data loads, so the tree isn't blank on first paint.
  useEffect(() => {
    if (tree.length > 0 && expandedIds.size === 0) {
      setExpandedIds(new Set([tree[0].id]));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tree]);

  const handleExpand = (id: string) => {
    setExpandedIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const flattenedNodes = useMemo(() => {
    const flat: FlatNode[] = [];
    const flatten = (nodes: HierarchyNode[], level: number, parentPath: FlatNode['path']) => {
      nodes.forEach(node => {
        const currentPath = [...parentPath, { id: node.id, name: node.name, type: node.type as NodeType }];
        flat.push({ node, level, path: parentPath });
        if (expandedIds.has(node.id) && node.children) {
          flatten(node.children, level + 1, currentPath);
        }
      });
    };
    // Auto-expand the first site by default so the tree isn't collapsed-empty on load.
    flatten(tree, 0, []);
    return flat;
  }, [tree, expandedIds]);

  const getIcon = (type: string, isExpanded: boolean) => {
    switch (type) {
      case 'SITE': return <Folder size={14} className={isExpanded ? 'text-blue-400' : 'text-blue-500/70'} />;
      case 'BUILDING': return <Building2 size={14} className={isExpanded ? 'text-indigo-400' : 'text-indigo-500/70'} />;
      case 'SPACE': return <Folder size={14} className={isExpanded ? 'text-cyan-400' : 'text-cyan-500/70'} />;
      case 'DEVICE': return <Box size={14} className="text-emerald-500" />;
      default: return <Folder size={14} />;
    }
  };

  if (isLoading) {
    return (
      <div className="flex flex-col h-full bg-[#0a0f1d] border-r border-[#1f1f28] items-center justify-center gap-2 text-muted-foreground">
        <Loader2 size={16} className="animate-spin" />
        <span className="text-xs">Loading infrastructure...</span>
      </div>
    );
  }

  if (tree.length === 0) {
    return (
      <div className="flex flex-col h-full bg-[#0a0f1d] border-r border-[#1f1f28] items-center justify-center gap-1 text-muted-foreground px-4 text-center">
        <span className="text-xs">No sites yet.</span>
        <span className="text-[11px] text-muted-foreground/70">Add a site to start building your infrastructure hierarchy.</span>
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full bg-[#0a0f1d] border-r border-[#1f1f28] shadow-xl z-20">
      <div className="flex-1 overflow-hidden relative">
        <Virtuoso
          className="h-full w-full custom-scrollbar py-2"
          data={flattenedNodes}
          itemContent={(_, { node, level, path }) => {
            const isExpanded = expandedIds.has(node.id);
            const isSelected = selectedNode?.id === node.id;
            const hasChildren = !!node.children && node.children.length > 0;

            return (
              <div
                className={`flex items-center w-full min-w-fit pr-2 py-1 cursor-pointer transition-colors group relative
                  ${isSelected ? 'bg-blue-600/15 border-l-2 border-blue-500' : 'border-l-2 border-transparent hover:bg-[#13131a]'}
                `}
                style={{ paddingLeft: `${level * 16 + 8}px` }}
                onClick={() => {
                  if (hasChildren) handleExpand(node.id);
                  else {
                    onSelect({
                      id: node.id,
                      name: node.name,
                      type: node.type as NodeType,
                      path: [...path, { id: node.id, name: node.name, type: node.type as NodeType }]
                    });
                  }
                }}
              >
                <div className="w-5 h-5 flex items-center justify-center shrink-0">
                  {hasChildren ? (
                    <button
                      onClick={(e) => { e.stopPropagation(); handleExpand(node.id); }}
                      className="p-0.5 hover:bg-muted/50 rounded text-muted-foreground hover:text-foreground"
                    >
                      {isExpanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
                    </button>
                  ) : (
                    <div className="w-4" />
                  )}
                </div>

                <div className="flex items-center gap-2 overflow-hidden flex-1 py-0.5">
                  <div className="shrink-0 flex items-center justify-center w-5 h-5 relative">
                    {getIcon(node.type, isExpanded)}
                  </div>
                  <span className={`text-[13px] truncate select-none ${isSelected ? 'text-blue-400 font-medium' : 'text-muted-foreground group-hover:text-foreground'}`}>
                    {node.name}
                  </span>
                </div>

                <div className="shrink-0 opacity-0 group-hover:opacity-100 transition-opacity pl-2">
                  <MoreVertical size={14} className="text-muted-foreground/50 hover:text-foreground" />
                </div>
              </div>
            );
          }}
        />
      </div>
    </div>
  );
}
