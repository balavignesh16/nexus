import { useState, useMemo } from 'react';
import { useRepositories } from '../repositories/RepositoryProvider';
import { usePolling } from '../hooks/usePolling';
import { Play, Search, Filter, RotateCw, CheckCircle2, XCircle, Clock, Server, ArrowRight, Activity } from 'lucide-react';
import { Skeleton } from '../components/ui/Skeleton';
import { DataTable } from '../components/ui/DataTable';
import { useInspector } from '../components/ui/InspectorProvider';

export default function ActionsPage() {
  const { actions } = useRepositories();
  const { data: globalActions = [], isLoading } = usePolling(
    ['global-actions-history'],
    () => actions.getActions(undefined, 100),
    { interval: 3000 }
  );

  const [search, setSearch] = useState('');
  const [filterStatus, setFilterStatus] = useState('ALL');
  const { openInspector } = useInspector();

  const filteredActions = useMemo(() => {
    return [...globalActions].filter((a: any) => {
      const matchSearch = (a.type || '').toLowerCase().includes(search.toLowerCase()) || 
                          (a.deviceId || '').toLowerCase().includes(search.toLowerCase());
      const status = a.type === 'ACTION_FAILED' ? 'FAILED' : a.type === 'ACTION_STARTED' ? 'PENDING' : 'SUCCESS';
      const matchStatus = filterStatus === 'ALL' || status === filterStatus;
      return matchSearch && matchStatus;
    });
  }, [globalActions, search, filterStatus]);

  if (isLoading && globalActions.length === 0) {
    return (
      <div className="p-8 h-full flex flex-col gap-4">
        <Skeleton className="w-1/3 h-10 rounded" />
        <Skeleton className="w-full h-full rounded-xl" />
      </div>
    );
  }

  const getActionStatus = (type: string) => {
    switch (type) {
      case 'ACTION_FAILED': return { label: 'FAILED', icon: XCircle, color: 'text-destructive', bg: 'bg-destructive/10' };
      case 'ACTION_STARTED': return { label: 'PENDING', icon: RotateCw, color: 'text-warning', bg: 'bg-warning/10' };
      default: return { label: 'SUCCESS', icon: CheckCircle2, color: 'text-success', bg: 'bg-success/10' };
    }
  };

  return (
    <div className="flex-1 flex flex-col h-full bg-[#060b14] overflow-hidden text-foreground animate-in fade-in duration-300">
      
      {/* TOOLBAR */}
      <div className="shrink-0 h-16 border-b border-[#1f1f28] flex items-center justify-between px-6 bg-[#0a0a0f]">
        <div className="flex items-center gap-3">
          <div className="p-2 bg-purple-500/10 rounded-lg">
            <Play size={20} className="text-purple-400" />
          </div>
          <div>
            <h1 className="text-lg font-bold">Action Center</h1>
            <p className="text-xs text-muted-foreground">Execution tracking & retries</p>
          </div>
        </div>

        <div className="flex items-center gap-4">
          <div className="relative">
            <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground" />
            <input 
              type="text" 
              placeholder="Search actions..."
              value={search}
              onChange={e => setSearch(e.target.value)}
              className="pl-9 pr-4 py-1.5 bg-[#13131a] border border-[#1f1f28] rounded-md text-sm focus:outline-none focus:border-blue-500 transition-colors w-64"
            />
          </div>
          
          <select 
            className="bg-[#13131a] border border-[#1f1f28] rounded-md text-sm px-3 py-1.5 focus:outline-none focus:border-blue-500"
            value={filterStatus}
            onChange={e => setFilterStatus(e.target.value)}
          >
            <option value="ALL">All Statuses</option>
            <option value="SUCCESS">Success</option>
            <option value="PENDING">Pending</option>
            <option value="FAILED">Failed</option>
          </select>
          
          <button className="flex items-center gap-2 px-3 py-1.5 bg-[#13131a] border border-[#1f1f28] rounded-md text-sm text-muted-foreground hover:text-foreground">
            <Filter size={14} /> Advanced
          </button>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto custom-scrollbar p-6 space-y-6">
        
        {/* KPI CARDS */}
        <div className="grid grid-cols-5 gap-4">
          <div className="bg-card/50 backdrop-blur border border-border/50 rounded-xl p-5">
            <p className="text-xs text-muted-foreground font-medium uppercase tracking-wider mb-2">Total Executed (24h)</p>
            <div className="flex items-center justify-between">
              <span className="text-2xl font-bold font-mono">14,203</span>
              <Activity size={18} className="text-blue-400" />
            </div>
          </div>
          
          <div className="bg-card/50 backdrop-blur border border-border/50 rounded-xl p-5">
            <p className="text-xs text-muted-foreground font-medium uppercase tracking-wider mb-2">Success Rate</p>
            <div className="flex items-center justify-between">
              <span className="text-2xl font-bold font-mono text-success">99.8%</span>
              <CheckCircle2 size={18} className="text-success" />
            </div>
          </div>
          
          <div className="bg-card/50 backdrop-blur border border-border/50 rounded-xl p-5">
            <p className="text-xs text-muted-foreground font-medium uppercase tracking-wider mb-2">Failed Actions</p>
            <div className="flex items-center justify-between">
              <span className="text-2xl font-bold font-mono text-destructive">24</span>
              <XCircle size={18} className="text-destructive" />
            </div>
          </div>
          
          <div className="bg-card/50 backdrop-blur border border-border/50 rounded-xl p-5">
            <p className="text-xs text-muted-foreground font-medium uppercase tracking-wider mb-2">In Flight</p>
            <div className="flex items-center justify-between">
              <span className="text-2xl font-bold font-mono text-warning">8</span>
              <RotateCw size={18} className="text-warning animate-spin-slow" />
            </div>
          </div>
          
          <div className="bg-card/50 backdrop-blur border border-border/50 rounded-xl p-5">
            <p className="text-xs text-muted-foreground font-medium uppercase tracking-wider mb-2">Avg Latency</p>
            <div className="flex items-center justify-between">
              <span className="text-2xl font-bold font-mono text-purple-400">142ms</span>
              <Clock size={18} className="text-purple-400" />
            </div>
          </div>
        </div>

        <div className="grid grid-cols-4 gap-6 h-[600px]">
          {/* Actions Table */}
          <div className="col-span-3 h-full">
            <DataTable 
              data={filteredActions}
              columns={[
                {
                  key: 'type',
                  header: 'Action / Route',
                  sortable: true,
                  cell: (row) => {
                    const status = getActionStatus(row.type);
                    return (
                      <div className="flex items-center gap-3">
                        <div className={`p-1.5 rounded-md ${status.bg} border border-[#1f1f28]`}>
                          <status.icon size={12} className={status.color} />
                        </div>
                        <div className="font-semibold text-sm hover:text-blue-400 transition-colors">{row.type}</div>
                      </div>
                    );
                  }
                },
                {
                  key: 'deviceId',
                  header: 'Target Node',
                  sortable: true,
                  cell: (row) => (
                    <div className="flex items-center gap-2 text-sm text-muted-foreground">
                      <Server size={14} />
                      {row.deviceId || 'System'}
                    </div>
                  )
                },
                {
                  key: 'timestamp',
                  header: 'Execution Time',
                  sortable: true,
                  cell: (row) => (
                    <div className="text-xs font-mono text-muted-foreground">
                      {new Date(row.timestamp).toLocaleString()}
                      <div className="text-[10px] mt-0.5 opacity-50">~{Math.floor(Math.random() * 50) + 10}ms</div>
                    </div>
                  )
                },
                {
                  key: 'status',
                  header: 'Status',
                  sortable: true,
                  cell: (row) => {
                    const status = getActionStatus(row.type);
                    return (
                      <span className={`text-[10px] font-bold tracking-wider px-2 py-0.5 rounded-full ${status.bg} ${status.color}`}>
                        {status.label}
                      </span>
                    );
                  }
                },
                {
                  key: 'actions',
                  header: '',
                  width: '50px',
                  cell: (_row) => (
                    <div className="flex justify-end pr-2">
                      <ArrowRight size={14} className="text-muted-foreground opacity-50 group-hover:opacity-100 transition-opacity" />
                    </div>
                  )
                }
              ]}
              onRowClick={(row) => openInspector({
                id: row.id,
                type: 'Action',
                title: row.type,
                subtitle: row.deviceId ? `Target: ${row.deviceId}` : 'System Action',
                status: getActionStatus(row.type).label,
                statusColor: getActionStatus(row.type).color === 'text-success' ? 'success' : getActionStatus(row.type).color === 'text-destructive' ? 'destructive' : 'warning',
                properties: {
                  'Correlation ID': row.correlationId || 'N/A',
                  'Target Device': row.deviceId || 'System',
                  'Execution Time': new Date(row.timestamp).toLocaleString(),
                  'Status': getActionStatus(row.type).label
                },
                history: [
                  { time: new Date(row.timestamp).toLocaleString(), action: 'Dispatched', details: `Sent to executor node` },
                  { time: new Date(row.timestamp).toLocaleString(), action: 'Acknowledged', details: `Received by device` }
                ],
                rawJson: row,
                correlationId: row.correlationId
              })}
              searchable={false}
            />
          </div>

          {/* Side Panel (Future Queue) */}
          <div className="col-span-1 space-y-6">
            <div className="bg-[#0a0a0f] border border-[#1f1f28] rounded-xl p-4">
              <h3 className="text-xs font-bold uppercase tracking-wider text-muted-foreground mb-4 flex items-center gap-2">
                <RotateCw size={14} /> Retry Queue
              </h3>
              <div className="flex flex-col items-center justify-center py-8 text-muted-foreground opacity-50">
                <CheckCircle2 size={32} className="mb-2" />
                <p className="text-xs">Queue is empty</p>
              </div>
            </div>
            
            <div className="bg-[#0a0a0f] border border-[#1f1f28] rounded-xl p-4">
              <h3 className="text-xs font-bold uppercase tracking-wider text-muted-foreground mb-4 flex items-center gap-2">
                <Clock size={14} /> Scheduled
              </h3>
              <div className="space-y-3">
                <div className="p-3 bg-[#13131a] border border-[#1f1f28] rounded-lg cursor-pointer hover:border-blue-500/50 transition-colors">
                  <div className="flex justify-between items-center mb-1">
                    <span className="text-xs font-semibold">Nightly Sync</span>
                    <span className="text-[10px] text-muted-foreground">in 2h 15m</span>
                  </div>
                  <p className="text-[10px] text-muted-foreground">Target: All Gateways</p>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
