import { useState, useEffect } from 'react';
import { Play, Maximize2, RefreshCw, Download, FileJson, ArrowRight, Circle, AlertTriangle, TestTube, MoreHorizontal, ShieldCheck, ShieldAlert, ShieldQuestion, Sparkles } from 'lucide-react';
import { useDevice } from '../../../../hooks/queries';
import { SkeletonCard, SkeletonChart, SkeletonTimeline } from '../../../ui/Skeletons';
import { ScenarioEngine } from '../../../../simulation/ScenarioEngine';
import { DebugOverlay } from '../DebugOverlay';
import { FEATURES } from '../../../../config/features';
import { useRepositories } from '../../../../repositories/RepositoryProvider';
import { usePolling } from '../../../../hooks/usePolling';
import { deviceApi, type DeviceConnectivityResult } from '../../../../api/deviceApi';
import { downloadCsv } from '../../../../utils/csv';
import { DataSourceBadge } from '../../../ui/DataSourceBadge';
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
      <div className="p-6 h-full flex flex-col space-y-6 animate-in slide-in-from-bottom-2 fade-in max-w-[1600px] mx-auto">
        <section>
          <div className="h-4 w-32 bg-[#1f1f28] rounded mb-4 animate-pulse" />
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
            <SkeletonCard />
            <SkeletonCard />
            <SkeletonCard />
            <SkeletonCard />
          </div>
        </section>
        <section className="grid grid-cols-12 gap-6 min-h-[350px]">
          <div className="col-span-12 lg:col-span-8">
            <SkeletonChart />
          </div>
          <div className="col-span-12 lg:col-span-4">
            <SkeletonCard />
          </div>
        </section>
        <section className="grid grid-cols-12 gap-6 pb-6">
          <div className="col-span-12 lg:col-span-8">
            <SkeletonCard />
          </div>
          <div className="col-span-12 lg:col-span-4">
            <SkeletonTimeline />
          </div>
        </section>
      </div>
    );
  }

  // 1. Live Readings Mapping
  const getIconColor = (index: number) => {
    const colors = ['text-blue-400', 'text-cyan-400', 'text-indigo-400', 'text-emerald-400', 'text-amber-400', 'text-rose-400'];
    return colors[index % colors.length];
  };

  const getStrokeColor = (index: number) => {
    const colors = ['stroke-blue-500', 'stroke-cyan-500', 'stroke-indigo-500', 'stroke-emerald-500', 'stroke-amber-500', 'stroke-rose-500'];
    return colors[index % colors.length];
  };

  // 2. Device Status
  // Latency/Twin Age/Uptime/Firmware/Last Reboot have no real backend source
  // today - shown as honestly unavailable rather than invented.
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

  // 3. Quick Actions - every one of these now does something real:
  // navigation actions switch tabs, Refresh Twin re-fetches the real device
  // from the backend, Download Telemetry exports the real telemetry history
  // already available to this device, and Test Connection reads the real
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
    ? { icon: ShieldCheck, color: 'text-success', ring: 'border-success/30 bg-success/5' }
    : connectivityStatus === 'OFFLINE'
      ? { icon: ShieldAlert, color: 'text-destructive', ring: 'border-destructive/30 bg-destructive/5' }
      : { icon: ShieldQuestion, color: 'text-[var(--data-unavailable)]', ring: 'border-surface-border bg-surface' };

  return (
    <div className="p-6 h-full flex flex-col space-y-6 animate-in slide-in-from-bottom-2 fade-in duration-300 max-w-[1600px] mx-auto overflow-y-auto custom-scrollbar">

      {/* SYSTEM HEALTH + AI INTELLIGENCE + ACTIVE EVENTS - answers
          "what is happening right now, what matters, what should I do" -
          before any raw metric grid. */}
      <section className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        {/* A. System Health */}
        <div className={`rounded-xl border p-5 flex flex-col gap-3 shadow-sm ${healthTone.ring}`}>
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">System Health</span>
            <DataSourceBadge classification="LIVE" />
          </div>
          <div className="flex items-center gap-3">
            <healthTone.icon size={28} className={healthTone.color} />
            <div>
              <div className={`text-lg font-bold ${healthTone.color}`}>{connectivityStatus}</div>
              <div className="text-[11px] text-muted-foreground">
                {connectivityStatus === 'UNKNOWN'
                  ? 'No connectivity ever observed'
                  : `Since ${connectivity?.lastChangedAt ? new Date(connectivity.lastChangedAt).toLocaleString() : '—'}`}
              </div>
            </div>
          </div>
          <div className="flex items-center justify-between pt-3 border-t border-surface-border/60 text-xs">
            <span className="text-muted-foreground">Lifecycle status</span>
            <span className="font-medium text-foreground/90">{device.status}</span>
          </div>
        </div>

        {/* C. AI Intelligence - honest placeholder: NEXUS's real AI capability
            (Tier 4) explains rule matches with grounded evidence; it does not
            yet perform standing anomaly detection on arbitrary devices. This
            section must never fabricate an insight to look populated. */}
        <div className="rounded-xl border border-surface-border bg-surface p-5 flex flex-col gap-3 shadow-sm">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold uppercase tracking-wider text-muted-foreground flex items-center gap-1.5">
              <Sparkles size={13} className="text-primary" /> NEXUS Intelligence
            </span>
            <DataSourceBadge classification="UNAVAILABLE" />
          </div>
          <div className="flex-1 flex flex-col items-center justify-center text-center gap-2 py-4">
            <p className="text-xs text-muted-foreground max-w-[220px]">
              No standing anomaly analysis exists for this device yet. Evidence-grounded explanations are available for rule matches once one occurs.
            </p>
          </div>
        </div>

        {/* D. Active Events */}
        <div className="rounded-xl border border-surface-border bg-surface p-5 flex flex-col gap-3 shadow-sm">
          <div className="flex items-center justify-between">
            <span className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">Active Events</span>
            <button onClick={() => onNavigateToTab?.('events')} className="text-[10px] text-primary hover:underline">View all</button>
          </div>
          {activeEvents.length === 0 ? (
            <div className="flex-1 flex items-center justify-center text-xs text-muted-foreground py-4">No critical or warning events</div>
          ) : (
            <div className="flex flex-col gap-2">
              {activeEvents.map((ev: any, i: number) => (
                <div key={ev.id || i} className="flex items-center gap-2 text-xs">
                  <span className={`w-1.5 h-1.5 rounded-full shrink-0 ${ev.severity === 'CRITICAL' ? 'bg-destructive' : 'bg-warning'}`} />
                  <span className="text-foreground/80 truncate flex-1">{ev.eventType || ev.type || 'Event'}</span>
                  <span className="text-muted-foreground/60 shrink-0">{new Date(ev.timestamp).toLocaleTimeString()}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      </section>

      {/* ROW 1: LIVE READINGS */}
      <section>
        <h3 className="text-sm font-semibold text-foreground mb-4">Live Readings</h3>
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
          {device.metrics && device.metrics.length > 0 ? (
            device.metrics.map((r: any, i: number) => (
              <div key={i} className="bg-[#13131a] border border-[#1f1f28] rounded-xl p-4 shadow-sm flex flex-col justify-between hover:border-border/60 transition-colors">
                <span className="text-xs text-muted-foreground font-medium uppercase tracking-wider">{r.name.replace('_', ' ')}</span>
                <div className="flex items-baseline gap-1 mt-1">
                  <span className="text-3xl font-bold text-foreground">{r.value}</span>
                  <span className="text-sm text-muted-foreground">{r.unit}</span>
                </div>
                
                {r.name.toLowerCase() === 'battery' ? (
                  <div className="h-10 mt-3 w-full flex items-center relative overflow-hidden rounded">
                    <div className="w-full bg-[#0a0a0f] h-2.5 rounded-full overflow-hidden border border-[#1f1f28] relative z-10">
                      <div className="bg-[#22c55e] h-full shadow-[0_0_8px_rgba(34,197,94,0.5)]" style={{ width: `${r.value}%` }}></div>
                    </div>
                  </div>
                ) : (
                  <div className="h-10 mt-3 w-full relative overflow-hidden">
                    <svg viewBox="0 0 100 30" className="w-full h-full preserve-3d" preserveAspectRatio="none">
                      <path 
                        d={r.name.toLowerCase() === 'pressure' ? `M0,15 L100,15` : `M0,20 Q10,${Math.random()*10+15} 20,15 T40,10 T60,20 T80,5 T100,15`} 
                        fill="none" 
                        className={getStrokeColor(i)} 
                        strokeWidth="2" 
                        strokeLinecap="round" 
                      />
                      <path 
                        d={r.name.toLowerCase() === 'pressure' ? `M0,30 L0,15 L100,15 L100,30 Z` : `M0,30 L0,20 Q10,${Math.random()*10+15} 20,15 T40,10 T60,20 T80,5 T100,15 L100,30 Z`} 
                        fill="currentColor" 
                        className={`opacity-10 ${getIconColor(i)}`} 
                      />
                    </svg>
                  </div>
                )}
  
                <div className="flex items-center justify-between mt-3 text-[10px]">
                  <span className={`${getIconColor(i)} font-medium capitalize`}>{r.trend || `Trend`}</span>
                  <span className="text-muted-foreground">{r.trendLabel || `vs 1m ago`}</span>
                </div>
              </div>
            ))
          ) : (
            <div className="col-span-full py-8 text-center border border-dashed border-[#1f1f28] rounded-xl text-muted-foreground text-xs">
              Waiting for telemetry...
            </div>
          )}
        </div>
      </section>

      {/* ROW 2: CHART & STATUS */}
      <section className="grid grid-cols-12 gap-6 min-h-[350px]">
        
        {/* Large Chart */}
        <div className="col-span-12 lg:col-span-8 bg-[#12121a] border border-[#1f1f28] rounded-xl p-4 flex flex-col shadow-sm relative group overflow-hidden">
          <div className="flex items-center justify-between mb-4 z-10">
            <h3 className="text-sm font-semibold text-foreground">Temperature Trend</h3>
            <div className="flex items-center gap-3">
              <select className="bg-[#13131a] border border-border/30 rounded text-xs px-2 py-1 text-muted-foreground focus:outline-none focus:border-primary cursor-pointer">
                <option>5 Minutes</option>
                <option>15 Minutes</option>
                <option>1 Hour</option>
              </select>
              <div className="flex rounded border border-border/30 overflow-hidden">
                <button className="bg-blue-500/20 text-blue-400 px-3 py-1 text-xs font-medium cursor-pointer">Live</button>
                <button className="bg-transparent hover:bg-muted/50 text-muted-foreground px-3 py-1 text-xs font-medium transition-colors cursor-pointer">Pause</button>
              </div>
              <button className="p-1.5 text-muted-foreground hover:text-foreground hover:bg-muted/50 rounded transition-colors cursor-pointer">
                <Maximize2 size={14} />
              </button>
            </div>
          </div>
          
          <div className="flex-1 w-full relative bg-[#0a0a0f]/50 rounded-lg border border-[#1f1f28] overflow-hidden flex items-end px-2">
            <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[80%] h-[80%] bg-blue-500/10 blur-[100px] rounded-full pointer-events-none" />
            
            <div className="absolute inset-0 pt-4 px-2 pb-6">
              <svg viewBox="0 0 1000 300" className="w-full h-full preserve-3d" preserveAspectRatio="none">
                <defs>
                  <linearGradient id="chartGradient" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="rgba(59, 130, 246, 0.25)" />
                    <stop offset="100%" stopColor="rgba(59, 130, 246, 0)" />
                  </linearGradient>
                </defs>
                <path 
                  d="M0,150 Q50,130 100,160 T200,120 T300,140 T400,110 T500,130 T600,160 T700,140 T800,170 T900,150 T1000,140" 
                  fill="none" 
                  stroke="#3b82f6" 
                  strokeWidth="3" 
                  strokeLinecap="round" 
                  className="drop-shadow-[0_0_8px_rgba(59,130,246,0.6)]"
                />
                <path 
                  d="M0,300 L0,150 Q50,130 100,160 T200,120 T300,140 T400,110 T500,130 T600,160 T700,140 T800,170 T900,150 T1000,140 L1000,300 Z" 
                  fill="url(#chartGradient)" 
                />
              </svg>
            </div>
          </div>
        </div>

        {/* Device Status */}
        <div className="col-span-12 lg:col-span-4 bg-[#12121a] border border-[#1f1f28] rounded-xl p-5 flex flex-col shadow-sm relative group">
          <DebugOverlay 
            title="Device Status" 
            data={device} 
            correlationId={`corr-dev-${device.id}`}
            repository="DataSourceResolver"
          />
          <h3 className="text-sm font-semibold text-foreground mb-4">Device Status</h3>
          <div className="flex flex-col gap-3 flex-1 justify-center">
            {statusProperties.map((prop, idx) => (
              <div key={idx} className="flex items-center justify-between border-b border-[#1f1f28]/50 pb-3 last:border-0 last:pb-0">
                <span className="text-xs text-muted-foreground">{prop.label}</span>
                <span className={`text-xs font-mono font-medium tracking-wide ${prop.isSuccess ? 'text-[#22c55e]' : prop.unavailable ? 'text-muted-foreground' : 'text-foreground/90'}`}>
                  {prop.value}
                </span>
              </div>
            ))}
          </div>
        </div>

      </section>

      {/* ROW 3: ACTIONS & ACTIVITY */}
      <section className="grid grid-cols-12 gap-6 pb-6">
        
        {/* Quick Actions Grid */}
        <div className="col-span-12 lg:col-span-8 bg-[#12121a] border border-[#1f1f28] rounded-xl p-5 flex flex-col shadow-sm">
          <h3 className="text-sm font-semibold text-foreground mb-4">Quick Actions</h3>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4 flex-1">
            {actions.map((act, i) => (
              <button
                key={i}
                data-testid={`quick-action-${act.label}`}
                onClick={() => handleQuickAction(act.label)}
                className="bg-[#0a0a0f]/50 border border-[#1f1f28] rounded-xl p-4 flex flex-col items-center justify-center gap-3 transition-all group shadow-sm hover:border-blue-500/40 hover:bg-blue-500/5 hover:shadow-[0_0_15px_rgba(59,130,246,0.1)] cursor-pointer"
              >
                <act.icon size={20} className="text-blue-500/80 group-hover:text-blue-400 group-hover:scale-110 transition-all" />
                <span className="text-[11px] font-medium text-muted-foreground group-hover:text-foreground text-center">
                  {act.label}
                </span>
              </button>
            ))}
          </div>
          {actionState && (
            <div data-testid="quick-action-result" className="mt-4 text-xs text-muted-foreground border-t border-[#1f1f28] pt-3">
              <span className="font-medium text-foreground/80">{actionState.label}:</span> {actionState.message}
            </div>
          )}
        </div>

        {/* Recent Activity Timeline */}
        <div className="col-span-12 lg:col-span-4 bg-[#12121a] border border-[#1f1f28] rounded-xl flex flex-col shadow-sm min-h-[300px]">
          <div className="p-5 pb-0 border-b-0 shrink-0">
            <h3 className="text-sm font-semibold text-foreground">Recent Activity</h3>
          </div>
          <div className="flex-1 overflow-hidden relative flex flex-col mt-4">
            <div className="absolute inset-0 overflow-y-auto custom-scrollbar px-5">
              {events.length > 0 ? (
                <div className="relative before:absolute before:inset-0 before:ml-[11px] before:-translate-x-px before:h-full before:w-[2px] before:bg-gradient-to-b before:from-[#1f1f28] before:to-transparent space-y-6">
                  {events.map((ev, idx) => (
                    <div key={idx} className="relative flex gap-4 is-active group cursor-default">
                      <div className={`w-6 h-6 rounded-full bg-[#12121a] border-[3px] border-[#0a0a0f] flex items-center justify-center shrink-0 z-10 ${
                        ev.type.includes('Alert') ? 'text-red-500' :
                        ev.type.includes('Rule') ? 'text-amber-500' :
                        'text-blue-500'
                      }`}>
                        {ev.type.includes('Alert') ? <AlertTriangle size={10} className="fill-current" /> : <Circle size={8} className="fill-current" />}
                      </div>
                      <div className="flex-1 pt-0.5">
                        <div className="flex items-center gap-2 mb-1">
                          <span className="text-[10px] font-mono text-muted-foreground font-bold">
                            {new Date(ev.timestamp).toLocaleTimeString()}
                          </span>
                        </div>
                        <p className="text-xs font-semibold text-foreground">{ev.type}</p>
                        <p className="text-[11px] text-muted-foreground mt-1">{ev.message}</p>
                      </div>
                    </div>
                  ))}
                </div>
              ) : (
                <div className="h-full flex items-center justify-center text-xs text-muted-foreground">
                  Pipeline listening...
                </div>
              )}
            </div>
          </div>
        </div>

      </section>

    </div>
  );
}
