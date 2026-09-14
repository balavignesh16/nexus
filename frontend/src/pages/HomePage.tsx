import { useMemo } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useSites, useActiveDevices, useEvents, useActionHistory } from '../hooks/queries';
import { useExplorerHierarchy } from '../hooks/useExplorerHierarchy';
import { useConnectionStatus } from '../services/ConnectionManager';
import { usePolling } from '../hooks/usePolling';
import { useRepositories } from '../repositories/RepositoryProvider';
import { DeviceStatus } from '../api/deviceApi';
import {
  Building2,
  Layers,
  Box,
  Radio,
  WifiOff,
  Activity,
  AlertTriangle,
  Zap,
  Sparkles,
  Signal,
} from 'lucide-react';
import { GlassPanel } from '../components/foundation/GlassPanel';
import { Card } from '../components/foundation/Card';
import { MetricCard } from '../components/foundation/MetricCard';
import { SectionHeader } from '../components/foundation/SectionHeader';
import { StatusIndicator, type StatusTone } from '../components/foundation/StatusIndicator';
import { EmptyState } from '../components/foundation/EmptyState';
import { DataSourceBadge } from '../components/ui/DataSourceBadge';

const DEVICE_STATUS_TONE: Record<string, StatusTone> = {
  [DeviceStatus.ACTIVE]: 'online',
  [DeviceStatus.OFFLINE]: 'offline',
  [DeviceStatus.MAINTENANCE]: 'warning',
  [DeviceStatus.REGISTERED]: 'unknown',
};

export default function HomePage() {
  const { data: sites } = useSites();
  const { data: devices } = useActiveDevices();
  // Step 9B: 20 (not 25) is the established real limit - it's useEvents/
  // useActionHistory's own default and the exact value
  // RealtimeRepositorySync writes into ['events', 20]/['actions', 20].
  // Requesting 25 silently missed every realtime push once the socket
  // connected, since a differing query key never matches a synced one.
  const { data: events = [] } = useEvents(20);
  const { data: actions = [] } = useActionHistory(20);
  const { data: hierarchy } = useExplorerHierarchy();
  const connection = useConnectionStatus();
  const { analytics } = useRepositories();

  // Real connectivity distribution (Phase 8) - reuses the same Analytics
  // aggregation already built and tested for the connectivity summary
  // (devices LEFT JOIN device_connectivity, COALESCE to UNKNOWN). UNKNOWN
  // is never counted as OFFLINE. No duplicate backend logic is introduced.
  // Step 9D: routed through the same IAnalyticsRepository/DataSourceResolver
  // AnalyticsPage uses (Step 8B) instead of calling analyticsApi directly -
  // same '24h' range, same query key, same CALCULATED semantics.
  const { data: summary, isLoading: summaryLoading, error: summaryError } = usePolling(
    ['analytics-summary', '24h'],
    () => analytics.getAnalyticsSummary('24h'),
    { interval: 0 }
  );
  const offlineCount = summary?.connectivityDistribution?.OFFLINE ?? 0;

  // Unchanged real-but-currently-inert data flow (Step 2 does not touch
  // this): only ever hydrated by an external realtime write into the query
  // cache; queryFn intentionally starts empty.
  const { data: globalTelemetry = [] } = useQuery<any[]>({
    queryKey: ['telemetry', 100],
    queryFn: () => [],
    initialData: [],
  });

  const latestTelemetry = useMemo(() => {
    if (globalTelemetry.length === 0) return null;
    return [...globalTelemetry].sort((a, b) => b.timestamp - a.timestamp)[0];
  }, [globalTelemetry]);

  const liveActivity = useMemo(() => {
    const combined = [
      ...events.map((e: any) => ({
        id: `event-${e.id}`,
        timestamp: e.timestamp,
        title: e.type,
        description: e.message || `${e.severity} event from ${e.deviceId}`,
        icon: AlertTriangle,
        tone: 'var(--warning)',
      })),
      ...actions.map((a: any) => ({
        id: `action-${a.id}`,
        timestamp: a.timestamp,
        title: a.actionType,
        description: a.message || 'Action executed',
        icon: Zap,
        tone: 'var(--accent)',
      })),
    ];
    return combined.sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime()).slice(0, 8);
  }, [events, actions]);

  const siteCount = sites?.length || 0;
  const buildingCount = hierarchy?.buildingCount ?? 0;
  const spaceCount = hierarchy?.spaceCount ?? 0;
  const deviceCount = devices?.length || 0;

  return (
    <div
      className="flex flex-col h-full overflow-y-auto"
      style={{ backgroundColor: 'var(--foundation-bg)' }}
    >
      <div className="flex flex-col gap-6 p-4 sm:p-6 lg:p-8 max-w-[1600px] w-full mx-auto">
        {/* Context header */}
        <SectionHeader
          icon={<Sparkles size={18} />}
          title="NEXUS"
          subtitle="AI-driven physical intelligence platform — operational overview"
        />

        {/* System Snapshot */}
        <section>
          <h2 className="text-xs font-bold uppercase tracking-wider text-foundation-muted-fg mb-3">System Snapshot</h2>

          {/* Real platform health signals - same useConnectionStatus() values
              PlatformHealthWidget/HealthCard render elsewhere; reimplemented
              here as a compact glass strip to fit the light layout, not
              recomputed or fabricated. */}
          <GlassPanel level="glass" radius="panel" className="flex flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3 mb-3 sm:mb-4">
            <StatusIndicator
              tone={connection.backend === 'Online' ? 'online' : 'offline'}
              label={`Backend API · ${connection.backend}`}
            />
            <StatusIndicator
              tone={connection.database === 'Online' ? 'online' : 'offline'}
              label={`Database · ${connection.database}`}
            />
            <StatusIndicator
              tone={connection.mqtt === 'Online' ? 'online' : 'offline'}
              label={`MQTT Broker · ${connection.mqtt}`}
            />
            <StatusIndicator
              tone={connection.realtimeStatus === 'connected' ? 'online' : connection.realtimeStatus === 'demo' ? 'warning' : 'offline'}
              label={`Realtime · ${connection.realtimeStatus}`}
              pulse={connection.realtimeStatus === 'connected'}
            />
          </GlassPanel>

          <div className="grid grid-cols-2 sm:grid-cols-3 xl:grid-cols-6 gap-3 sm:gap-4">
            <MetricCard label="Sites" value={siteCount} icon={<Building2 size={18} />} />
            <MetricCard label="Buildings" value={buildingCount} icon={<Layers size={18} />} />
            <MetricCard label="Spaces" value={spaceCount} icon={<Box size={18} />} />
            <MetricCard label="Devices" value={deviceCount} icon={<Radio size={18} />} />
            <MetricCard
              label="Throughput"
              value={connection.messagesPerSec}
              unit="msg/s"
              icon={<Signal size={18} />}
            />
            <MetricCard
              label="Offline"
              value={summaryError ? 'Unavailable' : summaryLoading ? '…' : offlineCount}
              icon={<WifiOff size={18} />}
              badge={<DataSourceBadge classification={summaryError ? 'UNAVAILABLE' : 'CALCULATED'} variant="foundation" />}
            />
          </div>
        </section>

        {/* Main grid: Device/Physical System + Operational Activity */}
        <div className="grid grid-cols-1 xl:grid-cols-12 gap-4 sm:gap-6">
          {/* Device / Physical System area */}
          <div className="xl:col-span-7">
            <Card
              level="glass"
              icon={<Radio size={16} />}
              title="Devices"
              subtitle={`${deviceCount} provisioned`}
              padding="lg"
              className="h-full"
            >
              {(devices ?? []).length === 0 ? (
                <EmptyState icon={<Radio size={28} />} title="No devices provisioned yet." />
              ) : (
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
                  {(devices ?? []).slice(0, 6).map((device) => (
                    <div
                      key={device.id}
                      className="flex items-center gap-3 p-3 rounded-[var(--radius-panel)]"
                      style={{ backgroundColor: 'var(--foundation-surface)', border: '1px solid var(--foundation-border)' }}
                    >
                      <div
                        className="w-9 h-9 rounded-[var(--radius-panel)] flex items-center justify-center shrink-0"
                        style={{ backgroundColor: 'color-mix(in srgb, var(--accent) 14%, transparent)', color: 'var(--accent)' }}
                      >
                        <Box size={16} />
                      </div>
                      <div className="flex-1 min-w-0">
                        <div className="text-sm font-semibold text-foundation-fg truncate">{device.name}</div>
                        <div className="text-[11px] text-foundation-muted-fg truncate">{device.id}</div>
                      </div>
                      <StatusIndicator
                        tone={DEVICE_STATUS_TONE[device.status] ?? 'unknown'}
                        label={device.status}
                      />
                    </div>
                  ))}
                </div>
              )}
            </Card>
          </div>

          {/* Operational Activity */}
          <div className="xl:col-span-5">
            <Card
              level="glass"
              icon={<Activity size={16} />}
              title="Operational Activity"
              subtitle="Recent events & actions"
              padding="lg"
              className="h-full"
            >
              {liveActivity.length === 0 ? (
                <EmptyState icon={<Activity size={28} />} title="No recent events or actions recorded." />
              ) : (
                <div className="flex flex-col divide-y" style={{ borderColor: 'var(--foundation-border)' }}>
                  {liveActivity.map((item) => (
                    <div key={item.id} className="flex items-start gap-3 py-2.5 first:pt-0 last:pb-0">
                      <div
                        className="w-7 h-7 rounded-full flex items-center justify-center shrink-0 mt-0.5"
                        style={{ backgroundColor: `color-mix(in srgb, ${item.tone} 14%, transparent)`, color: item.tone }}
                      >
                        <item.icon size={13} />
                      </div>
                      <div className="flex-1 min-w-0">
                        <div className="text-sm font-medium text-foundation-fg truncate">{item.title}</div>
                        <div className="text-xs text-foundation-muted-fg truncate">{item.description}</div>
                      </div>
                      <span className="text-[10px] text-foundation-tertiary-fg shrink-0 mt-1">
                        {new Date(item.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </Card>
          </div>
        </div>

        {/* Telemetry + Intelligence */}
        <div className="grid grid-cols-1 xl:grid-cols-12 gap-4 sm:gap-6">
          {/* Telemetry area - honest: this page's telemetry query buffer is
              only ever hydrated by realtime writes (unchanged from before
              the redesign). A fabricated chart is never shown in its
              place - when the buffer is empty, that is stated plainly. */}
          <div className="xl:col-span-7">
            <Card
              level="glass"
              icon={<Signal size={16} />}
              title="Telemetry"
              subtitle="Live readings buffered on this page"
              padding="lg"
              action={<DataSourceBadge classification="LIVE" variant="foundation" />}
              className="h-full"
            >
              {latestTelemetry ? (
                <div className="flex items-baseline gap-3">
                  <span className="text-3xl font-semibold text-foundation-fg tabular-nums">{globalTelemetry.length}</span>
                  <span className="text-sm text-foundation-muted-fg">
                    readings · most recent {new Date(latestTelemetry.timestamp).toLocaleTimeString()}
                  </span>
                </div>
              ) : (
                <EmptyState
                  icon={<Signal size={28} />}
                  title="No live telemetry buffered on this page yet."
                  description="Open the Telemetry workspace for full device-level history."
                  action={
                    <Link
                      to="/telemetry"
                      className="text-xs font-medium"
                      style={{ color: 'var(--accent)' }}
                    >
                      Go to Telemetry →
                    </Link>
                  }
                />
              )}
            </Card>
          </div>

          {/* Intelligence - no new AI call/widget is introduced here. AI
              Insights is a stateful investigation workspace (select a rule
              match, then fetch its explanation) with no standalone
              reusable "latest insight" component to surface without
              building new AI logic, which this phase explicitly forbids.
              An honest unavailable/empty state links to the real page
              instead. */}
          <div className="xl:col-span-5">
            <Card
              level="glass"
              icon={<Sparkles size={16} />}
              title="Intelligence"
              subtitle="Evidence-grounded rule-match explanations"
              padding="lg"
              action={<DataSourceBadge classification="UNAVAILABLE" variant="foundation" />}
              className="h-full"
            >
              <EmptyState
                icon={<Sparkles size={28} />}
                title="No intelligence summary surfaced here."
                description="Open NEXUS Intelligence to review evidence-grounded explanations for real rule matches."
                action={
                  <Link
                    to="/ai"
                    className="text-xs font-medium"
                    style={{ color: 'var(--accent)' }}
                  >
                    Go to Intelligence →
                  </Link>
                }
              />
            </Card>
          </div>
        </div>
      </div>
    </div>
  );
}
