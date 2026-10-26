import { type ReactNode, useCallback, useState } from 'react';
import { Link, Outlet } from 'react-router-dom';
import { Search, Server, Zap, Settings, LayoutDashboard, Box, Signal, FileJson, TrendingUp, Cpu, Bell, Workflow, Boxes, Menu } from 'lucide-react';
import { NavigationSidebar } from './NavigationSidebar';
import { DemoScenarioSelector } from './ui/DemoScenarioSelector';
import { useConnectionStatus } from '../services/ConnectionManager';
import { CommandPalette } from './ui/CommandPalette';
import { NotificationCenter } from './ui/NotificationCenter';
import { GlassPanel } from './foundation/GlassPanel';
import { IconButton } from './foundation/IconButton';

interface AppShellProps {
  children?: ReactNode;
}

// Grouped by the platform's actual data-flow layers (Physical -> Observation
// -> Understanding -> Decision), not a flat alphabetical menu - the sidebar
// itself should communicate what NEXUS is. Phase 6 adds assistant and analysis
// destinations while preserving the earlier observation and rule workflows.
const NAV_GROUPS = [
  {
    label: 'Workspace',
    items: [
      { label: 'Overview', icon: LayoutDashboard, path: '/' },
      { label: 'Infrastructure', icon: Server, path: '/explorer' },
      { label: 'Manage Infrastructure', icon: Boxes, path: '/infrastructure' },
      { label: 'Three ESP Simulation', icon: Cpu, path: '/simulation' },
      { label: 'Digital Twin', icon: Box, path: '/twins' },
      { label: 'Telemetry', icon: Signal, path: '/telemetry' },
    ],
  },
  {
    label: 'Intelligence',
    items: [
      { label: 'Events', icon: Zap, path: '/events' },
      { label: 'Pipeline', icon: Workflow, path: '/pipeline' },
      { label: 'Analytics', icon: TrendingUp, path: '/analytics' },
      { label: 'NEXUS Assistant', icon: Cpu, path: '/assistant' },
      { label: 'AI Insights', icon: Cpu, path: '/intelligence' },
      { label: 'Trends', icon: TrendingUp, path: '/trends' },
      { label: 'Energy Intelligence', icon: Zap, path: '/energy' },
    ],
  },
  {
    label: 'Decide & Act',
    items: [
      { label: 'Rules', icon: FileJson, path: '/rules' },
      { label: 'Actions', icon: Zap, path: '/actions' },
      { label: 'AI Rule Drafts', icon: FileJson, path: '/ai/drafts' },
    ],
  },
  {
    label: 'Framework',
    items: [
      { label: 'Capability Definitions', icon: Boxes, path: '/framework/capabilities' },
      { label: 'Device Templates', icon: Box, path: '/framework/templates' },
      { label: 'Device Onboarding', icon: Server, path: '/framework/onboarding' },
      { label: 'Generic Rules', icon: FileJson, path: '/framework/rules' },
    ],
  },
  {
    label: 'System',
    items: [
      { label: 'Settings', icon: Settings, path: '/settings' },
    ],
  },
];

const CONNECTION_TONE: Record<string, string> = {
  connected: 'var(--success)',
  demo: 'var(--data-simulated)',
  recovering: 'var(--warning)',
  connecting: 'var(--info)',
};

export function AppShell({ children }: AppShellProps = {}) {
  const connection = useConnectionStatus();
  const [cmdOpen, setCmdOpen] = useState(false);
  const [notifOpen, setNotifOpen] = useState(false);
  const [mobileOpen,setMobileOpen]=useState(false);
  const closeMobileNav=useCallback(()=>setMobileOpen(false),[]);

  const connectionColor = CONNECTION_TONE[connection.realtimeStatus] ?? 'var(--danger)';

  return (
    <div
      className="flex flex-col h-screen w-screen overflow-hidden"
      style={{ backgroundColor: 'var(--foundation-bg)' }}
    >
      {/* GLOBAL SHELL: TOP BAR - refined glass header, floating above the
          (still dark, unredesigned) content area. */}
      <header className="h-[68px] flex-shrink-0 flex items-center gap-4 px-4 sm:px-6 z-30 relative">
        <GlassPanel
          level="float"
          radius="pill"
          className="flex-1 h-12 flex items-center justify-between gap-4 px-2 sm:px-3"
        >
          {/* Left: Wordmark */}
          <div className="flex items-center gap-2.5 shrink-0 pl-2">
            <button type="button" aria-label="Open navigation" aria-expanded={mobileOpen} className="md:hidden text-foundation-muted-fg p-1" onClick={()=>setMobileOpen(true)}><Menu size={20}/></button>
            <div
              className="w-8 h-8 rounded-full flex items-center justify-center"
              style={{ backgroundColor: 'color-mix(in srgb, var(--accent) 16%, transparent)', color: 'var(--accent)' }}
            >
              <Box size={16} />
            </div>
            <span className="font-bold text-[14px] tracking-[0.08em] text-foundation-fg hidden sm:inline">NEXUS</span>
            <span className="text-[10px] font-medium text-foundation-muted-fg uppercase tracking-wider hidden lg:inline">
              Physical Intelligence Platform
            </span>
          </div>

          {/* Center: Global Search - opens the real CommandPalette */}
          <button
            onClick={() => setCmdOpen(true)}
            aria-label="Open search"
            className="flex-1 max-w-md hidden sm:flex items-center gap-2 h-9 px-3.5 rounded-[var(--radius-pill)] text-left transition-colors"
            style={{ backgroundColor: 'var(--glass-bg)', border: '1px solid var(--glass-border)' }}
          >
            <Search size={14} className="text-foundation-muted-fg shrink-0" />
            <span className="text-xs text-foundation-muted-fg flex-1 truncate">Search devices, events, rules...</span>
            <kbd className="hidden md:inline-flex items-center px-1.5 py-0.5 text-[10px] font-medium text-foundation-muted-fg rounded border border-foundation-border">
              Ctrl K
            </kbd>
          </button>

          {/* Right: real connection state + real overlays */}
          <div className="flex items-center gap-1.5 sm:gap-2 shrink-0">
            <Link to="/assistant" className="text-xs font-semibold text-[var(--accent)] px-3 py-2 rounded-full border border-foundation-border">NEXUS Assistant</Link>
            <DemoScenarioSelector />

            <div
              className="hidden md:flex items-center gap-1.5 px-3 h-8 rounded-[var(--radius-pill)] text-[11px] font-mono font-medium uppercase tracking-wider"
              style={{ backgroundColor: 'color-mix(in srgb, ' + connectionColor + ' 12%, transparent)', color: connectionColor }}
              title={`${connection.messagesPerSec} msg/s · ${connection.latencyMs}ms latency`}
            >
              <span className="w-1.5 h-1.5 rounded-full" style={{ backgroundColor: connectionColor }} />
              {connection.realtimeStatus === 'connected' ? 'Live' : connection.realtimeStatus}
            </div>

            <IconButton variant="ghost" aria-label="Open search" className="sm:hidden" onClick={() => setCmdOpen(true)}>
              <Search size={16} />
            </IconButton>

            <IconButton variant="ghost" aria-label="Notifications" onClick={() => setNotifOpen(true)}>
              <Bell size={16} />
            </IconButton>
          </div>
        </GlassPanel>
      </header>

      {/* Main Content Area */}
      <div className="flex flex-1 overflow-hidden px-4 sm:px-6 pb-4 sm:pb-6 gap-4 sm:gap-6">
        <NavigationSidebar groups={NAV_GROUPS} connection={connection} mobileOpen={mobileOpen} onMobileClose={closeMobileNav}/>

        {/* PANE 2/3: DYNAMIC MAIN CONTENT
            Deliberately kept on the legacy dark bg-canvas: pages not yet
            redesigned still use dark-theme text/surface colors, so the
            shared content slot itself must stay dark so those pages remain
            legible. [&>*]:w-full [&>*]:h-full forces the one rendered page
            root to exactly fill this slot regardless of how that page
            authored its own sizing - some pages size themselves via
            flex-1/min-h-0 (which only works when their parent is itself
            display:flex), others via h-full (percentage height, which
            only works when their parent has a definite height). Rather
            than depend on either assumption holding for every page, this
            direct-child rule makes both true unconditionally: a page
            relying on flex-1 alone (e.g. ExplorerPage) no longer collapses
            to its own content height, and a page relying on h-full is
            pinned to 100% with certainty. This replaces an earlier,
            narrower fix that only solved the width case and inadvertently
            left the height case unresolved for pages using flex-1 without
            h-full. */}
        <div className="flex-1 overflow-hidden bg-canvas relative z-0 rounded-[var(--radius-panel-lg)] [&>*]:w-full [&>*]:h-full">
          {children || <Outlet />}
        </div>
      </div>

      {/* Global Command Palette */}
      <CommandPalette open={cmdOpen} setOpen={setCmdOpen} />

      {/* Global Notification Center */}
      <NotificationCenter open={notifOpen} setOpen={setNotifOpen} />
    </div>
  );
}
