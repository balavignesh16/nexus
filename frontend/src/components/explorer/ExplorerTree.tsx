import { useState, useMemo, useEffect } from 'react';
import { Folder, Building2, Box, ChevronRight, ChevronDown, Loader2 } from 'lucide-react';
import { Virtuoso } from 'react-virtuoso';
import type { SelectedNode, NodeType } from '../../pages/ExplorerPage';
import { useExplorerHierarchy, type HierarchyNode } from '../../hooks/useExplorerHierarchy';
import { EmptyState } from '../foundation/EmptyState';

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
    flatten(tree, 0, []);
    return flat;
  }, [tree, expandedIds]);

  const getIcon = (type: string) => {
    switch (type) {
      case 'SITE': return <Folder size={14} />;
      case 'BUILDING': return <Building2 size={14} />;
      case 'SPACE': return <Folder size={14} />;
      case 'DEVICE': return <Box size={14} />;
      default: return <Folder size={14} />;
    }
  };

  if (isLoading) {
    return (
      <div className="flex flex-col h-full items-center justify-center gap-2 text-foundation-muted-fg">
        <Loader2 size={16} className="animate-spin" />
        <span className="text-xs">Loading infrastructure...</span>
      </div>
    );
  }

  if (tree.length === 0) {
    return (
      <div className="h-full flex items-center justify-center px-4">
        <EmptyState
          icon={<Folder size={28} />}
          title="No sites yet."
          description="Add a site to start building your infrastructure hierarchy."
        />
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full">
      <Virtuoso
        className="h-full w-full custom-scrollbar py-2"
        data={flattenedNodes}
        itemContent={(_, { node, level, path }) => {
          const isExpanded = expandedIds.has(node.id);
          const isSelected = selectedNode?.id === node.id;
          const hasChildren = !!node.children && node.children.length > 0;

          return (
            <div
              className="px-2"
              style={{ paddingLeft: `${level * 14 + 8}px` }}
            >
              <div
                className="flex items-center w-full min-w-fit gap-2 pr-2 py-1.5 my-0.5 cursor-pointer rounded-[var(--radius-panel)] transition-colors"
                style={isSelected ? { backgroundColor: 'var(--accent)', color: 'var(--accent-foreground)' } : undefined}
                onMouseDown={(e) => e.preventDefault()}
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
                <div className="w-4 h-4 flex items-center justify-center shrink-0">
                  {hasChildren ? (
                    <button
                      onClick={(e) => { e.stopPropagation(); handleExpand(node.id); }}
                      className={isSelected ? 'text-[var(--accent-foreground)]/80' : 'text-foundation-muted-fg'}
                    >
                      {isExpanded ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
                    </button>
                  ) : null}
                </div>

                <div className={`shrink-0 flex items-center justify-center ${isSelected ? '' : 'text-foundation-muted-fg'}`}>
                  {getIcon(node.type)}
                </div>
                <span className={`text-[13px] truncate select-none ${isSelected ? 'font-medium' : 'text-foundation-fg/90'}`}>
                  {node.name}
                </span>
              </div>
            </div>
          );
        }}
      />
    </div>
  );
}
