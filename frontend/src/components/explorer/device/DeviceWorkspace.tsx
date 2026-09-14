import { useState, createContext, useContext } from 'react';
import { useDevice } from '../../../hooks/queries';
import { ChevronRight, Box, Bug } from 'lucide-react';
import { DeviceConnectivityBadge, type DeviceConnectivity } from '../../DeviceConnectivityBadge';
import { useDeviceConnectivityStream } from '../../../realtime/streams/useDeviceConnectivityStream';
import { DeviceCapabilitiesList } from '../../DeviceCapabilitiesList';
import { DeviceCommandControls } from '../../DeviceCommandControls';
import { useDeviceLocationPath } from '../../../hooks/useDeviceLocationPath';
import { useDeviceTwinState } from '../../../hooks/useDeviceTwinState';
import { formatAge } from '../../../utils/formatAge';
import { DataSourceBadge } from '../../ui/DataSourceBadge';
import { GlassPanel } from '../../foundation/GlassPanel';
import { StatusIndicator, type StatusTone } from '../../foundation/StatusIndicator';
import { Tabs } from '../../foundation/Tabs';
import { LoadingBlock } from '../../foundation/LoadingState';
import { ErrorState } from '../../foundation/ErrorState';

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

const TAB_ITEMS: { value: TabId; label: string }[] = [
  { value: 'overview', label: 'Overview' },
  { value: 'telemetry', label: 'Telemetry' },
  { value: 'twin', label: 'Digital Twin' },
  { value: 'events', label: 'Events' },
  { value: 'rules', label: 'Rules' },
  { value: 'actions', label: 'Actions' },
  { value: 'diagnostics', label: 'Diagnostics' },
  { value: 'json', label: 'Raw JSON' },
];

const CONNECTIVITY_TONE: Record<DeviceConnectivity, StatusTone> = {
  ONLINE: 'online',
  OFFLINE: 'offline',
  UNKNOWN: 'unknown',
};

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
      <div className="flex flex-col h-full p-4 sm:p-6 gap-4">
        <LoadingBlock className="h-6 w-64" />
        <LoadingBlock className="h-[132px]" />
        <LoadingBlock className="h-10 w-full max-w-md" />
        <LoadingBlock className="flex-1" />
      </div>
    );
  }

  if (error || !device) {
    return (
      <div className="h-full flex items-center justify-center p-8">
        <ErrorState message="Error loading device details." />
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full overflow-hidden p-4 sm:p-6 gap-4" style={{ backgroundColor: 'var(--foundation-bg)' }}>

      {/* Context breadcrumb */}
      <div className="shrink-0 flex items-center justify-between gap-3">
        <div className="flex items-center gap-1.5 text-xs font-medium text-foundation-muted-fg min-w-0 overflow-x-auto whitespace-nowrap">
          {locationPath.isLoading ? (
            <span className="text-foundation-tertiary-fg">Resolving location…</span>
          ) : locationPath.data ? (
            <>
              <span>{locationPath.data.site}</span>
              <ChevronRight size={12} className="text-foundation-tertiary-fg shrink-0" />
              <span>{locationPath.data.building}</span>
              <ChevronRight size={12} className="text-foundation-tertiary-fg shrink-0" />
              <span>{locationPath.data.space}</span>
              <ChevronRight size={12} className="text-foundation-tertiary-fg shrink-0" />
            </>
          ) : (
            <>
              <span data-testid="breadcrumb-unavailable" className="text-foundation-tertiary-fg">Location unavailable</span>
              <ChevronRight size={12} className="text-foundation-tertiary-fg shrink-0" />
            </>
          )}
          <span className="text-foundation-fg font-semibold">{device.name}</span>
        </div>

        <button
          onClick={() => setDevMode(!devMode)}
          aria-pressed={devMode}
          className="shrink-0 flex items-center gap-1.5 px-2.5 py-1 rounded-[var(--radius-pill)] text-[10px] font-semibold uppercase tracking-wider transition-colors"
          style={devMode
            ? { backgroundColor: 'color-mix(in srgb, var(--success) 14%, transparent)', color: 'var(--success)' }
            : { backgroundColor: 'var(--glass-bg)', border: '1px solid var(--glass-border)', color: 'var(--foundation-muted-fg)' }}
        >
          <Bug size={11} /> Dev Mode {devMode ? 'On' : 'Off'}
        </button>
      </div>

      <DeveloperModeContext.Provider value={devMode}>

        {/* Device Hero - only real information */}
        <GlassPanel level="glass" radius="panel-lg" className="shrink-0 p-5 flex flex-col lg:flex-row gap-6">
          {/* Identity */}
          <div className="flex items-start gap-4 lg:pr-6 lg:border-r border-foundation-border shrink-0 lg:max-w-[340px]">
            <div
              className="w-14 h-14 rounded-[var(--radius-panel)] flex items-center justify-center shrink-0"
              style={{ backgroundColor: 'color-mix(in srgb, var(--accent) 14%, transparent)', color: 'var(--accent)' }}
            >
              <Box size={26} />
            </div>
            <div className="min-w-0 flex flex-col gap-1.5">
              <div className="flex items-center gap-2 flex-wrap">
                <h1 className="text-lg font-bold text-foundation-fg truncate">{device.name}</h1>
                <DeviceConnectivityBadge connectivity={connectivity} />
              </div>
              <div className="text-xs text-foundation-muted-fg">{device.deviceType}</div>
              <div className="text-[11px] font-mono text-foundation-tertiary-fg truncate">ID: {device.id}</div>

              <div className="mt-1">
                <span className="text-[10px] uppercase tracking-wide text-foundation-tertiary-fg">Capabilities</span>
                <DeviceCapabilitiesList capabilities={device.capabilities ?? []} />
                <div className="mt-2">
                  <DeviceCommandControls deviceId={device.id} capabilities={device.capabilities ?? []} />
                </div>
              </div>
            </div>
          </div>

          {/* Real state signals */}
          <div className="flex-1 flex flex-wrap items-center gap-x-8 gap-y-4">
            <div className="flex flex-col gap-1">
              <span className="text-[10px] text-foundation-muted-fg flex items-center gap-1.5">
                Connectivity <DataSourceBadge classification="LIVE" variant="foundation" />
              </span>
              <StatusIndicator tone={CONNECTIVITY_TONE[connectivity]} label={connectivity} pulse={connectivity === 'ONLINE'} />
            </div>

            <div className="flex flex-col gap-1">
              <span className="text-[10px] text-foundation-muted-fg flex items-center gap-1.5">
                Last Telemetry <DataSourceBadge classification="CALCULATED" variant="foundation" />
              </span>
              {twinState.telemetryLoading ? (
                <span className="text-sm font-semibold text-foundation-muted-fg">…</span>
              ) : twinState.lastTelemetryAt ? (
                <span className="text-sm font-semibold text-foundation-fg" title={new Date(twinState.lastTelemetryAt).toLocaleString()}>
                  {formatAge(twinState.lastTelemetryAt)}
                </span>
              ) : (
                <span className="text-sm font-semibold text-foundation-muted-fg">Unavailable</span>
              )}
            </div>

            <div className="flex flex-col gap-1">
              <span className="text-[10px] text-foundation-muted-fg">Reporting Fields</span>
              <span className="text-sm font-semibold text-foundation-fg">
                {twinState.reportedFields.length > 0 ? `${twinState.reportedFields.length} field(s)` : 'None reported'}
              </span>
            </div>

            <div className="flex flex-col gap-1">
              <span className="text-[10px] text-foundation-muted-fg">Lifecycle Status</span>
              <span className="text-sm font-semibold text-foundation-fg">{device.status}</span>
            </div>
          </div>
        </GlassPanel>

        {/* Contextual tab strip - horizontally scrollable on narrow screens */}
        <div className="shrink-0 overflow-x-auto custom-scrollbar">
          <Tabs items={TAB_ITEMS} value={activeTab} onChange={(v) => setActiveTab(v as TabId)} />
        </div>

        {/* TAB CONTENT */}
        <div className="flex-1 overflow-y-auto custom-scrollbar -mx-4 sm:-mx-6 px-4 sm:px-6">
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
