import { useState, createContext, useContext } from 'react';
import { useDevice } from '../../../hooks/queries';
import { Activity, Server, Code, Signal, Clock, Box, Zap, Hash, ChevronDown, RefreshCw, MoreHorizontal, Settings } from 'lucide-react';
import { DeviceStatusBadge } from '../../DeviceStatusBadge';
import { DeviceConnectivityBadge, type DeviceConnectivity } from '../../DeviceConnectivityBadge';
import { useDeviceConnectivityStream } from '../../../realtime/streams/useDeviceConnectivityStream';
import { DeviceCapabilitiesList } from '../../DeviceCapabilitiesList';
import { DeviceCommandControls } from '../../DeviceCommandControls';
import { useDeviceLocationPath } from '../../../hooks/useDeviceLocationPath';
import { useDeviceTwinState } from '../../../hooks/useDeviceTwinState';
import { formatAge } from '../../../utils/formatAge';
import { DataSourceBadge } from '../../ui/DataSourceBadge';

export const DeveloperModeContext = createContext<boolean>(false);
export const useDeveloperMode = () => useContext(DeveloperModeContext);

// Import Tab Components
import { OverviewTab } from './tabs/OverviewTab';
import { TelemetryTab } from './tabs/TelemetryTab';
import { DigitalTwinTab } from './tabs/DigitalTwinTab';
import { EventsTab } from './tabs/EventsTab';
import { RulesTab } from './tabs/RulesTab';
import { ActionsTab } from './tabs/ActionsTab';
import { DiagnosticsTab } from './tabs/DiagnosticsTab';
import { RawJsonTab } from './tabs/RawJsonTab';

interface DeviceWorkspaceProps {
  deviceId: string;
}

export type TabId = 'overview' | 'telemetry' | 'twin' | 'events' | 'rules' | 'actions' | 'diagnostics' | 'json';

export function DeviceWorkspace({ deviceId }: DeviceWorkspaceProps) {
  const { data: device, isLoading, error } = useDevice(deviceId);
  const [activeTab, setActiveTab] = useState<TabId>('overview');
  const [devMode, setDevMode] = useState(false);
  // Tier 5B: physical MQTT connectivity, deliberately separate from
  // device.status (lifecycle). 'UNKNOWN' until the first realtime
  // connectivity event arrives for this device in the current session.
  const [connectivity, setConnectivity] = useState<DeviceConnectivity>('UNKNOWN');
  useDeviceConnectivityStream(deviceId, (payload) => setConnectivity(payload.status));

  const locationPath = useDeviceLocationPath(device?.spaceId);
  const twinState = useDeviceTwinState(deviceId);

  if (isLoading) {
    return (
      <div className="flex flex-col h-full bg-[#0a0a0f] p-4 space-y-4">
        <div className="shrink-0 flex items-center justify-between pb-1">
          <div className="h-4 w-64 bg-[#1f1f28] rounded animate-pulse" />
        </div>
        <div className="shrink-0 bg-[#12121a] border border-[#1f1f28] rounded-xl h-[106px] animate-pulse shadow-sm" />
        <div className="shrink-0 border-b border-[#1f1f28] h-10 flex gap-6 px-2">
           <div className="h-full w-20 bg-[#1f1f28] rounded-t-md animate-pulse" />
           <div className="h-full w-24 bg-[#1f1f28] rounded-t-md animate-pulse opacity-70" />
           <div className="h-full w-16 bg-[#1f1f28] rounded-t-md animate-pulse opacity-50" />
        </div>
        <div className="flex-1 bg-[#12121a]/50 border border-[#1f1f28] rounded-xl animate-pulse mt-2" />
      </div>
    );
  }
  
  if (error || !device) {
    return <div className="p-8 text-destructive">Error loading device details.</div>;
  }

  const tabs: { id: TabId; label: string; icon: any }[] = [
    { id: 'overview', label: 'Overview', icon: Activity },
    { id: 'telemetry', label: 'Telemetry', icon: Signal },
    { id: 'twin', label: 'Twin', icon: Box },
    { id: 'events', label: 'Events', icon: Clock },
    { id: 'rules', label: 'Rules', icon: Server },
    { id: 'actions', label: 'Actions', icon: Zap },
    { id: 'diagnostics', label: 'Diagnostics', icon: Hash },
    { id: 'json', label: 'Raw JSON', icon: Code },
  ];

  return (
    <div className="flex flex-col h-full bg-[#0a0a0f] overflow-hidden animate-in fade-in relative p-4 space-y-4">
      
      {/* TOP HEADER: Breadcrumbs & Actions */}
      <div className="shrink-0 flex items-center justify-between pb-1">
        <div className="flex items-center gap-2 text-xs font-medium text-muted-foreground/80">
          {locationPath.isLoading ? (
            <span className="text-muted-foreground/50">Resolving location…</span>
          ) : locationPath.data ? (
            <>
              <span className="hover:text-foreground cursor-pointer transition-colors">{locationPath.data.site}</span>
              <span className="text-muted-foreground/40">{'>'}</span>
              <span className="hover:text-foreground cursor-pointer transition-colors">{locationPath.data.building}</span>
              <span className="text-muted-foreground/40">{'>'}</span>
              <span className="hover:text-foreground cursor-pointer transition-colors">{locationPath.data.space}</span>
              <span className="text-muted-foreground/40">{'>'}</span>
            </>
          ) : (
            <>
              <span className="text-muted-foreground/50" data-testid="breadcrumb-unavailable">Location unavailable</span>
              <span className="text-muted-foreground/40">{'>'}</span>
            </>
          )}
          <div className="flex items-center gap-1.5 text-foreground">
            <span className="font-semibold text-white flex items-center gap-1.5">
              <Settings size={14} className="text-muted-foreground" />
              {device.name}
            </span>
          </div>
        </div>
        
        <div className="flex items-center gap-2">
          <button 
            onClick={() => setDevMode(!devMode)}
            className={`flex items-center gap-2 px-3 py-1.5 rounded-md text-[11px] font-medium transition-colors border ${devMode ? 'bg-[#22c55e]/10 border-[#22c55e]/30 text-[#22c55e]' : 'bg-[#12121a] hover:bg-[#1a1a24] border-[#1f1f28] text-foreground'}`}
          >
            {devMode ? 'Dev Mode: ON' : 'Dev Mode: OFF'}
          </button>
          <button className="flex items-center gap-2 bg-[#12121a] hover:bg-[#1a1a24] border border-[#1f1f28] px-3 py-1.5 rounded-md text-[11px] font-medium transition-colors text-foreground">
            Quick Actions
            <ChevronDown size={12} className="text-muted-foreground" />
          </button>
          <button className="p-1.5 bg-[#12121a] hover:bg-[#1a1a24] border border-[#1f1f28] rounded-md text-muted-foreground hover:text-foreground transition-colors">
            <RefreshCw size={14} />
          </button>
          <button className="p-1.5 bg-[#12121a] hover:bg-[#1a1a24] border border-[#1f1f28] rounded-md text-muted-foreground hover:text-foreground transition-colors">
            <MoreHorizontal size={14} />
          </button>
        </div>
      </div>

      <DeveloperModeContext.Provider value={devMode}>

      {/* PANE 3 HERO HEADER CARD */}
      <div className="shrink-0 bg-[#12121a] border border-[#1f1f28] rounded-xl p-5 flex items-stretch shadow-sm">
        
        {/* Left: Device Identity */}
        <div className="flex items-center gap-4 pr-8 border-r border-[#1f1f28] shrink-0">
          <div className="w-16 h-16 rounded-xl bg-[#0a0a0f] border border-[#1f1f28] flex items-center justify-center shadow-inner relative overflow-hidden group shrink-0">
            <Box size={32} className="text-[#22c55e] group-hover:scale-110 transition-transform duration-300 drop-shadow-[0_0_8px_rgba(34,197,94,0.5)]" />
          </div>
          <div className="flex flex-col">
            <div className="flex items-center gap-3 mb-1">
              <h1 className="text-xl font-bold tracking-tight text-white">{device.name}</h1>
              <DeviceStatusBadge status={device.status} />
              <DeviceConnectivityBadge connectivity={connectivity} />
            </div>
            
            <div className="flex flex-col text-xs font-mono text-muted-foreground leading-[18px]">
              <span className="text-muted-foreground/70">ID: <span className="text-foreground/80">{device.id}</span></span>
              <span className="text-muted-foreground/70">Type: <span className="text-foreground/80">{device.deviceType}</span></span>
              <span className="text-muted-foreground/70">MQTT: <span className="text-foreground/80">{device.mqttTopic}</span></span>
            </div>

            <div className="mt-2">
              <span className="text-[10px] uppercase tracking-wide text-muted-foreground/60">Capabilities</span>
              <DeviceCapabilitiesList capabilities={device.capabilities ?? []} />
              <div className="mt-2">
                <DeviceCommandControls deviceId={device.id} capabilities={device.capabilities ?? []} />
              </div>
            </div>
          </div>
        </div>

        {/* Right: Health Metrics */}
        <div className="flex-1 flex items-center justify-evenly pl-4 gap-4">
          {/* Digital Twin state - real signals only (Phase 8): no
              fabricated health/quality score. Connectivity is LIVE;
              telemetry recency and field count are CALCULATED from the
              device's own real telemetry history. */}
          <div className="flex items-center justify-center pr-8 border-r border-[#1f1f28] h-full">
            <div className="flex flex-col items-center gap-1 pt-1">
              <span className="text-[10px] text-muted-foreground flex items-center gap-1.5">
                Digital Twin <DataSourceBadge classification="LIVE" />
              </span>
              <span className={`text-sm font-bold mt-1 ${
                connectivity === 'ONLINE' ? 'text-success' : connectivity === 'OFFLINE' ? 'text-destructive' : 'text-[var(--data-unavailable)]'
              }`}>
                {connectivity}
              </span>
            </div>
          </div>

          <div className="flex items-center justify-center pr-8 border-r border-[#1f1f28] h-full">
            <div className="flex flex-col items-center gap-1 pt-1">
              <span className="text-[10px] text-muted-foreground flex items-center gap-1.5">
                Last Telemetry <DataSourceBadge classification="CALCULATED" />
              </span>
              {twinState.telemetryLoading ? (
                <span className="text-sm font-bold text-muted-foreground mt-1">…</span>
              ) : twinState.lastTelemetryAt ? (
                <span className="text-sm font-bold text-white mt-1" title={new Date(twinState.lastTelemetryAt).toLocaleString()}>
                  {formatAge(twinState.lastTelemetryAt)}
                </span>
              ) : (
                <span className="text-sm font-bold text-muted-foreground mt-1">Unavailable</span>
              )}
              <span className="text-[10px] font-mono text-muted-foreground">
                {twinState.reportedFields.length > 0 ? `${twinState.reportedFields.length} field(s) reporting` : '—'}
              </span>
            </div>
          </div>

          {/* Last Seen */}
          <div className="flex items-center justify-center pr-8 border-r border-[#1f1f28] h-full">
            <div className="flex flex-col items-center gap-1 pt-1">
              <span className="text-[10px] text-muted-foreground">Last Seen</span>
              <span className="text-xl font-bold text-white">{device.lastSeen}</span>
              <span className="text-[10px] font-mono text-muted-foreground">-</span>
            </div>
          </div>

          {/* Signal Strength */}
          <div className="flex items-center justify-center h-full">
            <div className="flex flex-col items-center gap-1 pt-1">
              <span className="text-[10px] text-muted-foreground">Signal Strength</span>
              <div className="flex items-end gap-1 h-7 mt-1">
                <div className={`w-[5px] h-2 rounded-sm ${device.signalStrength !== null && device.signalStrength > 10 ? 'bg-[#22c55e]' : 'bg-[#1f1f28]'}`}></div>
                <div className={`w-[5px] h-3 rounded-sm ${device.signalStrength !== null && device.signalStrength > 30 ? 'bg-[#22c55e]' : 'bg-[#1f1f28]'}`}></div>
                <div className={`w-[5px] h-4 rounded-sm ${device.signalStrength !== null && device.signalStrength > 50 ? 'bg-[#22c55e]' : 'bg-[#1f1f28]'}`}></div>
                <div className={`w-[5px] h-6 rounded-sm ${device.signalStrength !== null && device.signalStrength > 70 ? 'bg-[#22c55e]' : 'bg-[#1f1f28]'}`}></div>
                <div className={`w-[5px] h-7 rounded-sm ${device.signalStrength !== null && device.signalStrength > 90 ? 'bg-[#22c55e]' : 'bg-[#1f1f28]'}`}></div>
              </div>
              {device.signalStrength !== null ? (
                <span className="text-[10px] text-white mt-1 font-bold">-{100 - device.signalStrength} dBm <span className="text-muted-foreground font-normal">{device.signalStrength > 60 ? 'Strong' : 'Weak'}</span></span>
              ) : (
                <span className="text-[10px] text-muted-foreground mt-1 font-bold">Unavailable</span>
              )}
            </div>
          </div>
        </div>
      </div>

      {(device as any).value !== undefined && <div className="absolute right-6 top-[138px] rounded border border-blue-400/30 bg-blue-500/10 px-3 py-1 text-xs text-blue-200 animate-in fade-in">
        Live Value: <span className="font-mono font-bold">{(device as any).value}</span>
      </div>}

      {/* TABS */}
      <div className="shrink-0 border-b border-border/50 flex gap-6 overflow-x-auto custom-scrollbar px-2">
        {tabs.map(tab => {
          const Icon = tab.icon;
          const isActive = activeTab === tab.id;
          return (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`flex items-center gap-2 pb-3 px-1 text-xs font-semibold transition-colors relative whitespace-nowrap
                ${isActive ? 'text-primary' : 'text-muted-foreground hover:text-foreground'}
              `}
            >
              <Icon size={14} className={isActive ? 'text-primary' : 'opacity-70'} />
              {tab.label}
              {isActive && (
                <div className="absolute bottom-0 left-0 right-0 h-0.5 bg-primary rounded-t-full shadow-[0_0_8px_rgba(59,130,246,0.8)]" />
              )}
            </button>
          );
        })}
      </div>

      {/* TAB CONTENT */}
      <div className="flex-1 overflow-y-auto relative bg-[#060b14]">
        {activeTab === 'overview' && <OverviewTab deviceId={deviceId} onNavigateToTab={setActiveTab} />}
        {activeTab === 'telemetry' && <TelemetryTab deviceId={deviceId} />}
        {activeTab === 'twin' && <DigitalTwinTab deviceId={deviceId} />}
        {activeTab === 'events' && <EventsTab deviceId={deviceId} />}
        {activeTab === 'rules' && <RulesTab deviceId={deviceId} />}
        {activeTab === 'actions' && <ActionsTab deviceId={deviceId} />}
        {activeTab === 'diagnostics' && <DiagnosticsTab deviceId={deviceId} />}
        {activeTab === 'json' && <RawJsonTab deviceId={deviceId} />}
      </div>
      
      </DeveloperModeContext.Provider>

    </div>
  );
}
