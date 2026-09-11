import { useState, useEffect } from 'react';
import { Play, Maximize2, RefreshCw, Download, FileJson, ArrowRight, Circle, AlertTriangle, TestTube, MoreHorizontal } from 'lucide-react';
import { useDevice } from '../../../../hooks/queries';
import { SkeletonCard, SkeletonChart, SkeletonTimeline } from '../../../ui/Skeletons';
import { ScenarioEngine } from '../../../../simulation/ScenarioEngine';
import { DebugOverlay } from '../DebugOverlay';
import { FEATURES } from '../../../../config/features';

// These quick actions have no real backend implementation yet - they only
// ever did anything via the demo ScenarioEngine. In live mode they must be
// visibly unavailable rather than silently no-op or fabricate demo events.
const LIVE_MODE_UNAVAILABLE_ACTIONS = new Set(['Refresh Twin', 'Download Telemetry', 'Test Connection']);

export function OverviewTab({ deviceId }: { deviceId: string }) {
  const { data: device, isLoading } = useDevice(deviceId);
  const [events, setEvents] = useState<any[]>([]);

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

  // 3. Quick Actions
  // Refresh Twin / Test Connection / Download Telemetry have no real backend
  // implementation - only ever simulated via the demo ScenarioEngine. This
  // must never fire in live mode (see LIVE_MODE_UNAVAILABLE_ACTIONS below,
  // which also disables the buttons in the UI).
  const handleQuickAction = (label: string) => {
    if (!FEATURES.DEMO_MODE) return;

    if (label === 'Refresh Twin') {
      ScenarioEngine.setScenario('Normal Operations');
      // Hack to emit an event
      // @ts-ignore
      ScenarioEngine.emitEvent('Action Executed', 'Digital Twin refreshed manually', deviceId);
    } else if (label === 'Test Connection') {
      // @ts-ignore
      ScenarioEngine.emitEvent('Diagnostic', 'Ping successful. Latency 24ms', deviceId);
    } else if (label === 'Download Telemetry') {
      // @ts-ignore
      ScenarioEngine.emitEvent('Data Export', 'Telemetry downloaded as CSV', deviceId);
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

  return (
    <div className="p-6 h-full flex flex-col space-y-6 animate-in slide-in-from-bottom-2 fade-in duration-300 max-w-[1600px] mx-auto overflow-y-auto custom-scrollbar">
      
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
            {actions.map((act, i) => {
              const isUnavailableInLiveMode = !FEATURES.DEMO_MODE && LIVE_MODE_UNAVAILABLE_ACTIONS.has(act.label);
              return (
                <button
                  key={i}
                  onClick={() => handleQuickAction(act.label)}
                  disabled={isUnavailableInLiveMode}
                  title={isUnavailableInLiveMode ? 'Not available in live mode' : undefined}
                  className={`bg-[#0a0a0f]/50 border rounded-xl p-4 flex flex-col items-center justify-center gap-3 transition-all group shadow-sm ${
                    isUnavailableInLiveMode
                      ? 'border-[#1f1f28] opacity-40 cursor-not-allowed'
                      : 'border-[#1f1f28] hover:border-blue-500/40 hover:bg-blue-500/5 hover:shadow-[0_0_15px_rgba(59,130,246,0.1)] cursor-pointer'
                  }`}
                >
                  <act.icon size={20} className={isUnavailableInLiveMode ? 'text-muted-foreground/50' : 'text-blue-500/80 group-hover:text-blue-400 group-hover:scale-110 transition-all'} />
                  <span className="text-[11px] font-medium text-muted-foreground group-hover:text-foreground text-center">
                    {act.label}
                  </span>
                  {isUnavailableInLiveMode && (
                    <span className="text-[9px] text-muted-foreground/70 -mt-1">Not available in live mode</span>
                  )}
                </button>
              );
            })}
          </div>
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
