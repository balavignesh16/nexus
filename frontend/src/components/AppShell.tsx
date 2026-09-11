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

export function AppShell({ children }: AppShellProps = {}) {
  const location = useLocation();
  const connection = useConnectionStatus();
  const [cmdOpen, setCmdOpen] = useState(false);
  const [notifOpen, setNotifOpen] = useState(false);
  // Handle Ctrl+K is handled inside CommandPalette now, but we can also handle the global click on the search bar
  const globalNav = [
    { label: 'Dashboard', icon: LayoutDashboard, path: '/' },
    { label: 'Infrastructure', icon: Server, path: '/explorer' },
    { label: 'Digital Twins', icon: Box, path: '/twins' },
    { label: 'Telemetry', icon: Signal, path: '/telemetry' },
    { label: 'Events', icon: Zap, path: '/events' },
    { label: 'Rules', icon: FileJson, path: '/rules' },
    { label: 'Actions', icon: Zap, path: '/actions' },
    { label: 'Pipeline', icon: Workflow, path: '/pipeline' },
    { label: 'Analytics', icon: TrendingUp, path: '/analytics' },
    { label: 'AI Insights', icon: Cpu, path: '/ai' },
    { label: 'Settings', icon: Settings, path: '/settings' },
  ];

  const getIsActive = (path: string) => {
    if (path === '/') return location.pathname === '/';
    return location.pathname.startsWith(path);
  };

  return (
    <div className="flex flex-col h-screen w-screen overflow-hidden bg-background text-foreground">
      
      {/* GLOBAL SHELL: TOP NAVBAR */}
      <header className="h-[64px] flex-shrink-0 bg-[#0a0a0f] border-b border-[#1f1f28] flex items-center justify-between px-4 z-30 relative">
        
        {/* Left: Logo & Wordmark */}
        <div className="flex items-center gap-4">
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 rounded-md bg-gradient-to-br from-blue-500 to-cyan-400 flex items-center justify-center shadow-lg">
              <Box size={18} className="text-[#0a0a0f]" />
            </div>
            <span className="font-bold text-lg tracking-wide text-white">NEXUS</span>
          </div>
          <div className="h-6 w-px bg-[#1f1f28]"></div>
          <span className="text-sm font-normal text-muted-foreground">Operations Center</span>
        </div>

        {/* Center: Global Search */}
        <div className="flex-1 max-w-xl px-8">
          <div 
            className="relative group cursor-text"
            onClick={() => setCmdOpen(true)}
          >
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground group-hover:text-primary transition-colors" />
            <div className="w-full bg-[#13131a] border border-[#1f1f28] rounded-md py-1.5 pl-10 pr-4 text-sm text-muted-foreground group-hover:border-[#2d2d3d] transition-colors flex justify-between items-center">
              <span>Search twins, events, rules...</span>
              <span className="flex items-center gap-1">
                <kbd className="hidden sm:inline-block px-1.5 py-0.5 text-[10px] font-medium bg-[#1f1f28] text-muted-foreground rounded border border-[#2d2d3d]">Ctrl</kbd>
                <kbd className="hidden sm:inline-block px-1.5 py-0.5 text-[10px] font-medium bg-[#1f1f28] text-muted-foreground rounded border border-[#2d2d3d]">K</kbd>
              </span>
            </div>
          </div>
        </div>
        {/* Right: Actions */}
        <div className="flex items-center gap-4 z-50">
          <DemoScenarioSelector />
          
          <div className="flex items-center gap-2 group relative">
            <span className={`w-2 h-2 rounded-full animate-pulse shadow-[0_0_8px_rgba(34,197,94,0.5)] ${
              connection.realtimeStatus === 'connected' ? 'bg-success' : 
              connection.realtimeStatus === 'demo' ? 'bg-blue-500' :
              connection.realtimeStatus === 'recovering' ? 'bg-warning' :
              connection.realtimeStatus === 'connecting' ? 'bg-purple-500' : 'bg-destructive'
            }`}></span>
            <span className="text-xs font-mono font-medium tracking-wider text-muted-foreground hidden md:block uppercase">
              {connection.realtimeStatus === 'connected' ? 'LIVE' : connection.realtimeStatus}
            </span>

            {/* Hover tooltip for connection stats */}
            <div className="absolute top-full mt-2 right-0 w-64 bg-[#0a0a0f] border border-[#1f1f28] rounded-xl p-4 shadow-xl opacity-0 group-hover:opacity-100 pointer-events-none transition-opacity z-50 flex flex-col gap-3">
              <h4 className="text-xs font-bold text-foreground mb-1 uppercase tracking-wider border-b border-[#1f1f28] pb-2">Connection Stats</h4>
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
            className="relative p-2 hover:bg-[#13131a] rounded-lg text-muted-foreground hover:text-foreground transition-colors group"
          >
            <Bell size={18} className="group-hover:text-blue-400 transition-colors" />
            <span className="absolute top-1.5 right-1.5 w-2 h-2 bg-blue-500 rounded-full border border-[#0a0a0f]"></span>
          </button>
          
          <button 
            onClick={() => setCmdOpen(true)}
            className="p-2 hover:bg-[#13131a] rounded-lg text-muted-foreground hover:text-foreground transition-colors group flex items-center gap-2"
          >
            <Search size={18} className="group-hover:text-blue-400 transition-colors" />
            <kbd className="hidden sm:inline-flex h-5 items-center gap-1 rounded border border-[#1f1f28] bg-muted/50 px-1.5 font-mono text-[10px] font-medium text-muted-foreground opacity-100">
              <span className="text-xs">⌘</span>K
            </kbd>
          </button>
        </div>
      </header>

      {/* Main Content Area */}
      <div className="flex flex-1 overflow-hidden">
        {/* Sidebar Nav */}
        <aside className="w-[68px] flex-col border-r border-[#1f1f28] bg-[#0a0a0f] hidden md:flex transition-all duration-300 z-10 shrink-0 hover:w-[200px] group/sidebar">
          
          <nav className="flex-1 flex flex-col gap-2 p-3 mt-4 overflow-hidden">
            {globalNav.map((item) => {
              const active = getIsActive(item.path);
              return (
                <Link
                  key={item.path}
                  to={item.path}
                  className={`
                    flex items-center gap-4 rounded-lg p-3 transition-colors text-sm relative
                    ${active ? 'bg-blue-500/10 text-blue-400' : 'text-muted-foreground hover:bg-[#13131a] hover:text-foreground'}
                  `}
                >
                  {active && <div className="absolute left-0 top-1 bottom-1 w-1 bg-blue-500 rounded-r-full" />}
                  <div className="shrink-0">
                    <item.icon size={20} className={active ? 'text-blue-400' : 'opacity-70'} />
                  </div>
                  <span className="opacity-0 w-0 group-hover/sidebar:w-auto group-hover/sidebar:opacity-100 transition-all whitespace-nowrap overflow-hidden duration-200">
                    {item.label}
                  </span>
                </Link>
              );
            })}
          </nav>

          {/* Bottom Status Modules */}
          <div className="shrink-0 flex flex-col">
            <div className="px-3 py-4 border-t border-[#1f1f28] flex flex-col gap-3 text-xs overflow-hidden">
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
                      <div className={`w-1.5 h-1.5 rounded-full ${isOnline ? 'bg-[#22c55e] shadow-[0_0_5px_rgba(34,197,94,0.5)]' : 'bg-red-500'}`}></div>
                      <span className="opacity-0 group-hover/sidebar:opacity-100 transition-opacity whitespace-nowrap overflow-hidden absolute left-[68px] group-hover/sidebar:relative group-hover/sidebar:left-0 duration-200">
                        {service.label}
                      </span>
                    </div>
                    <span className={`${isOnline ? 'text-[#22c55e]' : 'text-red-500'} font-medium text-[11px] opacity-0 group-hover/sidebar:opacity-100 transition-opacity`}>{service.status}</span>
                  </div>
                );
              })}
              
              <div className="flex justify-between items-center mt-2 pt-2 border-t border-[#1f1f28] opacity-0 group-hover/sidebar:opacity-100 transition-opacity">
                <span className="text-[10px] text-muted-foreground">Latency</span>
                <span className="text-[10px] font-mono font-medium text-white">{connection.latencyMs}ms</span>
              </div>
            </div>
            
            {/* Version Footer */}
            <div className="px-5 py-4 border-t border-[#1f1f28] text-[11px] text-muted-foreground/60 flex items-center justify-between opacity-0 group-hover/sidebar:opacity-100 transition-opacity">
              <span>v1.0.0</span>
              <span>Build 250803.1</span>
            </div>
          </div>
        </aside>
  
        {/* PANE 2/3: DYNAMIC MAIN CONTENT */}
        <div className="flex-1 flex overflow-hidden bg-[#060b14] relative z-0">
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
