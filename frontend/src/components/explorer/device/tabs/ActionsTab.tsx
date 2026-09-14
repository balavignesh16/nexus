import { CheckCircle2, XCircle, Search, RotateCw, Play } from 'lucide-react';
import { useState } from 'react';
import { useRepositories } from '../../../../repositories/RepositoryProvider';
import { usePolling } from '../../../../hooks/usePolling';
import { DebugOverlay } from '../DebugOverlay';
import { Card } from '../../../foundation/Card';
import { Input } from '../../../foundation/Input';
import { Pill } from '../../../foundation/Pill';
import { EmptyState } from '../../../foundation/EmptyState';
import { LoadingBlock } from '../../../foundation/LoadingState';

// Real backend ExecutionStatus values (deviceApi.ts): SUCCESS/FAILURE/
// IGNORED/UNSUPPORTED - the filter set uses the real 'FAILURE' spelling,
// not the previous 'FAILED' which never matched a real action.
const FILTERS = ['ALL', 'SUCCESS', 'FAILURE', 'IGNORED', 'UNSUPPORTED'];

export function ActionsTab({ deviceId }: { deviceId: string }) {
  const { actions } = useRepositories();
  const { data: deviceActions = [], isLoading } = usePolling(
    ['actions-history', deviceId],
    () => actions.getActions(deviceId, 50),
    { interval: 2000 }
  );

  const [filter, setFilter] = useState('ALL');
  const [search, setSearch] = useState('');

  if (isLoading) return <LoadingBlock className="h-64 mt-6" />;

  const getActionIcon = (status: string) => {
    switch (status) {
      case 'SUCCESS': return <CheckCircle2 size={16} style={{ color: 'var(--success)' }} />;
      case 'FAILURE': return <XCircle size={16} style={{ color: 'var(--danger)' }} />;
      case 'IGNORED':
      case 'UNSUPPORTED': return <RotateCw size={16} style={{ color: 'var(--warning)' }} />;
      default: return <Play size={16} style={{ color: 'var(--accent)' }} />;
    }
  };

  // Reuses the exact ack semantics/coloring established on the Analytics
  // page (Phase 8): ACCEPTED/REJECTED are real device acknowledgements; an
  // absent ackStatus means NO_ACK_YET - never rendered as a failure.
  const ackLabel = (ackStatus: string | null | undefined) => {
    if (ackStatus === 'ACCEPTED') return { label: 'ACCEPTED', color: 'var(--success)' };
    if (ackStatus === 'REJECTED') return { label: 'REJECTED', color: 'var(--danger)' };
    return { label: 'NO ACK YET', color: 'var(--foundation-muted-fg)' };
  };

  const filteredActions = deviceActions
    .filter((a: any) => filter === 'ALL' || a.status === filter)
    .filter((a: any) => search.trim() === '' || JSON.stringify(a).toLowerCase().includes(search.trim().toLowerCase()));

  return (
    <div className="py-6 flex flex-col gap-4">

      {/* Header & Controls */}
      <Card level="glass" padding="lg" className="shrink-0 relative">
        <DebugOverlay
          title="Action Execution History"
          data={deviceActions}
          repository="DataSourceResolver"
        />
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <div className="p-2 rounded-[var(--radius-panel)]" style={{ backgroundColor: 'color-mix(in srgb, var(--accent) 14%, transparent)', color: 'var(--accent)' }}>
              <Play size={20} />
            </div>
            <div>
              <h3 className="font-semibold text-foundation-fg">Action Execution History</h3>
              <p className="text-xs text-foundation-muted-fg">Commands sent to device</p>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-3">
            <div className="relative">
              <Search size={14} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-foundation-muted-fg" />
              <Input
                type="text"
                placeholder="Search actions..."
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                className="w-48 pl-8"
              />
            </div>
            <div className="flex flex-wrap items-center gap-1.5">
              {FILTERS.map((f) => (
                <Pill key={f} active={filter === f} onClick={() => setFilter(f)}>{f}</Pill>
              ))}
            </div>
          </div>
        </div>
      </Card>

      {/* Action Feed */}
      {filteredActions.length === 0 ? (
        <Card level="glass" padding="lg">
          <EmptyState
            icon={<Play size={28} />}
            title="Command queue empty."
            description="No actions have been executed against this device yet."
          />
        </Card>
      ) : (
        <div className="flex flex-col gap-3">
          {filteredActions.map((action: any, i: number) => (
            <Card key={action.id || `${action.timestamp}-${i}`} level="glass" padding="md">
              <div className="flex items-start gap-3">
                <div className="w-9 h-9 rounded-full flex items-center justify-center shrink-0" style={{ backgroundColor: 'var(--foundation-surface)', border: '1px solid var(--foundation-border)' }}>
                  {getActionIcon(action.status)}
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center justify-between gap-3">
                    <span
                      className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full"
                      style={{
                        backgroundColor: action.status === 'FAILURE' ? 'color-mix(in srgb, var(--danger) 14%, transparent)' : 'color-mix(in srgb, var(--success) 14%, transparent)',
                        color: action.status === 'FAILURE' ? 'var(--danger)' : 'var(--success)',
                      }}
                    >
                      {action.status}
                    </span>
                    <span className="text-xs font-mono text-foundation-tertiary-fg shrink-0">
                      {new Date(action.timestamp).toLocaleTimeString([], { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' })}
                    </span>
                  </div>
                  <p className="text-sm text-foundation-fg/90 mt-1.5">
                    {action.message || 'Action executed'}
                  </p>
                  {/* Acknowledgement (Phase 8): whether the device itself
                      confirmed the command, distinct from execution
                      status. Only SEND_COMMAND rows ever carry ack data -
                      an absent ackStatus is NO_ACK_YET, never a failure. */}
                  {action.actionType === 'SEND_COMMAND' && (
                    <div className="mt-2 flex items-center gap-1.5">
                      <span className="text-[10px] text-foundation-muted-fg">Device ack:</span>
                      <span
                        className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full"
                        style={{ backgroundColor: 'color-mix(in srgb, ' + ackLabel(action.ackStatus).color + ' 14%, transparent)', color: ackLabel(action.ackStatus).color }}
                      >
                        {ackLabel(action.ackStatus).label}
                      </span>
                    </div>
                  )}
                  {action.payload && Object.keys(action.payload).length > 0 && (
                    <div className="mt-2 rounded p-2 text-xs font-mono text-foundation-muted-fg overflow-hidden text-ellipsis whitespace-nowrap" style={{ backgroundColor: 'var(--foundation-bg)' }}>
                      {JSON.stringify(action.payload)}
                    </div>
                  )}
                </div>
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
