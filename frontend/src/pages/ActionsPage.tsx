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
      const targetDeviceId = a.parameters?.deviceId || '';
      const matchSearch = (a.actionType || '').toLowerCase().includes(search.toLowerCase()) ||
                          targetDeviceId.toLowerCase().includes(search.toLowerCase());
      const matchStatus = filterStatus === 'ALL' || a.status === filterStatus;
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

  // Real ExecutionStatus values from the backend (SUCCESS/FAILURE/IGNORED/UNSUPPORTED) -
  // not the demo-repository's ACTION_STARTED/ACTION_FAILED event-type strings.
  const getActionStatus = (status: string) => {
    switch (status) {
      case 'SUCCESS': return { label: 'SUCCESS', icon: CheckCircle2, color: 'text-success', bg: 'bg-success/10' };
      case 'FAILURE': return { label: 'FAILED', icon: XCircle, color: 'text-destructive', bg: 'bg-destructive/10' };
      case 'UNSUPPORTED': return { label: 'UNSUPPORTED', icon: XCircle, color: 'text-warning', bg: 'bg-warning/10' };
      case 'IGNORED': return { label: 'IGNORED', icon: RotateCw, color: 'text-muted-foreground', bg: 'bg-muted/10' };
      default: return { label: status || 'UNKNOWN', icon: RotateCw, color: 'text-muted-foreground', bg: 'bg-muted/10' };
    }
  };

  const totalLoaded = globalActions.length;
  const successCount = globalActions.filter((a: any) => a.status === 'SUCCESS').length;
  const failedCount = globalActions.filter((a: any) => a.status === 'FAILURE').length;
  const unsupportedCount = globalActions.filter((a: any) => a.status === 'UNSUPPORTED' || a.status === 'IGNORED').length;
  const successRate = totalLoaded > 0 ? ((successCount / totalLoaded) * 100).toFixed(1) : '—';

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
            <option value="FAILURE">Failed</option>
            <option value="UNSUPPORTED">Unsupported</option>
            <option value="IGNORED">Ignored</option>
          </select>
          
          <button className="flex items-center gap-2 px-3 py-1.5 bg-[#13131a] border border-[#1f1f28] rounded-md text-sm text-muted-foreground hover:text-foreground">
            <Filter size={14} /> Advanced
          </button>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto custom-scrollbar p-6 space-y-6">
        
        {/* KPI CARDS (real counts over the currently-loaded page of action history) */}
        <div className="grid grid-cols-4 gap-4">
          <div className="bg-card/50 backdrop-blur border border-border/50 rounded-xl p-5">
            <p className="text-xs text-muted-foreground font-medium uppercase tracking-wider mb-2">Total Loaded</p>
            <div className="flex items-center justify-between">
              <span className="text-2xl font-bold font-mono">{totalLoaded}</span>
              <Activity size={18} className="text-blue-400" />
            </div>
          </div>

          <div className="bg-card/50 backdrop-blur border border-border/50 rounded-xl p-5">
            <p className="text-xs text-muted-foreground font-medium uppercase tracking-wider mb-2">Success Rate</p>
            <div className="flex items-center justify-between">
              <span className="text-2xl font-bold font-mono text-success">{successRate}{successRate !== '—' ? '%' : ''}</span>
              <CheckCircle2 size={18} className="text-success" />
            </div>
          </div>

          <div className="bg-card/50 backdrop-blur border border-border/50 rounded-xl p-5">
            <p className="text-xs text-muted-foreground font-medium uppercase tracking-wider mb-2">Failed</p>
            <div className="flex items-center justify-between">
              <span className="text-2xl font-bold font-mono text-destructive">{failedCount}</span>
              <XCircle size={18} className="text-destructive" />
            </div>
          </div>

          <div className="bg-card/50 backdrop-blur border border-border/50 rounded-xl p-5">
            <p className="text-xs text-muted-foreground font-medium uppercase tracking-wider mb-2">Unsupported / Ignored</p>
            <div className="flex items-center justify-between">
              <span className="text-2xl font-bold font-mono text-warning">{unsupportedCount}</span>
              <RotateCw size={18} className="text-warning" />
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
                  key: 'actionType',
                  header: 'Action / Route',
                  sortable: true,
                  cell: (row) => {
                    const status = getActionStatus(row.status);
                    return (
                      <div className="flex items-center gap-3">
                        <div className={`p-1.5 rounded-md ${status.bg} border border-[#1f1f28]`}>
                          <status.icon size={12} className={status.color} />
                        </div>
                        <div className="font-semibold text-sm hover:text-blue-400 transition-colors">{row.actionType}</div>
                      </div>
                    );
                  }
                },
                {
                  key: 'target',
                  header: 'Target Node',
                  sortable: false,
                  cell: (row) => (
                    <div className="flex items-center gap-2 text-sm text-muted-foreground">
                      <Server size={14} />
                      {row.parameters?.deviceId || 'System'}
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
                    </div>
                  )
                },
                {
                  key: 'status',
                  header: 'Status',
                  sortable: true,
                  cell: (row) => {
                    const status = getActionStatus(row.status);
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
              onRowClick={(row) => {
                const status = getActionStatus(row.status);
                const target = row.parameters?.deviceId || 'System';
                return openInspector({
                  id: row.executionId,
                  type: 'Action',
                  title: row.actionType,
                  subtitle: `Target: ${target}`,
                  status: status.label,
                  statusColor: status.color === 'text-success' ? 'success' : status.color === 'text-destructive' ? 'destructive' : 'warning',
                  properties: {
                    'Request ID': row.requestId || 'N/A',
                    'Rule Match ID': row.matchId || 'N/A',
                    'Target Device': target,
                    'Execution Time': new Date(row.timestamp).toLocaleString(),
                    'Status': status.label,
                    'Message': row.message || 'N/A'
                  },
                  history: [
                    { time: new Date(row.timestamp).toLocaleString(), action: status.label, details: row.message || 'No message returned by the executor.' }
                  ],
                  rawJson: row,
                  correlationId: undefined
                });
              }}
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
              <div className="flex flex-col items-center justify-center py-8 text-muted-foreground opacity-50">
                <Clock size={32} className="mb-2" />
                <p className="text-xs">Action scheduling is not yet implemented</p>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
