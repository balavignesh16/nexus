import { useState, useMemo } from 'react';
import { useRepositories } from '../repositories/RepositoryProvider';
import { usePolling } from '../hooks/usePolling';
import { useConnectionStatus } from '../services/ConnectionManager';
import { Signal, Download, Activity, BarChart2, TrendingUp, TrendingDown } from 'lucide-react';
import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';
import { downloadCsv } from '../utils/csv';
import { GlassPanel } from '../components/foundation/GlassPanel';
import { Card } from '../components/foundation/Card';
import { MetricCard } from '../components/foundation/MetricCard';
import { SectionHeader } from '../components/foundation/SectionHeader';
import { Select } from '../components/foundation/Input';
import { Button } from '../components/foundation/Button';
import { StatusIndicator } from '../components/foundation/StatusIndicator';
import { Table, type TableColumn } from '../components/foundation/Table';
import { EmptyState } from '../components/foundation/EmptyState';
import { LoadingBlock } from '../components/foundation/LoadingState';
import { DataSourceBadge } from '../components/ui/DataSourceBadge';

type ChartPoint = { time: string; value: number; timestamp: number };
type ReadingPoint = { time: string; value: unknown; timestamp: number; unit: string | null; id: string };

// Real bound: these must match the backend's CapabilityType enum names
// exactly (the literal sensorType strings telemetry records carry), never
// the legacy "*_SENSOR" device-type naming - a mismatch here silently
// leaves the chart empty for every device/metric combination.
const METRIC_OPTIONS = [
  { value: 'TEMPERATURE', label: 'Temperature' },
  { value: 'HUMIDITY', label: 'Humidity' },
  { value: 'PRESSURE', label: 'Pressure' },
  { value: 'GAS', label: 'Gas' },
  { value: 'MOTION', label: 'Motion' },
  { value: 'BUTTON', label: 'Button' },
];

// Real bound: the backend telemetry history endpoint this page consumes
// (BackendRepository.getTelemetryHistory) always fetches at most 50
// records - these are the only window sizes that can genuinely reflect
// the available dataset (never imply 200/500/1000/5000 records exist).
const WINDOW_OPTIONS = [10, 25, 50];

export default function TelemetryPage() {
  const { device, telemetry } = useRepositories();
  const connection = useConnectionStatus();
  const { data: devices = [], isLoading: isLoadingDevices } = usePolling(
    ['all-devices'],
    () => device.getDevices(),
    { interval: 10000 }
  );

  const [selectedDevice, setSelectedDevice] = useState<string>('');
  const [selectedMetric, setSelectedMetric] = useState<string>('TEMPERATURE');
  const [windowSize, setWindowSize] = useState(25);

  // Default to first device if none selected
  const activeDeviceId = selectedDevice || (devices.length > 0 ? devices[0].id : null);

  // Unchanged real data pipeline (Step 4): same query key/fn RealtimeRepositorySync
  // already pushes live telemetry into - do not alter either side of this contract.
  const { data: telemetryData = [], isLoading: isLoadingTelemetry, isError: telemetryError } = usePolling(
    ['telemetry-page', activeDeviceId, 'all', 'typed-history'],
    () => activeDeviceId ? telemetry.getTelemetryHistory(activeDeviceId) : Promise.resolve([]),
    // Refetch a bounded authoritative snapshot even with websocket connectivity: live scalar deltas
    // do not contain every typed value/unit and only update their exact legacy metric cache key.
    { interval: 5000, refetchInterval: 5000 }
  );

  const metricOptions = useMemo(() => {
    const keys = [...new Set<string>(telemetryData.flatMap(t=>Object.keys(t.payload??{})))];
    return keys.length ? keys.map(key=>({value:key,label:METRIC_OPTIONS.find(m=>m.value.toLowerCase()===key.toLowerCase())?.label ?? key.replace(/^(demo_|usb_)/,'').replaceAll('_',' ').replace(/\b\w/g,c=>c.toUpperCase())})) : METRIC_OPTIONS;
  },[telemetryData]);
  const activeMetric = metricOptions.find(m=>m.value===selectedMetric)?.value
    ?? metricOptions.find(m=>m.value.toLowerCase()===selectedMetric.toLowerCase())?.value
    ?? metricOptions.find(m=>telemetryData.some(t=>typeof t.payload?.[m.value]==='number'))?.value
    ?? metricOptions[0].value;
  const metricLabel = metricOptions.find(m=>m.value===activeMetric)?.label ?? activeMetric;
  const selectedReadings = useMemo<ReadingPoint[]>(()=>telemetryData
    .filter(t=>t.payload && Object.hasOwn(t.payload,activeMetric))
    .map((t,index)=>{const timestamp=new Date(t.timestamp).getTime();return {id:t.id??`${timestamp}-${index}`,time:new Date(timestamp).toLocaleTimeString(),timestamp,value:t.payload[activeMetric],unit:t.unit??null};})
    .filter(t=>Number.isFinite(t.timestamp)).sort((a,b)=>a.timestamp-b.timestamp),[telemetryData,activeMetric]);
  const selectedUnit=selectedReadings.at(-1)?.unit??null;
  const chartData = useMemo<ChartPoint[]>(() => {
    return selectedReadings.filter(t=>typeof t.value==='number'&&Number.isFinite(t.value)&&t.unit===selectedUnit)
      .map(t=>({time:t.time,value:t.value as number,timestamp:t.timestamp}));
  }, [selectedReadings,selectedUnit]);

  const displayedChartData = useMemo(() => chartData.slice(-windowSize), [chartData, windowSize]);
  const displayedReadings=selectedReadings.slice(-windowSize);
  const activeDevice=devices.find(d=>d.id===activeDeviceId);
  const simulated=activeDevice?.mode==='SIMULATED'||connection.realtimeStatus==='demo';
  const source=simulated?'SIMULATED':activeDevice?.mode==='REAL'?'LIVE':'UNAVAILABLE';

  const stats = useMemo(() => {
    if (displayedChartData.length === 0) return null;
    const values = displayedChartData.map(d => d.value);
    return {
      min: Math.min(...values).toFixed(1),
      max: Math.max(...values).toFixed(1),
      avg: (values.reduce((a, b) => a + b, 0) / values.length).toFixed(1),
      current: values[values.length - 1].toFixed(1),
    };
  }, [displayedChartData]);

  const isLive = connection.realtimeStatus === 'connected' || connection.realtimeStatus === 'demo';

  const exportCsv = () => {
    const rows = displayedReadings.map((d) => [new Date(d.timestamp).toISOString(), activeMetric, typeof d.value==='object'?JSON.stringify(d.value):String(d.value), d.unit??'']);
    downloadCsv(`telemetry-${activeDeviceId}-${activeMetric}-${Date.now()}.csv`, ['timestamp', 'sensorType', 'value', 'unit'], rows);
  };

  const recentReadingsColumns: TableColumn<ReadingPoint>[] = [
    { key: 'timestamp', header: 'Timestamp', render: (d) => <span className="font-mono text-xs text-foundation-muted-fg whitespace-nowrap">{new Date(d.timestamp).toLocaleString()}</span> },
    { key: 'sensor', header: 'Sensor', render: () => <span className="text-xs">{metricLabel}</span> },
    { key: 'value', header: 'Value', align: 'right', render: (d) => <span className="font-mono text-xs font-medium text-foundation-fg">{typeof d.value==='object'?JSON.stringify(d.value):String(d.value)}</span> },
    { key: 'unit', header: 'Unit', render: d=><span className="text-xs">{d.unit??'Unitless'}</span> },
  ];

  const isLoading = isLoadingDevices || (isLoadingTelemetry && chartData.length === 0);

  if (isLoading && devices.length === 0) {
    return (
      <div className="flex flex-col h-full overflow-y-auto p-4 sm:p-6" style={{ backgroundColor: 'var(--foundation-bg)' }}>
        <LoadingBlock className="h-16 mb-4" />
        <LoadingBlock className="flex-1" />
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full overflow-y-auto" style={{ backgroundColor: 'var(--foundation-bg)' }}>
      <div className="flex flex-col gap-6 p-4 sm:p-6 lg:p-8 max-w-[1600px] w-full mx-auto">

        {/* HEADER */}
        <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4">
          <SectionHeader
            icon={<Signal size={18} />}
            title="Telemetry"
            subtitle="Real-time metric visualization"
          />
          <div className="flex flex-wrap items-center gap-3">
            <Select aria-label="Telemetry device" value={activeDeviceId || ''} onChange={(e) => setSelectedDevice(e.target.value)} className="w-64">
              {devices.map(d => <option key={d.id} value={d.id}>{d.name}</option>)}
            </Select>
            <Select aria-label="Telemetry metric" value={activeMetric} onChange={(e) => setSelectedMetric(e.target.value)} className="w-48">
              {metricOptions.map(m => <option key={m.value} value={m.value}>{m.label}</option>)}
            </Select>
            <Select value={windowSize} onChange={(e) => setWindowSize(Number(e.target.value))} className="w-32">
              {WINDOW_OPTIONS.map(size => <option key={size} value={size}>Last {size}</option>)}
            </Select>
            {/* Read-only real status - never a manual pause toggle. usePolling
                already disables its own interval automatically whenever
                realtime is healthy; this pill only describes that real
                state, it does not control it. */}
            <div data-testid="realtime-status">
              <StatusIndicator
                tone={isLive ? 'online' : 'unknown'}
                label={isLive ? 'Live' : 'Polling every 5s'}
                pulse={isLive}
              />
            </div>
          </div>
        </div>

        <div className="text-xs text-foundation-muted-fg flex flex-wrap gap-3 items-center"><DataSourceBadge classification={source} variant="foundation"/><span>Stored readings · exact capability: {activeMetric}{selectedUnit?` · ${selectedUnit}`:''} · refresh every 5s. Connection status does not prove sensor freshness.</span></div>
        {telemetryError&&<p role="alert" className="text-red-500">Could not refresh telemetry. Previously fetched readings may be stale.</p>}

        {/* PRIMARY CHART */}
        <Card
          level="glass"
          padding="lg"
          title={`${metricLabel} Trend${selectedUnit?` (${selectedUnit})`:''}`}
          subtitle={`${displayedChartData.length} of ${chartData.length} fetched point(s)`}
          action={<DataSourceBadge classification={source} variant="foundation" />}
        >
          <div className="h-[340px]">
            {displayedChartData.length > 0 ? (
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={displayedChartData} margin={{ top: 10, right: 10, left: -20, bottom: 0 }}>
                  <defs>
                    <linearGradient id="telemetryColor" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="var(--accent)" stopOpacity={0.35} />
                      <stop offset="95%" stopColor="var(--accent)" stopOpacity={0} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="3 3" stroke="var(--foundation-border)" vertical={false} />
                  <XAxis dataKey="time" stroke="var(--foundation-muted-fg)" fontSize={10} tickMargin={10} minTickGap={30} />
                  <YAxis stroke="var(--foundation-muted-fg)" fontSize={10} tickFormatter={(val) => val.toFixed(0)} />
                  <Tooltip
                    contentStyle={{ backgroundColor: 'var(--foundation-surface)', borderColor: 'var(--foundation-border)', borderRadius: '10px', fontSize: '12px' }}
                    itemStyle={{ color: 'var(--accent)' }}
                    labelStyle={{ color: 'var(--foundation-muted-fg)', marginBottom: '4px' }}
                  />
                  <Area
                    type="monotone"
                    dataKey="value"
                    stroke="var(--accent)"
                    strokeWidth={2}
                    fillOpacity={1}
                    fill="url(#telemetryColor)"
                    isAnimationActive={false}
                  />
                </AreaChart>
              </ResponsiveContainer>
            ) : (
              <EmptyState
                icon={<Activity size={28} />}
                title={selectedReadings.length?'State/text telemetry — see readings below.':'No telemetry data available for this metric.'}
                description={selectedReadings.length?'Non-numeric states are preserved as reported; no numeric chart or average is invented.':'Select a different device or sensor, or wait for new readings to arrive.'}
              />
            )}
          </div>
        </Card>

        {/* KPI CARDS */}
        <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 sm:gap-4">
          <MetricCard label="Current" value={stats?.current ?? '—'} icon={<Activity size={18} />} badge={<DataSourceBadge classification="CALCULATED" variant="foundation" />} />
          <MetricCard label="Average" value={stats?.avg ?? '—'} icon={<BarChart2 size={18} />} badge={<DataSourceBadge classification="CALCULATED" variant="foundation" />} />
          <MetricCard label="Minimum" value={stats?.min ?? '—'} icon={<TrendingDown size={18} />} badge={<DataSourceBadge classification="CALCULATED" variant="foundation" />} />
          <MetricCard label="Maximum" value={stats?.max ?? '—'} icon={<TrendingUp size={18} />} badge={<DataSourceBadge classification="CALCULATED" variant="foundation" />} />
        </div>

        {/* RECENT READINGS + EXPORT */}
        <GlassPanel level="glass" radius="panel-lg" className="overflow-hidden">
          <div className="flex items-center justify-between p-4 border-b border-foundation-border">
            <div>
              <h3 className="text-sm font-semibold text-foundation-fg">Recent Readings</h3>
              <p className="text-xs text-foundation-muted-fg">{displayedReadings.length} record(s) in the current window · at most 50 fetched records across this device's metrics</p>
            </div>
            <Button variant="secondary" size="sm" onClick={exportCsv} disabled={displayedReadings.length === 0}>
              <Download size={14} /> Export CSV
            </Button>
          </div>
          {displayedReadings.length === 0 ? (
            <div className="p-6">
              <EmptyState icon={<Signal size={24} />} title="No readings to display yet." />
            </div>
          ) : (
            <Table
              columns={recentReadingsColumns}
              rows={[...displayedReadings].reverse()}
              rowKey={(d) => d.id}
              className="border-0 rounded-none"
            />
          )}
        </GlassPanel>

      </div>
    </div>
  );
}
