import React, { useState, useMemo } from 'react';
import { Virtuoso } from 'react-virtuoso';
import { ArrowDown, ArrowUp, ArrowUpDown, Download, Filter, Search, SlidersHorizontal } from 'lucide-react';

export interface ColumnDef<T> {
  key: string;
  header: string;
  cell: (row: T) => React.ReactNode;
  sortable?: boolean;
  width?: string | number;
}

interface DataTableProps<T> {
  data: T[];
  columns: ColumnDef<T>[];
  onRowClick?: (row: T) => void;
  searchable?: boolean;
  searchKey?: string;
}

export function DataTable<T extends Record<string, any>>({ 
  data, 
  columns, 
  onRowClick,
  searchable = true,
  searchKey
}: DataTableProps<T>) {
  const [sortKey, setSortKey] = useState<string | null>(null);
  const [sortDirection, setSortDirection] = useState<'asc' | 'desc'>('asc');
  const [searchQuery, setSearchQuery] = useState('');

  const handleSort = (key: string) => {
    if (sortKey === key) {
      if (sortDirection === 'asc') setSortDirection('desc');
      else setSortKey(null);
    } else {
      setSortKey(key);
      setSortDirection('asc');
    }
  };

  const processedData = useMemo(() => {
    let result = [...data];

    if (searchQuery && searchKey) {
      result = result.filter(row => 
        String(row[searchKey]).toLowerCase().includes(searchQuery.toLowerCase())
      );
    }

    if (sortKey) {
      result.sort((a, b) => {
        const valA = a[sortKey];
        const valB = b[sortKey];
        if (valA < valB) return sortDirection === 'asc' ? -1 : 1;
        if (valA > valB) return sortDirection === 'asc' ? 1 : -1;
        return 0;
      });
    }

    return result;
  }, [data, sortKey, sortDirection, searchQuery, searchKey]);

  return (
    <div className="flex flex-col h-full bg-[#0a0a0f] border border-[#1f1f28] rounded-xl overflow-hidden">
      
      {/* Toolbar */}
      <div className="flex items-center justify-between p-3 border-b border-[#1f1f28] bg-[#13131a] shrink-0">
        {searchable && searchKey ? (
          <div className="relative w-64">
            <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
            <input 
              type="text" 
              placeholder="Search..." 
              value={searchQuery}
              onChange={e => setSearchQuery(e.target.value)}
              className="w-full bg-[#060b14] border border-[#1f1f28] rounded-md pl-9 pr-3 py-1.5 text-sm focus:outline-none focus:border-blue-500 transition-colors"
            />
          </div>
        ) : <div />}
        
        <div className="flex items-center gap-2">
          <button className="p-1.5 hover:bg-[#1f1f28] rounded text-muted-foreground hover:text-foreground transition-colors" title="Filter">
            <Filter size={16} />
          </button>
          <button className="p-1.5 hover:bg-[#1f1f28] rounded text-muted-foreground hover:text-foreground transition-colors" title="Columns">
            <SlidersHorizontal size={16} />
          </button>
          <button className="p-1.5 hover:bg-[#1f1f28] rounded text-muted-foreground hover:text-foreground transition-colors" title="Export CSV">
            <Download size={16} />
          </button>
        </div>
      </div>

      {/* Header Row */}
      <div className="flex items-center border-b border-[#1f1f28] bg-[#060b14] px-4 py-3 shrink-0">
        {columns.map(col => (
          <div 
            key={col.key} 
            className="text-xs font-bold uppercase tracking-wider text-muted-foreground flex items-center gap-1.5"
            style={{ width: col.width || `${100 / columns.length}%` }}
          >
            <span className={col.sortable ? 'cursor-pointer hover:text-foreground transition-colors select-none' : ''} onClick={() => col.sortable && handleSort(col.key)}>
              {col.header}
            </span>
            {col.sortable && (
              <span className="text-muted-foreground/50">
                {sortKey === col.key ? (
                  sortDirection === 'asc' ? <ArrowUp size={12} className="text-blue-400" /> : <ArrowDown size={12} className="text-blue-400" />
                ) : (
                  <ArrowUpDown size={12} className="opacity-0 group-hover:opacity-100 transition-opacity" />
                )}
              </span>
            )}
          </div>
        ))}
      </div>

      {/* Virtualized Body */}
      <div className="flex-1 bg-[#060b14]">
        {processedData.length === 0 ? (
          <div className="flex items-center justify-center h-full text-muted-foreground text-sm">
            No records found.
          </div>
        ) : (
          <Virtuoso
            data={processedData}
            itemContent={(_index, row) => (
              <div 
                onClick={() => onRowClick?.(row)}
                className={`flex items-center px-4 py-3 border-b border-[#1f1f28]/50 hover:bg-[#13131a] transition-colors ${onRowClick ? 'cursor-pointer' : ''}`}
              >
                {columns.map(col => (
                  <div key={col.key} className="truncate" style={{ width: col.width || `${100 / columns.length}%` }}>
                    {col.cell(row)}
                  </div>
                ))}
              </div>
            )}
          />
        )}
      </div>

      {/* Footer */}
      <div className="flex items-center justify-between px-4 py-2 border-t border-[#1f1f28] bg-[#13131a] shrink-0 text-xs text-muted-foreground">
        <span>{processedData.length} records</span>
      </div>
    </div>
  );
}
