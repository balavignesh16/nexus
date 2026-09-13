import { type ReactNode, useState } from 'react';
import { Link, useLocation, Outlet } from 'react-router-dom';
import { Search, Server, Zap, Settings, LayoutDashboard, Box, Signal, FileJson, TrendingUp, Cpu, Bell, Workflow } from 'lucide-react';
import { DemoScenarioSelector } from './ui/DemoScenarioSelector';
import { useConnectionStatus } from '../services/ConnectionManager';
import { CommandPalette } from './ui/CommandPalette';
import { NotificationCenter } from './ui/NotificationCenter';

interface AppShellProps {
  children?: ReactNode;
}

// Grouped by the platform's actual data-flow layers (Physical -> Observation
// -> Understanding -> Decision), not a flat alphabetical menu - the sidebar
// itself should communicate what NEXUS is.
const NAV_GROUPS = [
  {
    label: 'Observe',
    items: [
      { label: 'Overview', icon: LayoutDashboard, path: '/' },
      { label: 'Infrastructure', icon: Server, path: '/explorer' },
      { label: 'Digital Twin', icon: Box, path: '/twins' },
      { label: 'Telemetry', icon: Signal, path: '/telemetry' },
    ],
  },
  {
    label: 'Understand',
    items: [
      { label: 'Events', icon: Zap, path: '/events' },
      { label: 'Pipeline', icon: Workflow, path: '/pipeline' },
      { label: 'Analytics', icon: TrendingUp, path: '/analytics' },
      { label: 'AI Intelligence', icon: Cpu, path: '/ai' },
    ],
  },
  {
    label: 'Decide & Act',
    items: [
      { label: 'Rules', icon: FileJson, path: '/rules' },
      { label: 'Actions', icon: Zap, path: '/actions' },
    ],
  },
  {
    label: 'System',
    items: [
      { label: 'Settings', icon: Settings, path: '/settings' },
    ],
  },
];

export function AppShell({ children }: AppShellProps = {}) {
  const location = useLocation();
  const connection = useConnectionStatus();
  const [cmdOpen, setCmdOpen] = useState(false);
  const [notifOpen, setNotifOpen] = useState(false);

  const getIsActive = (path: string) => {
    if (path === '/') return location.pathname === '/';
    return location.pathname.startsWith(path);
  };

  return (
    <div className="flex flex-col h-screen w-screen overflow-hidden bg-background text-foreground">
      
      {/* GLOBAL SHELL: TOP NAVBAR */}
      <header className="h-[60px] flex-shrink-0 bg-sunken border-b border-surface-border flex items-center justify-between px-4 z-30 relative">

        {/* Left: Logo & Wordmark - flat mark, no decorative gradient */}
        <div className="flex items-center gap-4">
          <div className="flex items-center gap-2.5">
            <div className="w-8 h-8 rounded-md bg-primary/15 border border-primary/30 flex items-center justify-center">
              <Box size={16} className="text-primary" />
            </div>
            <span className="font-bold text-[15px] tracking-[0.08em] text-white">NEXUS</span>
          </div>
          <div className="h-5 w-px bg-surface-border"></div>
          <span className="text-xs font-medium text-muted-foreground uppercase tracking-wider">Physical Intelligence Platform</span>
        </div>

        {/* Center: Global Search */}
        <div className="flex-1 max-w-xl px-8">
          <div
            className="relative group cursor-text"
            onClick={() => setCmdOpen(true)}
          >
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground group-hover:text-primary transition-colors" />
            <div className="w-full bg-surface border border-surface-border rounded-md py-1.5 pl-10 pr-4 text-sm text-muted-foreground group-hover:border-primary/40 transition-colors flex justify-between items-center">
              <span>Search devices, events, rules...</span>
              <span className="flex items-center gap-1">
                <kbd className="hidden sm:inline-block px-1.5 py-0.5 text-[10px] font-medium bg-raised text-muted-foreground rounded border border-surface-border">Ctrl</kbd>
                <kbd className="hidden sm:inline-block px-1.5 py-0.5 text-[10px] font-medium bg-raised text-muted-foreground rounded border border-surface-border">K</kbd>
              </span>
            </div>
          </div>
        </div>
        {/* Right: Actions */}
        <div className="flex items-center gap-4 z-50">
          <DemoScenarioSelector />

          <div className="flex items-center gap-2 group relative">
            <span className={`w-2 h-2 rounded-full ${
              connection.realtimeStatus === 'connected' ? 'bg-success' :
              connection.realtimeStatus === 'demo' ? 'bg-[var(--data-simulated)]' :
              connection.realtimeStatus === 'recovering' ? 'bg-warning' :
              connection.realtimeStatus === 'connecting' ? 'bg-info' : 'bg-destructive'
            }`}></span>
            <span className="text-xs font-mono font-medium tracking-wider text-muted-foreground hidden md:block uppercase">
              {connection.realtimeStatus === 'connected' ? 'LIVE' : connection.realtimeStatus}
            </span>

            {/* Hover tooltip for connection stats */}
            <div className="absolute top-full mt-2 right-0 w-64 bg-surface border border-surface-border rounded-lg p-4 shadow-xl opacity-0 group-hover:opacity-100 pointer-events-none transition-opacity z-50 flex flex-col gap-3">
              <h4 className="text-xs font-bold text-foreground mb-1 uppercase tracking-wider border-b border-surface-border pb-2">Connection Stats</h4>
              <div className="flex justify-between text-xs">
                <span className="text-muted-foreground">Messages/sec</span>
                <span className="font-mono text-white">{connection.messagesPerSec}</span>
              </div>
              <div className="flex justify-between text-xs">
                <span className="text-muted-foreground">Latency</span>
                <span className="font-mono text-white">{connection.latencyMs}ms</span>
              </div>
              <div className="flex justify-between text-xs">
                <span className="text-muted-foreground">Dropped</span>
                <span className="font-mono text-white">{connection.droppedMessages}</span>
              </div>
              <div className="flex justify-between text-xs">
                <span className="text-muted-foreground">Reconnects</span>
                <span className="font-mono text-white">{connection.reconnectAttempts}</span>
              </div>
              <div className="flex justify-between text-xs">
                <span className="text-muted-foreground">Clock Offset</span>
                <span className="font-mono text-white">{connection.clientOffset}ms</span>
              </div>
            </div>
          </div>
          
          <div className="h-4 w-px bg-border/50"></div>

          <button
            onClick={() => setNotifOpen(true)}
            aria-label="Notifications"
            className="relative p-2 hover:bg-surface rounded-lg text-muted-foreground hover:text-foreground transition-colors group"
          >
            <Bell size={18} className="group-hover:text-primary transition-colors" />
            <span className="absolute top-1.5 right-1.5 w-2 h-2 bg-primary rounded-full border border-sunken"></span>
          </button>

          <button
            onClick={() => setCmdOpen(true)}
            aria-label="Open command palette"
            className="p-2 hover:bg-surface rounded-lg text-muted-foreground hover:text-foreground transition-colors group flex items-center gap-2"
          >
            <Search size={18} className="group-hover:text-primary transition-colors" />
            <kbd className="hidden sm:inline-flex h-5 items-center gap-1 rounded border border-surface-border bg-muted/50 px-1.5 font-mono text-[10px] font-medium text-muted-foreground opacity-100">
              <span className="text-xs">⌘</span>K
            </kbd>
          </button>
        </div>
      </header>

      {/* Main Content Area */}
      <div className="flex flex-1 overflow-hidden">
        {/* Sidebar Nav - grouped by data-flow layer (Observe / Understand /
            Decide & Act), not a flat alphabetical list. */}
        <aside className="w-[68px] flex-col border-r border-surface-border bg-sunken hidden md:flex transition-all duration-300 z-10 shrink-0 hover:w-[212px] group/sidebar">

          <nav className="flex-1 flex flex-col gap-4 p-3 mt-4 overflow-hidden">
            {NAV_GROUPS.map((group) => (
              <div key={group.label} className="flex flex-col gap-1">
                <span className="px-3 text-[9px] font-bold uppercase tracking-[0.12em] text-muted-foreground/50 opacity-0 w-0 group-hover/sidebar:w-auto group-hover/sidebar:opacity-100 transition-all whitespace-nowrap overflow-hidden duration-200 h-3">
                  {group.label}
                </span>
                {group.items.map((item) => {
                  const active = getIsActive(item.path);
                  return (
                    <Link
                      key={item.path}
                      to={item.path}
                      aria-current={active ? 'page' : undefined}
                      className={`
                        flex items-center gap-4 rounded-lg p-3 transition-colors text-sm relative
                        ${active ? 'bg-primary/10 text-primary' : 'text-muted-foreground hover:bg-surface hover:text-foreground'}
                      `}
                    >
                      {active && <div className="absolute left-0 top-1 bottom-1 w-1 bg-primary rounded-r-full" />}
                      <div className="shrink-0">
                        <item.icon size={20} className={active ? 'text-primary' : 'opacity-70'} />
                      </div>
                      <span className="opacity-0 w-0 group-hover/sidebar:w-auto group-hover/sidebar:opacity-100 transition-all whitespace-nowrap overflow-hidden duration-200">
                        {item.label}
                      </span>
                    </Link>
                  );
                })}
              </div>
            ))}
          </nav>

          {/* Bottom Status Modules */}
          <div className="shrink-0 flex flex-col">
            <div className="px-3 py-4 border-t border-surface-border flex flex-col gap-3 text-xs overflow-hidden">
              {[
                { label: 'Realtime', status: connection.realtimeStatus },
                { label: 'Backend', status: connection.backend },
                { label: 'MQTT Broker', status: connection.mqtt },
                { label: 'Database', status: connection.database },
              ].map((service, i) => {
                const isOnline = service.status === 'Online' || service.status === 'connected' || service.status === 'demo';
                return (
                  <div key={i} className="flex items-center justify-between group cursor-default">
                    <div className="flex items-center gap-2 text-muted-foreground">
                      <div className={`w-1.5 h-1.5 rounded-full ${isOnline ? 'bg-success' : 'bg-destructive'}`}></div>
                      <span className="opacity-0 group-hover/sidebar:opacity-100 transition-opacity whitespace-nowrap overflow-hidden absolute left-[68px] group-hover/sidebar:relative group-hover/sidebar:left-0 duration-200">
                        {service.label}
                      </span>
                    </div>
                    <span className={`${isOnline ? 'text-success' : 'text-destructive'} font-medium text-[11px] opacity-0 group-hover/sidebar:opacity-100 transition-opacity`}>{service.status}</span>
                  </div>
                );
              })}

              <div className="flex justify-between items-center mt-2 pt-2 border-t border-surface-border opacity-0 group-hover/sidebar:opacity-100 transition-opacity">
                <span className="text-[10px] text-muted-foreground">Latency</span>
                <span className="text-[10px] font-mono font-medium text-white">{connection.latencyMs}ms</span>
              </div>
            </div>

            {/* Version Footer */}
            <div className="px-5 py-4 border-t border-surface-border text-[11px] text-muted-foreground/60 flex items-center justify-between opacity-0 group-hover/sidebar:opacity-100 transition-opacity">
              <span>v1.0.0</span>
              <span>Build 250803.1</span>
            </div>
          </div>
        </aside>

        {/* PANE 2/3: DYNAMIC MAIN CONTENT */}
        <div className="flex-1 flex overflow-hidden bg-canvas relative z-0">
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
