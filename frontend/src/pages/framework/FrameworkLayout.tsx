import { Link, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';
import { GlassPanel } from '../../components/foundation/GlassPanel';

const links = [
  ['Capabilities', '/framework/capabilities'],
  ['Templates', '/framework/templates'],
  ['Onboarding', '/framework/onboarding'],
  ['Generic Rules', '/framework/rules'],
] as const;

export function FrameworkLayout({ title, children }: { title: string; children: ReactNode }) {
  const { pathname } = useLocation();
  return <div className="h-full overflow-y-auto p-4 sm:p-6 lg:p-8 text-foundation-fg" style={{ backgroundColor: 'var(--foundation-bg)' }}>
    <div className="max-w-6xl mx-auto space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div><p className="text-xs uppercase tracking-widest text-foundation-muted-fg">Framework</p>
          <h1 className="text-2xl font-semibold">{title}</h1></div>
        <nav aria-label="Framework" className="flex flex-wrap gap-2">
          {links.map(([label, path]) => <Link key={path} to={path} aria-current={pathname.startsWith(path) ? 'page' : undefined}
            className="rounded-full px-3 py-2 text-xs border border-foundation-border"
            style={pathname.startsWith(path) ? { backgroundColor: 'var(--accent)', color: 'var(--accent-foreground)' } : undefined}>{label}</Link>)}
        </nav>
      </div>
      <GlassPanel level="glass" radius="panel-lg" className="p-5 sm:p-6">{children}</GlassPanel>
    </div>
  </div>;
}
