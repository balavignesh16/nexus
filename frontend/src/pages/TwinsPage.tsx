import { useState, useMemo } from 'react';
import { useRepositories } from '../repositories/RepositoryProvider';
import { usePolling } from '../hooks/usePolling';
import { Box, Search, Cpu } from 'lucide-react';
import { useInspector } from '../components/ui/InspectorProvider';
import { useDeviceTwinState } from '../hooks/useDeviceTwinState';
import { formatAge } from '../utils/formatAge';
import { DataSourceBadge } from '../components/ui/DataSourceBadge';
import { DeviceStatus } from '../api/deviceApi';
import { GlassPanel } from '../components/foundation/GlassPanel';
import { SectionHeader } from '../components/foundation/SectionHeader';
import { Input } from '../components/foundation/Input';
import { StatusIndicator, type StatusTone } from '../components/foundation/StatusIndicator';
import { EmptyState } from '../components/foundation/EmptyState';
import { ErrorState } from '../components/foundation/ErrorState';
import { LoadingBlock } from '../components/foundation/LoadingState';

// Device.status (administrative lifecycle) - deliberately a distinct
// concept from connectivity (physical MQTT reachability, Tier 5B). All
// four real values are preserved and shown honestly; MAINTENANCE/
// REGISTERED are never silently collapsed into "Offline".
const LIFECYCLE_TONE: Record<string, StatusTone> = {
  [DeviceStatus.ACTIVE]: 'online',
  [DeviceStatus.OFFLINE]: 'offline',
  [DeviceStatus.MAINTENANCE]: 'warning',
  [DeviceStatus.REGISTERED]: 'unknown',
};

const CONNECTIVITY_TONE: Record<string, StatusTone> = {
  ONLINE: 'online',
  OFFLINE: 'offline',
  UNKNOWN: 'unknown',
};

function TwinCard({ device, openInspector }: { device: any; openInspector: (item: any) => void }) {
  const twinState = useDeviceTwinState(device.id);
  const connectivity = twinState.connectivity?.status ?? 'UNKNOWN';
  const capabilities = device.capabilities ?? [];

  const handleOpen = () =>
    openInspector({
      id: device.id,
      type: 'Twin',
      title: device.name,
      subtitle: device.id,
      status: device.status,
      statusColor: device.status === 'ACTIVE' ? 'success' : device.status === 'MAINTENANCE' ? 'warning' : device.status === 'OFFLINE' ? 'destructive' : undefined,
      properties: {
        'Device Type': device.deviceType,
        'Lifecycle Status': device.status,
        'Connectivity': connectivity,
        'Last Telemetry': twinState.lastTelemetryAt ? new Date(twinState.lastTelemetryAt).toLocaleString() : 'Unavailable',
        'Reporting Fields': twinState.reportedFields.length > 0 ? twinState.reportedFields.join(', ') : 'None',
        'Capabilities': capabilities.length > 0
          ? capabilities.map((c: any) => `${c.capabilityType} (${c.direction}${c.unit ? `, ${c.unit}` : ''})`).join('; ')
          : 'None reported',
      },
      rawJson: device,
      // No real per-twin event history source exists yet - omitted rather
      // than fabricated (Phase 8, preserved). Never mqttTopic - that field
      // does not exist on the real Device DTO.
    });

  return (
    <GlassPanel
      level="glass"
      className="p-5 cursor-pointer transition-shadow hover:shadow-[var(--shadow-glass-lg)]"
      onClick={handleOpen}
    >
      <div className="flex justify-between items-start gap-3 mb-4">
        <div className="flex items-center gap-3 min-w-0">
          <div
            className="w-10 h-10 rounded-[var(--radius-panel)] flex items-center justify-center shrink-0"
            style={{ backgroundColor: 'color-mix(in srgb, var(--accent) 14%, transparent)', color: 'var(--accent)' }}
          >
            <Cpu size={18} />
          </div>
          <div className="min-w-0">
            <h3 className="font-semibold text-sm text-foundation-fg truncate">{device.name}</h3>
            <p className="text-xs text-foundation-tertiary-fg font-mono truncate">{device.id}</p>
          </div>
        </div>
        <StatusIndicator tone={LIFECYCLE_TONE[device.status] ?? 'unknown'} label={device.status} />
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div className="space-y-1">
          <span className="text-[10px] text-foundation-muted-fg uppercase tracking-wider flex items-center gap-1">
            Connectivity <DataSourceBadge classification="LIVE" variant="foundation" />
          </span>
          <StatusIndicator tone={CONNECTIVITY_TONE[connectivity] ?? 'unknown'} label={connectivity} pulse={connectivity === 'ONLINE'} />
        </div>
        <div className="space-y-1">
          <span className="text-[10px] text-foundation-muted-fg uppercase tracking-wider flex items-center gap-1">
            Last Telemetry <DataSourceBadge classification="CALCULATED" variant="foundation" />
          </span>
          <span className="font-mono text-sm font-medium text-foundation-fg" title={twinState.lastTelemetryAt ? new Date(twinState.lastTelemetryAt).toLocaleString() : undefined}>
            {twinState.telemetryLoading ? '…' : twinState.lastTelemetryAt ? formatAge(twinState.lastTelemetryAt) : 'Unavailable'}
          </span>
        </div>
      </div>

      {/* Capabilities - real device.capabilities already present on the
          fetched device response; never a separate per-card fetch. */}
      <div className="mt-4 pt-3 border-t border-foundation-border">
        <span className="text-[10px] text-foundation-muted-fg uppercase tracking-wider">Capabilities</span>
        {capabilities.length === 0 ? (
          <p className="text-xs text-foundation-tertiary-fg mt-1">No capabilities reported</p>
        ) : (
          <div className="flex flex-wrap gap-1.5 mt-1.5">
            {capabilities.slice(0, 4).map((c: any) => (
              <span
                key={c.id}
                className="text-[10px] font-mono px-2 py-0.5 rounded-[var(--radius-pill)]"
                style={{ backgroundColor: 'var(--foundation-bg)', border: '1px solid var(--foundation-border)', color: 'var(--foundation-muted-fg)' }}
              >
                {c.capabilityType} · {c.direction}{c.unit ? ` · ${c.unit}` : ''}
              </span>
            ))}
            {capabilities.length > 4 && (
              <span className="text-[10px] text-foundation-tertiary-fg px-1 py-0.5">+{capabilities.length - 4} more</span>
            )}
          </div>
        )}
      </div>

      <div className="mt-3 pt-3 border-t border-foundation-border text-xs text-foundation-muted-fg">
        {twinState.reportedFields.length > 0
          ? `Reporting ${twinState.reportedFields.length} field(s): ${twinState.reportedFields.join(', ')}`
          : 'No telemetry fields reported yet'}
      </div>
    </GlassPanel>
  );
}

export default function TwinsPage() {
  const { device } = useRepositories();
  const { data: devices = [], isLoading, error } = usePolling(
    ['all-devices'],
    () => device.getDevices(),
    { interval: 5000 }
  );

  const [search, setSearch] = useState('');
  const { openInspector } = useInspector();

  const filteredDevices = useMemo(() => {
    return devices.filter(d => d.name.toLowerCase().includes(search.toLowerCase()) || d.id.toLowerCase().includes(search.toLowerCase()));
  }, [devices, search]);

  return (
    <div className="flex flex-col h-full overflow-y-auto" style={{ backgroundColor: 'var(--foundation-bg)' }}>
      <div className="flex flex-col gap-6 p-4 sm:p-6 lg:p-8 max-w-[1600px] w-full mx-auto">

        {/* HEADER */}
        <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4">
          <SectionHeader
            icon={<Box size={18} />}
            title="Digital Twins"
            subtitle={`${devices.length} device(s) represented as a digital twin`}
          />
          <div className="relative">
            <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-foundation-muted-fg" />
            <Input
              type="text"
              placeholder="Search twins..."
              value={search}
              onChange={e => setSearch(e.target.value)}
              className="pl-9 w-64"
            />
          </div>
        </div>

        {/* TWIN GRID */}
        {isLoading && devices.length === 0 ? (
          <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4 gap-4">
            <LoadingBlock className="h-56" />
            <LoadingBlock className="h-56" />
            <LoadingBlock className="h-56" />
          </div>
        ) : error ? (
          <ErrorState message="Could not load devices." />
        ) : devices.length === 0 ? (
          <EmptyState
            icon={<Box size={28} />}
            title="No devices to represent as a digital twin."
          />
        ) : filteredDevices.length === 0 ? (
          <EmptyState
            icon={<Search size={28} />}
            title="No twins match your search."
            description="Try a different device name or ID."
          />
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4 gap-4">
            {filteredDevices.map((d: any) => (
              <TwinCard key={d.id} device={d} openInspector={openInspector} />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
