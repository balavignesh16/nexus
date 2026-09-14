import type { ReactNode } from 'react';
import { cn } from '../../lib/utils';

export interface TableColumn<T> {
  key: string;
  header: string;
  render: (row: T) => ReactNode;
  align?: 'left' | 'right' | 'center';
}

interface TableProps<T> {
  columns: TableColumn<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  className?: string;
}

/**
 * A generic styled table shell (Step 1 foundation) - distinct from the
 * existing production DataTable.tsx, which stays untouched and in use.
 * Intended for later adoption once individual pages are redesigned.
 */
export function Table<T>({ columns, rows, rowKey, className }: TableProps<T>) {
  return (
    <div className={cn('overflow-x-auto rounded-[var(--radius-panel)] border border-foundation-border', className)}>
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-foundation-border bg-black/[0.02]">
            {columns.map((col) => (
              <th
                key={col.key}
                className={cn(
                  'px-4 py-2.5 text-[10px] font-semibold uppercase tracking-wider text-foundation-muted-fg',
                  col.align === 'right' ? 'text-right' : col.align === 'center' ? 'text-center' : 'text-left'
                )}
              >
                {col.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={rowKey(row)} className="border-b border-foundation-border/60 last:border-0 hover:bg-black/[0.02] transition-colors">
              {columns.map((col) => (
                <td
                  key={col.key}
                  className={cn(
                    'px-4 py-2.5 text-foundation-fg/90',
                    col.align === 'right' ? 'text-right' : col.align === 'center' ? 'text-center' : 'text-left'
                  )}
                >
                  {col.render(row)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
