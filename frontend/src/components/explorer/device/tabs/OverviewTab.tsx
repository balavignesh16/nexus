import { useState, useEffect } from 'react';
import { RefreshCw, Download, FileJson, ArrowRight, TestTube, MoreHorizontal, ShieldCheck, ShieldAlert, ShieldQuestion, Sparkles, Signal, Play } from 'lucide-react';
import { useDevice } from '../../../../hooks/queries';
import { ScenarioEngine } from '../../../../simulation/ScenarioEngine';
import { DebugOverlay } from '../DebugOverlay';
import { FEATURES } from '../../../../config/features';
import { useRepositories } from '../../../../repositories/RepositoryProvider';
import { usePolling } from '../../../../hooks/usePolling';
import { deviceApi, type DeviceConnectivityResult } from '../../../../api/deviceApi';
import { useDeviceTwinState } from '../../../../hooks/useDeviceTwinState';
import { formatAge } from '../../../../utils/formatAge';
import { downloadCsv } from '../../../../utils/csv';
import { DataSourceBadge } from '../../../ui/DataSourceBadge';
import { Card } from '../../../foundation/Card';
import { LoadingBlock } from '../../../foundation/LoadingState';
import type { TabId } from '../DeviceWorkspace';

interface OverviewTabProps {
  deviceId: string;
  onNavigateToTab?: (tab: TabId) => void;
}

export function OverviewTab({ deviceId, onNavigateToTab }: OverviewTabProps) {
  const { data: device, isLoading, refetch } = useDevice(deviceId);
  const { telemetry, events: eventsRepo } = useRepositories();
  const [events, setEvents] = useState<any[]>([]);
  const [actionState, setActionState] = useState<{ label: string; message: string } | null>(null);
  const twinState = useDeviceTwinState(deviceId);

  // System Health: real last-known connectivity (Tier 5B), not a fabricated
  // ping - same source as the Test Connection quick action, polled quietly
  // in the background so the health summary stays current without the user
  // clicking anything. Disabled in demo mode - there is no live backend to
  // query, matching Test Connection's own demo-mode branch.
  const { data: connectivity } = usePolling<DeviceConnectivityResult>(
    ['device-connectivity', deviceId],
    () => deviceApi.getDeviceConnectivity(deviceId),
    { interval: 10000, enabled: !FEATURES.DEMO_MODE }
  );

  // Active Events: real backend event history, filtered to what actually
  // needs operator attention. Never demo/fabricated - this repository call
  // already resolves to real backend data outside DEMO_MODE.
  const { data: recentEvents = [] } = usePolling<any[]>(
    ['overview-active-events', deviceId],
    () => eventsRepo.getEvents(deviceId, 20),
    { interval: 5000 }
  );
  const activeEvents = recentEvents.filter((e: any) => e.severity === 'CRITICAL' || e.severity === 'WARNING').slice(0, 4);

  useEffect(() => {
    // ScenarioEngine is a demo-only data source - never subscribe to it in
    // live mode, so this timeline can't be populated by a demo event.
    if (!FEATURES.DEMO_MODE) return;

    const updateEvents = () => {
      setEvents(ScenarioEngine.getEvents(deviceId).slice(0, 5));
    };
    updateEvents();
    const unsubscribe = ScenarioEngine.subscribe(updateEvents);
    return () => { unsubscribe(); };
  }, [deviceId]);

  if (isLoading || !device) {
    return (
      <div className="py-6 flex flex-col gap-4 max-w-[1600px] mx-auto">
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          <LoadingBlock className="h-28" />
          <LoadingBlock className="h-28" />
          <LoadingBlock className="h-28" />
        </div>
        <LoadingBlock className="h-40" />
        <LoadingBlock className="h-64" />
      </div>
    );
  }

  // Device Status - Latency/Twin Age/Uptime/Firmware/Last Reboot have no
  // real backend source today - shown as honestly unavailable rather than
  // invented.
  const statusProperties = [
    { label: 'State', value: device.status, isSuccess: device.status === 'ACTIVE' },
    { label: 'Transport', value: 'MQTT' },
    { label: 'Polling Frequency', value: '5s' },
    { label: 'Latency', value: 'Unavailable', unavailable: true },
    { label: 'Twin Age', value: 'Unavailable', unavailable: true },
    { label: 'Uptime', value: 'Unavailable', unavailable: true },
    { label: 'Firmware', value: 'Unavailable', unavailable: true },
    { label: 'Last Reboot', value: 'Unavailable', unavailable: true },
  ];

  // Quick Actions - every one of these does something real: navigation
  // actions switch tabs, Refresh Twin re-fetches the real device from the
  // backend, Download Telemetry exports the real telemetry history already
  // available to this device, and Test Connection reads the real
  // last-known MQTT connectivity status (Tier 5B) - never a fabricated
  // ping/latency number.
  const handleQuickAction = async (label: string) => {
    setActionState(null);
    switch (label) {
      case 'View Raw JSON':
        onNavigateToTab?.('json');
        return;
      case 'Jump to Rules':
        onNavigateToTab?.('rules');
        return;
      case 'Jump to Events':
        onNavigateToTab?.('events');
        return;
      case 'Jump to Actions':
        onNavigateToTab?.('actions');
        return;
      case 'More':
        onNavigateToTab?.('diagnostics');
        return;
      case 'Refresh Twin': {
        const result = await refetch();
        setActionState({
          label,
          message: result.isSuccess ? 'Digital twin refreshed from the backend.' : 'Refresh failed.',
        });
        return;
      }
      case 'Download Telemetry': {
        try {
          const history = await telemetry.getTelemetryHistory(deviceId);
          if (!history || history.length === 0) {
            setActionState({ label, message: 'No telemetry history to export yet.' });
            return;
          }
          const keys = Array.from(new Set(history.flatMap((t: any) => Object.keys(t.payload || {}))));
          const columns = ['timestamp', ...keys];
          const rows = history.map((t: any) => [
            new Date(t.timestamp).toISOString(),
            ...keys.map((k) => (t.payload[k] !== undefined ? String(t.payload[k]) : '')),
          ]);
          downloadCsv(`telemetry-${deviceId}-${Date.now()}.csv`, columns, rows);
          setActionState({ label, message: `Exported ${history.length} record(s).` });
        } catch {
          setActionState({ label, message: 'Download failed.' });
        }
        return;
      }
      case 'Test Connection': {
        if (FEATURES.DEMO_MODE) {
          // Demo mode has no live backend to query - preserves the existing
          // simulated diagnostic behavior rather than failing a real call.
          // @ts-ignore
          ScenarioEngine.emitEvent('Diagnostic', 'Ping successful. Latency 24ms', deviceId);
          setActionState({ label, message: 'Ping successful. Latency 24ms (demo)' });
          return;
        }
        try {
          const connectivity = await deviceApi.getDeviceConnectivity(deviceId);
          const humanStatus = connectivity.status === 'UNKNOWN'
            ? 'No connectivity data has ever been received for this device.'
            : `Last known status: ${connectivity.status}` + (connectivity.lastChangedAt
                ? ` (since ${new Date(connectivity.lastChangedAt).toLocaleString()})`
                : '');
          setActionState({ label, message: humanStatus });
        } catch {
          setActionState({ label, message: 'Could not read connectivity status.' });
        }
        return;
      }
      default:
        if (FEATURES.DEMO_MODE) {
          // Demo-only fallback for any label not handled above.
          // @ts-ignore
          ScenarioEngine.emitEvent('Action Executed', `${label} (demo)`, deviceId);
        }
    }
  };

  const actions = [
    { label: 'Refresh Twin', icon: RefreshCw },
    { label: 'Download Telemetry', icon: Download },
    { label: 'View Raw JSON', icon: FileJson },
    { label: 'Jump to Rules', icon: ArrowRight },
    { label: 'Jump to Events', icon: ArrowRight },
    { label: 'Jump to Actions', icon: Play },
    { label: 'Test Connection', icon: TestTube },
    { label: 'More', icon: MoreHorizontal },
  ];

  const connectivityStatus = connectivity?.status ?? 'UNKNOWN';
  const healthTone = connectivityStatus === 'ONLINE'
    ? { icon: ShieldCheck, color: 'var(--success)' }
    : connectivityStatus === 'OFFLINE'
      ? { icon: ShieldAlert, color: 'var(--danger)' }
      : { icon: ShieldQuestion, color: 'var(--data-unavailable)' };

  return (
    <div className="py-6 flex flex-col gap-6 max-w-[1600px] mx-auto">

      {/* SYSTEM HEALTH + AI INTELLIGENCE + ACTIVE EVENTS - answers
          "what is happening right now, what matters, what should I do" -
          before any raw metric grid. */}
      <section className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        {/* A. System Health */}
        <Card level="glass" padding="lg">
          <div className="flex items-center justify-between mb-1">
            <span className="text-xs font-semibold uppercase tracking-wider text-foundation-muted-fg">System Health</span>
            <DataSourceBadge classification="LIVE" variant="foundation" />
          </div>
          <div className="flex items-center gap-3 mt-2">
            <healthTone.icon size={28} style={{ color: healthTone.color }} />
            <div>
              <div className="text-lg font-bold" style={{ color: healthTone.color }}>{connectivityStatus}</div>
              <div className="text-[11px] text-foundation-muted-fg">
                {connectivityStatus === 'UNKNOWN'
                  ? 'No connectivity ever observed'
                  : `Since ${connectivity?.lastChangedAt ? new Date(connectivity.lastChangedAt).toLocaleString() : '—'}`}
              </div>
            </div>
          </div>
          <div className="flex items-center justify-between pt-3 mt-3 border-t border-foundation-border text-xs">
            <span className="text-foundation-muted-fg">Lifecycle status</span>
            <span className="font-medium text-foundation-fg">{device.status}</span>
          </div>
        </Card>

        {/* B. AI Intelligence - honest placeholder: NEXUS's real AI capability
            (Tier 4) explains rule matches with grounded evidence; it does not
            yet perform standing anomaly detection on arbitrary devices. This
            section must never fabricate an insight to look populated. */}
        <Card
          level="glass"
          padding="lg"
          icon={<Sparkles size={16} />}
          title="NEXUS Intelligence"
          action={<DataSourceBadge classification="UNAVAILABLE" variant="foundation" />}
        >
          <p className="text-xs text-foundation-muted-fg">
            No standing anomaly analysis exists for this device yet. Evidence-grounded explanations are available for rule matches once one occurs.
          </p>
        </Card>

        {/* C. Active Events */}
        <Card
          level="glass"
          padding="lg"
          title="Active Events"
          action={<button onClick={() => onNavigateToTab?.('events')} className="text-[10px] font-medium" style={{ color: 'var(--accent)' }}>View all</button>}
        >
          {activeEvents.length === 0 ? (
            <div className="py-4 text-xs text-foundation-muted-fg">No critical or warning events</div>
          ) : (
            <div className="flex flex-col gap-2">
              {activeEvents.map((ev: any, i: number) => (
                <div key={ev.id || i} className="flex items-center gap-2 text-xs">
                  <span className="w-1.5 h-1.5 rounded-full shrink-0" style={{ backgroundColor: ev.severity === 'CRITICAL' ? 'var(--danger)' : 'var(--warning)' }} />
                  <span className="text-foundation-fg/90 truncate flex-1">{ev.eventType || ev.type || 'Event'}</span>
                  <span className="text-foundation-tertiary-fg shrink-0">{new Date(ev.timestamp).toLocaleTimeString()}</span>
                </div>
              ))}
            </div>
          )}
        </Card>
      </section>

      {/* LATEST READING + DEVICE STATUS - a real single latest reading
          rather than a fabricated trend chart; a link to the full history
          on Telemetry instead of inventing a time-series visualization. */}
      <section className="grid grid-cols-12 gap-6">
        <Card
          level="glass"
          padding="lg"
          icon={<Signal size={16} />}
          title="Latest Reading"
          action={<DataSourceBadge classification="LIVE" variant="foundation" />}
          className="col-span-12 lg:col-span-8"
        >
          {twinState.telemetryLoading ? (
            <LoadingBlock className="h-16" />
          ) : twinState.lastTelemetryAt ? (
            <div className="flex flex-col gap-3">
              <div className="flex items-baseline gap-2">
                <span className="text-3xl font-semibold text-foundation-fg tabular-nums">{formatAge(twinState.lastTelemetryAt)}</span>
              </div>
              <div className="text-xs text-foundation-muted-fg">
                {twinState.reportedFields.length > 0
                  ? `Reporting: ${twinState.reportedFields.join(', ')}`
                  : 'No fields reported in the latest record.'}
              </div>
              <button
                onClick={() => onNavigateToTab?.('telemetry')}
                className="self-start text-xs font-medium mt-1"
                style={{ color: 'var(--accent)' }}
              >
                View full telemetry history →
              </button>
            </div>
          ) : (
            <div className="py-4">
              <p className="text-sm text-foundation-fg font-medium">Waiting for telemetry.</p>
              <p className="text-xs text-foundation-muted-fg mt-1">No telemetry has been received for this device yet.</p>
            </div>
          )}
        </Card>

        {/* Device Status */}
        <Card level="glass" padding="lg" title="Device Status" className="col-span-12 lg:col-span-4 relative">
          <DebugOverlay
            title="Device Status"
            data={device}
            correlationId={`corr-dev-${device.id}`}
            repository="DataSourceResolver"
          />
          <div className="flex flex-col gap-2.5">
            {statusProperties.map((prop, idx) => (
              <div key={idx} className="flex items-center justify-between border-b border-foundation-border/60 pb-2.5 last:border-0 last:pb-0">
                <span className="text-xs text-foundation-muted-fg">{prop.label}</span>
                <span
                  className="text-xs font-mono font-medium tracking-wide"
                  style={{ color: prop.isSuccess ? 'var(--success)' : prop.unavailable ? 'var(--foundation-muted-fg)' : 'var(--foundation-fg)' }}
                >
                  {prop.value}
                </span>
              </div>
            ))}
          </div>
        </Card>
      </section>

      {/* QUICK ACTIONS & RECENT ACTIVITY */}
      <section className="grid grid-cols-12 gap-6 pb-2">
        <Card level="glass" padding="lg" title="Quick Actions" className="col-span-12 lg:col-span-8">
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            {actions.map((act, i) => (
              <button
                key={i}
                data-testid={`quick-action-${act.label}`}
                onClick={() => handleQuickAction(act.label)}
                className="rounded-[var(--radius-panel)] p-4 flex flex-col items-center justify-center gap-2.5 transition-colors"
                style={{ backgroundColor: 'var(--glass-bg)', border: '1px solid var(--glass-border)' }}
              >
                <act.icon size={18} style={{ color: 'var(--accent)' }} />
                <span className="text-[11px] font-medium text-foundation-muted-fg text-center">
                  {act.label}
                </span>
              </button>
            ))}
          </div>
          {actionState && (
            <div data-testid="quick-action-result" className="mt-4 text-xs text-foundation-muted-fg border-t border-foundation-border pt-3">
              <span className="font-medium text-foundation-fg">{actionState.label}:</span> {actionState.message}
            </div>
          )}
        </Card>

        {/* Recent Activity - demo-only timeline; in live mode this is
            intentionally empty (no live per-device push feed exists here
            beyond Active Events above), matching prior behavior. */}
        <Card level="glass" padding="lg" title="Recent Activity" className="col-span-12 lg:col-span-4 min-h-[220px] flex flex-col">
          <div className="flex-1 flex flex-col">
            {events.length > 0 ? (
              <div className="space-y-4">
                {events.map((ev, idx) => (
                  <div key={idx} className="flex gap-3">
                    <div className="w-2 h-2 rounded-full mt-1.5 shrink-0" style={{ backgroundColor: ev.type.includes('Alert') ? 'var(--danger)' : 'var(--accent)' }} />
                    <div className="flex-1">
                      <div className="flex items-center gap-2 mb-0.5">
                        <span className="text-[10px] font-mono text-foundation-tertiary-fg font-bold">
                          {new Date(ev.timestamp).toLocaleTimeString()}
                        </span>
                      </div>
                      <p className="text-xs font-semibold text-foundation-fg">{ev.type}</p>
                      <p className="text-[11px] text-foundation-muted-fg mt-0.5">{ev.message}</p>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <div className="flex-1 flex items-center justify-center text-xs text-foundation-muted-fg">
                Pipeline listening...
              </div>
            )}
          </div>
        </Card>
      </section>

    </div>
  );
}
