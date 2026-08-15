import { useSites, useActiveDevices, useEvents, useActionHistory } from '../hooks/queries';
import { StatCard } from '../components/ui/StatCard';
import { PlatformHealthWidget } from '../components/widgets/PlatformHealthWidget';
import { LiveAreaChart } from '../components/ui/LiveAreaChart';
import { ActivityTimeline } from '../components/ui/ActivityTimeline';
import { Server, Activity, AlertTriangle, Box, RefreshCw } from 'lucide-react';
import { DeviceStatusBadge } from '../components/DeviceStatusBadge';
import { useConnectionStatus } from '../services/ConnectionManager';
import { useMemo } from 'react';
import { useQuery } from '@tanstack/react-query';

export default function HomePage() {
  const { data: sites } = useSites();
  const { data: devices } = useActiveDevices();
  const { data: events = [] } = useEvents(25);
  const { data: actions = [] } = useActionHistory(25);
  const connection = useConnectionStatus();

  const { data: globalTelemetry = [] } = useQuery<any[]>({
    queryKey: ['telemetry', 100],
    queryFn: () => [], // default empty, hydrated by realtime
    initialData: [],
  });

  const chartData = useMemo(() => {
    return globalTelemetry
      .filter(t => t && t.payload)
      .map(t => {
        const metricKey = Object.keys(t.payload)[0] || 'value';
        return {
          time: new Date(t.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }),
          value: Number(t.payload[metricKey]) || 0,
          timestamp: t.timestamp
        };
      })
      .sort((a, b) => a.timestamp - b.timestamp);
  }, [globalTelemetry]);

  const liveActivity = useMemo(() => {
    const combined = [
      ...events.map((e: any) => ({
        id: e.id, time: new Date(e.timestamp).toLocaleTimeString(), title: e.type,
        description: e.message || `${e.severity} event from ${e.deviceId}`, icon: AlertTriangle, type: 'rule' as const,
        timestamp: e.timestamp
      })),
      ...actions.map((a: any) => ({
        id: a.id, time: new Date(a.timestamp).toLocaleTimeString(), title: a.actionType,
        description: a.message || 'Action executed', icon: Activity, type: 'action' as const,
        timestamp: a.timestamp
      }))
    ];
    return combined.sort((a, b) => new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime()).slice(0, 50);
  }, [events, actions]);

  const siteCount = sites?.length || 0;
  const buildingCount = siteCount * 2; 
  const spaceCount = buildingCount * 4;
  const deviceCount = devices?.length || 0;
  
  const mockEvents = [
    { id: '1', time: '12:05 PM', title: 'High CPU utilization', description: 'Rules engine server at 95%', icon: AlertTriangle, type: 'rule' as const },
    { id: '2', time: '11:15 AM', title: 'Configuration synced', description: 'Updated edge policies applied', icon: Activity, type: 'action' as const },
    { id: '3', time: '10:30 AM', title: 'New device provisioned', description: 'Sensor GS-01 added to site', icon: Server, type: 'telemetry' as const },
  ];

  return (
    <div className="flex flex-col gap-6 animate-in h-full overflow-y-auto p-4 sm:p-6 lg:p-8">
      {/* Header */}
      <div className="flex items-center justify-between shrink-0">
        <div>
          <h1 className="text-2xl font-bold text-white tracking-tight">Mission Control</h1>
          <p className="text-muted-foreground text-sm mt-1">Global platform visibility and health.</p>
        </div>
        <button className="flex items-center gap-2 bg-[#12121a] hover:bg-[#1a1a24] border border-[#1f1f28] px-3 py-1.5 rounded-md text-[11px] font-medium transition-colors text-foreground">
          <RefreshCw size={14} className="text-muted-foreground" />
          Refresh Dashboards
        </button>
      </div>

      {/* Platform Health */}
      <section>
        <PlatformHealthWidget />
      </section>

      {/* KPI Stats */}
      <section className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-4 xl:grid-cols-6 gap-4">
        <StatCard title="Sites" value={siteCount} subtitle="Total registered sites" />
        <StatCard title="Buildings" value={buildingCount} subtitle="Monitored structures" />
        <StatCard title="Spaces" value={spaceCount} subtitle="Active zones" />
        <StatCard title="Total Devices" value={deviceCount} subtitle="Provisioned sensors" />
        <StatCard title="Throughput" value={connection.messagesPerSec} subtitle="Live packets / sec" />
        <StatCard title="Offline" value={0} subtitle="Require maintenance" />
      </section>

      {/* Main Content Grid - 12 Column Grid */}
      <div className="grid grid-cols-1 xl:grid-cols-12 gap-6 flex-1 min-h-[400px]">
        {/* Left Column (8 columns): Chart */}
        <div className="xl:col-span-8 flex flex-col gap-6">
          <LiveAreaChart title="Global Telemetry Ingestion" data={chartData} />
          
          {/* Mock Active Twins */}
          <div className="bg-[#12121a] border border-[#1f1f28] rounded-xl p-5 shadow-sm">
            <h3 className="text-sm font-semibold text-foreground mb-4">Active Digital Twins</h3>
            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              {(devices ?? []).slice(0, 4).map(device => (
                <div key={device.id} className="flex items-center gap-4 p-3 rounded-lg bg-[#0a0a0f] border border-[#1f1f28] transition-all hover:border-blue-400 hover:shadow-[0_0_16px_rgba(59,130,246,.15)]">
                  <div className="w-10 h-10 rounded bg-[#12121a] border border-[#1f1f28] flex items-center justify-center">
                    <Box size={20} className="text-blue-400" />
                  </div>
                  <div className="flex-1 flex flex-col">
                    <span className="text-sm font-semibold text-foreground">{device.name}</span>
                    <span className="text-xs text-muted-foreground">ID: {device.id}</span>
                  </div>
                  <DeviceStatusBadge status={device.status} />
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* Right Column (4 columns): Event Feed */}
        <div className="xl:col-span-4 flex flex-col">
          <ActivityTimeline title="Global System Activity" events={[...liveActivity, ...mockEvents].slice(0, 50)} />
        </div>
      </div>
    </div>
  );
}
