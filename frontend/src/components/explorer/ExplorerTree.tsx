import { useState, useMemo } from 'react';
import { Folder, Building2, Box, ChevronRight, ChevronDown, AlertTriangle, MoreVertical } from 'lucide-react';
import { Virtuoso } from 'react-virtuoso';
import type { SelectedNode, NodeType } from '../../pages/ExplorerPage';

interface ExplorerTreeProps {
  selectedNode: SelectedNode | null;
  onSelect: (node: SelectedNode) => void;
}

interface TreeNodeData {
  id: string;
  name: string;
  type: NodeType | 'FLOOR' | 'ROOM';
  children?: TreeNodeData[];
  hasWarning?: boolean;
}

const STATIC_TREE: TreeNodeData[] = [
  {
    id: 'site-test',
    name: 'Test Site',
    type: 'SITE',
    children: [
      {
        id: 'bldg-main',
        name: 'Main Building',
        type: 'BUILDING',
        children: [
          {
            id: 'bldg1-floor-1',
            name: 'Floor 1',
            type: 'FLOOR',
            children: [
              { id: 'dev-temp-001', name: 'Temperature Sensor 1', type: 'DEVICE' },
              { id: 'dev-mot-001', name: 'Motion Sensor 1', type: 'DEVICE' },
            ]
          },
          {
            id: 'bldg1-floor-2',
            name: 'Floor 2',
            type: 'FLOOR',
            hasWarning: true,
            children: [
              { id: 'dev-gas-001', name: 'Gas Sensor 1', type: 'DEVICE' },
            ]
          }
        ]
      },
      {
        id: 'bldg-annex',
        name: 'Annex Building',
        type: 'BUILDING',
        children: [
          {
            id: 'bldg2-floor-1',
            name: 'Floor 1',
            type: 'FLOOR',
            children: [
              { id: 'dev-hum-001', name: 'Humidity Sensor 1', type: 'DEVICE' },
            ]
          },
          {
            id: 'bldg2-floor-2',
            name: 'Floor 2',
            type: 'FLOOR',
            children: [
              { id: 'dev-pwr-001', name: 'Energy Meter 1', type: 'DEVICE' },
              { id: 'dev-hvac-001', name: 'HVAC Controller 1', type: 'DEVICE' },
            ]
          }
        ]
      }
    ]
  }
];

interface FlatNode {
  node: TreeNodeData;
  level: number;
  path: { id: string; name: string; type: NodeType }[];
}

export function ExplorerTree({ selectedNode, onSelect }: ExplorerTreeProps) {
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set([
    'site-test', 'bldg-main', 'bldg1-floor-1', 'bldg-annex', 'bldg2-floor-2'
  ]));
  const searchTerm: string = '';

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
    const flatten = (nodes: TreeNodeData[], level: number, parentPath: any[]) => {
      nodes.forEach(node => {
        // Map FLOOR and ROOM to SPACE for the SelectedNode type compatibility
        const mappedType = (node.type === 'FLOOR' || node.type === 'ROOM') ? 'SPACE' : node.type;
        const currentPath = [...parentPath, { id: node.id, name: node.name, type: mappedType }];
        
        // Basic search filter
        if (searchTerm && !node.name.toLowerCase().includes(searchTerm.toLowerCase()) && node.children?.length === 0) {
          return;
        }

        flat.push({ node, level, path: parentPath });
        if (expandedIds.has(node.id) && node.children) {
          flatten(node.children, level + 1, currentPath);
        }
      });
    };
    flatten(STATIC_TREE, 0, []);
    return flat;
  }, [expandedIds, searchTerm]);

  const getIcon = (type: string, isExpanded: boolean) => {
    switch (type) {
      case 'SITE': return <Folder size={14} className={isExpanded ? 'text-blue-400' : 'text-blue-500/70'} />;
      case 'BUILDING': return <Building2 size={14} className={isExpanded ? 'text-indigo-400' : 'text-indigo-500/70'} />;
      case 'FLOOR':
      case 'ROOM': return <Folder size={14} className={isExpanded ? 'text-cyan-400' : 'text-cyan-500/70'} />;
      case 'DEVICE': return <Box size={14} className="text-emerald-500" />;
      default: return <Folder size={14} />;
    }
  };

  return (
    <div className="flex flex-col h-full bg-[#0a0f1d] border-r border-[#1f1f28] shadow-xl z-20">
      <div className="flex-1 overflow-hidden relative">
        <Virtuoso
          className="h-full w-full custom-scrollbar py-2"
          data={flattenedNodes}
          itemContent={(_, { node, level, path }) => {
            const isExpanded = expandedIds.has(node.id);
            const isSelected = selectedNode?.id === node.id || (node.id === 'dev-temp-001' && !selectedNode);
            const hasChildren = node.children && node.children.length > 0;
            const mappedType = (node.type === 'FLOOR' || node.type === 'ROOM') ? 'SPACE' : node.type;

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
                      type: mappedType as NodeType,
                      path: [...path, { id: node.id, name: node.name, type: mappedType as NodeType }]
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
                  {node.hasWarning && (
                    <AlertTriangle size={12} className="text-amber-500 shrink-0 ml-1" />
                  )}
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
