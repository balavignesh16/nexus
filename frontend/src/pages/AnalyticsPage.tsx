import { useState } from 'react';
import { useRepositories } from '../repositories/RepositoryProvider';
import { usePolling } from '../hooks/usePolling';
import { TrendingUp, Server, Zap, CheckCircle2, Battery, Download, Calendar } from 'lucide-react';
import { Skeleton } from '../components/ui/Skeleton';
import { AreaChart, Area, BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, PieChart, Pie, Cell } from 'recharts';
import { ResponsiveGridLayout } from 'react-grid-layout';
import 'react-grid-layout/css/styles.css';
import 'react-resizable/css/styles.css';

const COLORS = ['#3b82f6', '#8b5cf6', '#10b981', '#f59e0b', '#ef4444'];

export default function AnalyticsPage() {
  const { device } = useRepositories();
  const { data: devices = [], isLoading } = usePolling(
    ['all-devices'],
    () => device.getDevices(),
    { interval: 10000 }
  );

  const defaultLayouts = {
    lg: [
      { i: 'kpi-sla', x: 0, y: 0, w: 3, h: 2, static: true },
      { i: 'kpi-devices', x: 3, y: 0, w: 3, h: 2, static: true },
      { i: 'kpi-battery', x: 6, y: 0, w: 3, h: 2, static: true },
      { i: 'kpi-latency', x: 9, y: 0, w: 3, h: 2, static: true },
      { i: 'chart-volume', x: 0, y: 2, w: 8, h: 4 },
      { i: 'chart-types', x: 8, y: 2, w: 4, h: 4 },
      { i: 'chart-comparison', x: 0, y: 6, w: 12, h: 4 }
    ]
  };

  const [layouts, setLayouts] = useState(() => {
    const saved = localStorage.getItem('nexus-analytics-layout');
    if (saved) {
      try {
        return JSON.parse(saved);
      } catch (e) {
        return defaultLayouts;
      }
    }
    return defaultLayouts;
  });

  const onLayoutChange = (_layout: any, allLayouts: any) => {
    setLayouts(allLayouts);
    localStorage.setItem('nexus-analytics-layout', JSON.stringify(allLayouts));
  };

  if (isLoading && devices.length === 0) {
    return (
      <div className="p-8 h-full">
        <Skeleton className="w-full h-full rounded-xl" />
      </div>
    );
  }

  // Mock data for analytics
  const telemetryVolume = [
    { time: '00:00', volume: 4000 }, { time: '04:00', volume: 3000 },
    { time: '08:00', volume: 6000 }, { time: '12:00', volume: 8000 },
    { time: '16:00', volume: 7500 }, { time: '20:00', volume: 5000 }
  ];

  const siteComparison = [
    { name: 'Site Alpha', uptime: 99.9, incidents: 2 },
    { name: 'Site Beta', uptime: 99.5, incidents: 5 },
    { name: 'Site Gamma', uptime: 98.2, incidents: 12 },
    { name: 'Site Delta', uptime: 100, incidents: 0 }
  ];

  const deviceTypes = [
    { name: 'HVAC', value: 45 },
    { name: 'Lighting', value: 25 },
    { name: 'Security', value: 20 },
    { name: 'Sensors', value: 10 }
  ];

  return (
    <div className="flex-1 flex flex-col h-full bg-[#060b14] overflow-hidden text-foreground animate-in fade-in duration-300">
      
      {/* TOOLBAR */}
      <div className="shrink-0 h-16 border-b border-[#1f1f28] flex items-center justify-between px-6 bg-[#0a0a0f] z-10 relative">
        <div className="flex items-center gap-3">
          <div className="p-2 bg-blue-500/10 rounded-lg">
            <TrendingUp size={20} className="text-blue-400" />
          </div>
          <div>
            <h1 className="text-lg font-bold">Executive Analytics</h1>
            <p className="text-xs text-muted-foreground">Platform-wide KPI dashboard</p>
          </div>
        </div>

        <div className="flex items-center gap-4">
          <button className="flex items-center gap-2 px-3 py-1.5 bg-[#13131a] border border-[#1f1f28] rounded-md text-sm text-muted-foreground hover:text-foreground">
            <Calendar size={14} /> Last 30 Days
          </button>
          
          <button className="flex items-center gap-2 px-3 py-1.5 bg-blue-600 hover:bg-blue-700 text-white rounded-md text-sm font-medium transition-colors">
            <Download size={14} /> Export Report
          </button>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto custom-scrollbar p-6">
        <ResponsiveGridLayout
          className="layout"
          layouts={layouts}
          breakpoints={{ lg: 1200, md: 996, sm: 768, xs: 480, xxs: 0 }}
          cols={{ lg: 12, md: 10, sm: 6, xs: 4, xxs: 2 }}
          rowHeight={60}
          onLayoutChange={onLayoutChange}
          // @ts-ignore - react-grid-layout types are incomplete in this version
          draggableHandle=".drag-handle"
          margin={[16, 16]}
        >
          {/* KPI 1 */}
          <div key="kpi-sla" className="bg-card/50 backdrop-blur border border-border/50 rounded-xl p-5 flex flex-col justify-between group">
            <div className="flex justify-between items-start mb-2 drag-handle cursor-move">
              <p className="text-xs text-muted-foreground font-medium uppercase tracking-wider">Platform SLA</p>
              <CheckCircle2 size={16} className="text-success group-hover:scale-110 transition-transform" />
            </div>
            <div>
              <span className="text-3xl font-bold font-mono">99.98%</span>
              <p className="text-xs text-success mt-2 flex items-center gap-1">↑ +0.02% vs last month</p>
            </div>
          </div>
          
          {/* KPI 2 */}
          <div key="kpi-devices" className="bg-card/50 backdrop-blur border border-border/50 rounded-xl p-5 flex flex-col justify-between group">
            <div className="flex justify-between items-start mb-2 drag-handle cursor-move">
              <p className="text-xs text-muted-foreground font-medium uppercase tracking-wider">Total Active Devices</p>
              <Server size={16} className="text-blue-400 group-hover:scale-110 transition-transform" />
            </div>
            <div>
              <span className="text-3xl font-bold font-mono">{devices.length || 1248}</span>
              <p className="text-xs text-success mt-2 flex items-center gap-1">↑ 12 new devices</p>
            </div>
          </div>
          
          {/* KPI 3 */}
          <div key="kpi-battery" className="bg-card/50 backdrop-blur border border-border/50 rounded-xl p-5 flex flex-col justify-between group">
            <div className="flex justify-between items-start mb-2 drag-handle cursor-move">
              <p className="text-xs text-muted-foreground font-medium uppercase tracking-wider">Avg Battery Forecast</p>
              <Battery size={16} className="text-success group-hover:scale-110 transition-transform" />
            </div>
            <div>
              <span className="text-3xl font-bold font-mono">84%</span>
              <p className="text-xs text-muted-foreground mt-2">Estimated 14 months remaining</p>
            </div>
          </div>
          
          {/* KPI 4 */}
          <div key="kpi-latency" className="bg-card/50 backdrop-blur border border-border/50 rounded-xl p-5 flex flex-col justify-between group">
            <div className="flex justify-between items-start mb-2 drag-handle cursor-move">
              <p className="text-xs text-muted-foreground font-medium uppercase tracking-wider">Edge Latency</p>
              <Zap size={16} className="text-purple-400 group-hover:scale-110 transition-transform" />
            </div>
            <div>
              <span className="text-3xl font-bold font-mono">42ms</span>
              <p className="text-xs text-muted-foreground mt-2">Global average</p>
            </div>
          </div>

          {/* Chart 1 */}
          <div key="chart-volume" className="bg-card/50 backdrop-blur border border-border/50 rounded-xl p-5 flex flex-col overflow-hidden">
            <h3 className="text-sm font-semibold mb-6 drag-handle cursor-move shrink-0">Telemetry Ingestion Volume</h3>
            <div className="flex-1 min-h-0">
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={telemetryVolume} margin={{ top: 0, right: 0, left: -20, bottom: 0 }}>
                  <defs>
                    <linearGradient id="colorVolume" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="#3b82f6" stopOpacity={0.3}/>
                      <stop offset="95%" stopColor="#3b82f6" stopOpacity={0}/>
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="3 3" stroke="#1f1f28" vertical={false} />
                  <XAxis dataKey="time" stroke="#94a3b8" fontSize={12} tickLine={false} axisLine={false} />
                  <YAxis stroke="#94a3b8" fontSize={12} tickLine={false} axisLine={false} tickFormatter={(val) => `${val/1000}k`} />
                  <Tooltip 
                    contentStyle={{ backgroundColor: '#0a0a0f', borderColor: '#1f1f28', borderRadius: '8px' }}
                    itemStyle={{ color: '#fff' }}
                  />
                  <Area type="monotone" dataKey="volume" stroke="#3b82f6" strokeWidth={2} fillOpacity={1} fill="url(#colorVolume)" />
                </AreaChart>
              </ResponsiveContainer>
            </div>
          </div>
          
          {/* Chart 2 */}
          <div key="chart-types" className="bg-card/50 backdrop-blur border border-border/50 rounded-xl p-5 flex flex-col overflow-hidden">
            <h3 className="text-sm font-semibold mb-6 drag-handle cursor-move shrink-0">Device Distribution</h3>
            <div className="flex-1 min-h-0">
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie
                    data={deviceTypes}
                    cx="50%"
                    cy="50%"
                    innerRadius={40}
                    outerRadius={60}
                    paddingAngle={5}
                    dataKey="value"
                    stroke="none"
                  >
                    {deviceTypes.map((_, index) => (
                      <Cell key={`cell-${index}`} fill={COLORS[index % COLORS.length]} />
                    ))}
                  </Pie>
                  <Tooltip 
                    contentStyle={{ backgroundColor: '#0a0a0f', borderColor: '#1f1f28', borderRadius: '8px' }}
                    itemStyle={{ color: '#fff' }}
                  />
                </PieChart>
              </ResponsiveContainer>
            </div>
          </div>

          {/* Chart 3 */}
          <div key="chart-comparison" className="bg-card/50 backdrop-blur border border-border/50 rounded-xl p-5 flex flex-col overflow-hidden">
            <h3 className="text-sm font-semibold mb-6 drag-handle cursor-move shrink-0">Site Performance Comparison</h3>
            <div className="flex-1 min-h-0">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={siteComparison} margin={{ top: 0, right: 0, left: -20, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#1f1f28" vertical={false} />
                  <XAxis dataKey="name" stroke="#94a3b8" fontSize={12} tickLine={false} axisLine={false} />
                  <YAxis yAxisId="left" stroke="#94a3b8" fontSize={12} tickLine={false} axisLine={false} domain={[95, 100]} />
                  <YAxis yAxisId="right" orientation="right" stroke="#94a3b8" fontSize={12} tickLine={false} axisLine={false} />
                  <Tooltip 
                    contentStyle={{ backgroundColor: '#0a0a0f', borderColor: '#1f1f28', borderRadius: '8px' }}
                    cursor={{ fill: '#1f1f28', opacity: 0.4 }}
                  />
                  <Bar yAxisId="left" dataKey="uptime" fill="#10b981" radius={[4, 4, 0, 0]} name="Uptime %" />
                  <Bar yAxisId="right" dataKey="incidents" fill="#ef4444" radius={[4, 4, 0, 0]} name="Incidents" />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </div>
          
        </ResponsiveGridLayout>
      </div>
    </div>
  );
}
