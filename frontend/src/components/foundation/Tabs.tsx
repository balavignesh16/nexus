import { cn } from '../../lib/utils';

export interface TabItem {
  value: string;
  label: string;
}

interface TabsProps {
  items: TabItem[];
  value: string;
  onChange: (value: string) => void;
  className?: string;
}

/**
 * A generic horizontal tab strip (Step 1: foundation only). Deliberately
 * NOT wired to Site -> Building -> Space here - that real navigation
 * hierarchy redesign is explicitly deferred to the AppShell/Overview
 * phase. This component only needs to be capable of representing that
 * later, via plain {value,label} items.
 */
export function Tabs({ items, value, onChange, className }: TabsProps) {
  return (
    <div role="tablist" className={cn('inline-flex items-center gap-1 p-1 rounded-[var(--radius-pill)] bg-black/[0.03] border border-foundation-border', className)}>
      {items.map((item) => {
        const active = item.value === value;
        return (
          <button
            key={item.value}
            role="tab"
            aria-selected={active}
            onClick={() => onChange(item.value)}
            className={cn(
              'px-3 py-1.5 rounded-[var(--radius-pill)] text-xs font-medium transition-colors',
              active ? 'text-[var(--accent-foreground)]' : 'text-foundation-muted-fg hover:text-foundation-fg'
            )}
            style={active ? { backgroundColor: 'var(--accent)' } : undefined}
          >
            {item.label}
          </button>
        );
      })}
    </div>
  );
}
