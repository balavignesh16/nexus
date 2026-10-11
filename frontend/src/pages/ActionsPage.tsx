import { useState, useMemo } from 'react';
import { Link } from 'react-router-dom';
import { useRepositories } from '../repositories/RepositoryProvider';
import { usePolling } from '../hooks/usePolling';
import { Play, Search, RotateCw, CheckCircle2, XCircle, Clock, Zap, Sparkles } from 'lucide-react';
import { useInspector } from '../components/ui/InspectorProvider';
import { GlassPanel } from '../components/foundation/GlassPanel';
import { Card } from '../components/foundation/Card';
import { MetricCard } from '../components/foundation/MetricCard';
import { SectionHeader } from '../components/foundation/SectionHeader';
import { Input } from '../components/foundation/Input';
import { Pill } from '../components/foundation/Pill';
import { EmptyState } from '../components/foundation/EmptyState';
import { ErrorState } from '../components/foundation/ErrorState';
import { LoadingBlock } from '../components/foundation/LoadingState';
import { DataSourceBadge } from '../components/ui/DataSourceBadge';

// ACCEPTED_FOR_DISPATCH is durable intent; MQTT delivery is not yet known.
const STATUS_FILTERS = ['ALL', 'ACCEPTED_FOR_DISPATCH', 'SUCCESS', 'FAILURE', 'IGNORED', 'UNSUPPORTED'];

export default function ActionsPage() {
  const { actions } = useRepositories();
  const { data: globalActions = [], isLoading, error } = usePolling(
    ['global-actions-history'],
    () => actions.getActions(undefined, 100),
    { interval: 3000 }
  );

  const [search, setSearch] = useState('');
  const [filterStatus, setFilterStatus] = useState('ALL');
  const { openInspector } = useInspector();

  const filteredActions = useMemo(() => {
    return [...globalActions].filter((a: any) => {
      const targetDeviceId = a.deviceId || a.parameters?.deviceId || '';
      const matchSearch = (a.actionType || '').toLowerCase().includes(search.toLowerCase()) ||
                          targetDeviceId.toLowerCase().includes(search.toLowerCase());
      const matchStatus = filterStatus === 'ALL' || a.status === filterStatus;
      return matchSearch && matchStatus;
    });
  }, [globalActions, search, filterStatus]);

  // Real ExecutionStatus values from the backend - the comparison always
  // uses the real 'FAILURE' spelling; only the displayed label is humanized.
  const getActionStatus = (status: string, actionType?: string) => {
    switch (status) {
      case 'ACCEPTED_FOR_DISPATCH': return { label: 'Delivery unknown', icon: Clock, color: 'var(--warning)' };
      case 'SUCCESS': return { label: actionType === 'SEND_COMMAND' ? 'Published to MQTT' : 'SUCCESS', icon: CheckCircle2, color: 'var(--success)' };
      case 'FAILURE': return { label: 'Failed', icon: XCircle, color: 'var(--danger)' };
      case 'UNSUPPORTED': return { label: 'Unsupported', icon: XCircle, color: 'var(--warning)' };
      case 'IGNORED': return { label: 'Ignored', icon: RotateCw, color: 'var(--foundation-muted-fg)' };
      default: return { label: status || 'Unknown', icon: RotateCw, color: 'var(--foundation-muted-fg)' };
    }
  };

  // Real acknowledgement semantics (unchanged from ActionsTab/Analytics):
  // ACCEPTED/REJECTED are real device acknowledgements; a null ackStatus
  // means NO ACK YET, never a failure.
  const ackLabel = (ackStatus: string | null | undefined) => {
    if (ackStatus === 'ACCEPTED') return { label: 'ACCEPTED', color: 'var(--success)' };
    if (ackStatus === 'REJECTED') return { label: 'REJECTED', color: 'var(--danger)' };
    return { label: 'NO ACK YET', color: 'var(--foundation-muted-fg)' };
  };

  const totalLoaded = globalActions.length;
  const successCount = globalActions.filter((a: any) => a.status === 'SUCCESS').length;
  const failedCount = globalActions.filter((a: any) => a.status === 'FAILURE').length;
  const unsupportedCount = globalActions.filter((a: any) => a.status === 'UNSUPPORTED' || a.status === 'IGNORED').length;
  const successRate = totalLoaded > 0 ? ((successCount / totalLoaded) * 100).toFixed(1) : null;

  return (
    <div className="flex flex-col h-full overflow-y-auto" style={{ backgroundColor: 'var(--foundation-bg)' }}>
      <div className="flex flex-col gap-6 p-4 sm:p-6 lg:p-8 max-w-[1600px] w-full mx-auto">

        {/* HEADER */}
        <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4">
          <SectionHeader
            icon={<Play size={18} />}
            title="Actions"
            subtitle={`${totalLoaded} action(s) loaded`}
          />
          <div className="flex flex-wrap items-center gap-3">
            <div className="relative">
              <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-foundation-muted-fg" />
              <Input
                type="text"
                placeholder="Search actions..."
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                className="pl-9 w-56"
              />
            </div>
          </div>
        </div>

        {/* Real KPI summary */}
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 sm:gap-4">
          <MetricCard label="Total Loaded" value={totalLoaded} icon={<Zap size={18} />} />
          <MetricCard
            label="Success Rate"
            value={successRate ?? '—'}
            unit={successRate ? '%' : undefined}
            icon={<CheckCircle2 size={18} />}
            badge={<DataSourceBadge classification={successRate ? 'CALCULATED' : 'UNAVAILABLE'} variant="foundation" />}
          />
          <MetricCard label="Failed" value={failedCount} icon={<XCircle size={18} />} />
          <MetricCard label="Unsupported / Ignored" value={unsupportedCount} icon={<RotateCw size={18} />} />
        </div>

        {/* Filters */}
        <div className="flex flex-wrap items-center gap-1.5">
          {STATUS_FILTERS.map((f) => (
            <Pill key={f} active={filterStatus === f} onClick={() => setFilterStatus(f)}>{f}</Pill>
          ))}
        </div>

        {/* Main content: feed + side panel */}
        <div className="flex flex-col lg:flex-row gap-4 sm:gap-6">
          {/* Action Feed */}
          <div className="flex-1 min-w-0">
            {isLoading && globalActions.length === 0 ? (
              <div className="flex flex-col gap-3">
                <LoadingBlock className="h-24" />
                <LoadingBlock className="h-24" />
                <LoadingBlock className="h-24" />
              </div>
            ) : error ? (
              <Card level="glass" padding="lg">
                <ErrorState message="Could not load action history." />
              </Card>
            ) : globalActions.length === 0 ? (
              <Card level="glass" padding="lg">
                <EmptyState icon={<Play size={28} />} title="No actions recorded yet." />
              </Card>
            ) : filteredActions.length === 0 ? (
              <Card level="glass" padding="lg">
                <EmptyState icon={<Search size={28} />} title="No actions match your filters." />
              </Card>
            ) : (
              <div className="flex flex-col gap-3">
                {filteredActions.map((action: any, i: number) => {
                  const status = getActionStatus(action.status,action.actionType);
                  const targetDevice = action.deviceId || action.parameters?.deviceId || 'System';
                  return (
                    <GlassPanel
                      key={action.executionId || action.requestId || `${action.timestamp}-${i}`}
                      level="glass"
                      className="p-4 cursor-pointer transition-shadow hover:shadow-[var(--shadow-glass-lg)]"
                      onClick={() => openInspector({
                        id: action.executionId,
                        type: 'Action',
                        title: action.actionType,
                        subtitle: `Target: ${targetDevice}`,
                        status: status.label,
                        statusColor: action.status === 'SUCCESS' ? 'success' : action.status === 'FAILURE' ? 'destructive' : 'warning',
                        properties: {
                          'Request ID': action.requestId || 'N/A',
                          'Rule Match ID': action.matchId || 'N/A',
                          'Target Device': targetDevice,
                          'Execution Time': new Date(action.timestamp).toLocaleString(),
                          'Status': status.label,
                          'Message': action.message || 'N/A',
                        },
                        history: [
                          { time: new Date(action.timestamp).toLocaleString(), action: status.label, details: action.message || 'No message returned by the executor.' },
                        ],
                        rawJson: action,
                        correlationId: undefined,
                      })}
                    >
                      <div className="flex items-start gap-3">
                        <div
                          className="w-9 h-9 rounded-full flex items-center justify-center shrink-0"
                          style={{ backgroundColor: 'var(--foundation-surface)', border: '1px solid var(--foundation-border)' }}
                        >
                          <status.icon size={16} style={{ color: status.color }} />
                        </div>
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center justify-between gap-3 flex-wrap">
                            <span className="text-sm font-semibold text-foundation-fg">{action.actionType}</span>
                            <span className="text-xs font-mono text-foundation-tertiary-fg shrink-0">
                              {new Date(action.timestamp).toLocaleString()}
                            </span>
                          </div>
                          <div className="text-xs text-foundation-muted-fg mt-0.5 font-mono truncate" title={targetDevice}>
                            Device: {targetDevice}
                          </div>
                          <p className="text-sm text-foundation-fg/90 mt-1.5">{action.message || 'Action executed'}</p>
                          {action.actionType === 'SEND_COMMAND' && <p className="text-xs text-foundation-muted-fg mt-1">
                            Command: {action.commandStatus ?? (action.status === 'SUCCESS' ? 'PUBLISHED' : action.status)}
                            {' · '}Execution: {action.executionStatus ?? action.status}
                            {action.ackDeadlineAt ? ` · ACK deadline ${new Date(action.ackDeadlineAt).toLocaleString()}` : ''}
                            {action.ackBackendReceivedAt && action.ackDeadlineAt && new Date(action.ackBackendReceivedAt) > new Date(action.ackDeadlineAt) ? ' · Late ACK' : ''}
                          </p>}

                          <div className="flex flex-wrap items-center gap-3 mt-2.5 pt-2.5 border-t border-foundation-border">
                            <span
                              className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full"
                              style={{ backgroundColor: `color-mix(in srgb, ${status.color} 14%, transparent)`, color: status.color }}
                            >
                              {status.label}
                            </span>

                            {/* Acknowledgement - only SEND_COMMAND rows ever carry ack data */}
                            {action.actionType === 'SEND_COMMAND' && (
                              <span className="flex items-center gap-1.5 text-[10px]">
                                <span className="text-foundation-muted-fg">Device ack:</span>
                                <span
                                  className="font-bold uppercase tracking-wider px-2 py-0.5 rounded-full"
                                  style={{ backgroundColor: `color-mix(in srgb, ${ackLabel(action.ackStatus).color} 14%, transparent)`, color: ackLabel(action.ackStatus).color }}
                                >
                                  {ackLabel(action.ackStatus).label}
                                </span>
                              </span>
                            )}

                            {/* Real Rule Match investigation link - only when matchId genuinely exists */}
                            {action.matchId && (
                              <Link
                                to={`/ai?matchId=${action.matchId}`}
                                onClick={(e) => e.stopPropagation()}
                                className="flex items-center gap-1 text-[11px] font-medium ml-auto"
                                style={{ color: 'var(--accent)' }}
                              >
                                <Sparkles size={11} /> Rule Match →
                              </Link>
                            )}
                          </div>
                        </div>
                      </div>
                    </GlassPanel>
                  );
                })}
              </div>
            )}
          </div>

          {/* Side Panel - honest, clearly-labeled unavailable capabilities */}
          <div className="w-full lg:w-[280px] shrink-0 flex flex-col gap-4">
            <Card level="glass" padding="lg" icon={<RotateCw size={16} />} title="Retry Queue">
              <EmptyState icon={<CheckCircle2 size={24} />} title="Not yet implemented." description="Automatic action retries are not currently part of NEXUS." />
            </Card>
            <Card level="glass" padding="lg" icon={<Clock size={16} />} title="Scheduled">
              <EmptyState icon={<Clock size={24} />} title="Not yet implemented." description="Action scheduling is not currently part of NEXUS." />
            </Card>
          </div>
        </div>
      </div>
    </div>
  );
}
