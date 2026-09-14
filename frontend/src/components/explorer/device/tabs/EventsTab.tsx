import { Link } from 'react-router-dom';
import { Clock, AlertCircle, Info, Zap, AlertTriangle, Search, Hash } from 'lucide-react';
import { useState } from 'react';
import { useRepositories } from '../../../../repositories/RepositoryProvider';
import { usePolling } from '../../../../hooks/usePolling';
import { DebugOverlay } from '../DebugOverlay';
import { Card } from '../../../foundation/Card';
import { Input } from '../../../foundation/Input';
import { Pill } from '../../../foundation/Pill';
import { EmptyState } from '../../../foundation/EmptyState';
import { LoadingBlock } from '../../../foundation/LoadingState';

const FILTERS = ['ALL', 'INFO', 'WARNING', 'CRITICAL'];

export function EventsTab({ deviceId }: { deviceId: string }) {
  const { events } = useRepositories();
  const { data: deviceEvents = [], isLoading } = usePolling(
    ['events-history', deviceId],
    () => events.getEvents(deviceId, 100),
    { interval: 2000 }
  );
  const [filter, setFilter] = useState('ALL');
  const [search, setSearch] = useState('');

  if (isLoading) return <LoadingBlock className="h-64 mt-6" />;

  const getEventIcon = (severity: string) => {
    switch (severity) {
      case 'CRITICAL': return <AlertCircle size={16} style={{ color: 'var(--danger)' }} />;
      case 'WARNING': return <AlertTriangle size={16} style={{ color: 'var(--warning)' }} />;
      case 'INFO': return <Info size={16} style={{ color: 'var(--accent)' }} />;
      default: return <Zap size={16} className="text-foundation-muted-fg" />;
    }
  };

  const filteredEvents = deviceEvents
    .filter((e: any) => filter === 'ALL' || e.severity === filter)
    .filter((e: any) => search.trim() === '' || JSON.stringify(e).toLowerCase().includes(search.trim().toLowerCase()));

  return (
    <div className="py-6 flex flex-col gap-4">

      {/* Header & Controls */}
      <Card level="glass" padding="lg" className="shrink-0 relative">
        <DebugOverlay
          title="Event Timeline"
          data={deviceEvents}
          repository="DataSourceResolver"
        />
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <div className="p-2 rounded-[var(--radius-panel)]" style={{ backgroundColor: 'color-mix(in srgb, var(--accent) 14%, transparent)', color: 'var(--accent)' }}>
              <Clock size={20} />
            </div>
            <div>
              <h3 className="font-semibold text-foundation-fg">Event Timeline</h3>
              <p className="text-xs text-foundation-muted-fg">Historical device events</p>
            </div>
          </div>

          <div className="flex items-center gap-3">
            <div className="relative">
              <Search size={14} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-foundation-muted-fg" />
              <Input
                type="text"
                placeholder="Search events..."
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                className="w-48 pl-8"
              />
            </div>
            <div className="flex items-center gap-1.5">
              {FILTERS.map((f) => (
                <Pill key={f} active={filter === f} onClick={() => setFilter(f)}>{f}</Pill>
              ))}
            </div>
          </div>
        </div>
      </Card>

      {/* Timeline Feed */}
      {filteredEvents.length === 0 ? (
        <Card level="glass" padding="lg">
          <EmptyState
            icon={<Clock size={28} />}
            title="Event pipeline listening."
            description="Connected to the message broker. Waiting for incoming events."
          />
        </Card>
      ) : (
        <div className="flex flex-col gap-3">
          {filteredEvents.map((event: any, i: number) => (
            <Card key={event.id || `${event.timestamp}-${i}`} level="glass" padding="md">
              <div className="flex items-start gap-3">
                <div className="w-9 h-9 rounded-full flex items-center justify-center shrink-0" style={{ backgroundColor: 'var(--foundation-surface)', border: '1px solid var(--foundation-border)' }}>
                  {getEventIcon(event.severity || 'INFO')}
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center justify-between gap-3">
                    <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full"
                      style={{
                        backgroundColor: event.severity === 'CRITICAL' ? 'color-mix(in srgb, var(--danger) 14%, transparent)' : event.severity === 'WARNING' ? 'color-mix(in srgb, var(--warning) 14%, transparent)' : 'color-mix(in srgb, var(--accent) 14%, transparent)',
                        color: event.severity === 'CRITICAL' ? 'var(--danger)' : event.severity === 'WARNING' ? 'var(--warning)' : 'var(--accent)',
                      }}
                    >
                      {event.type || 'SYSTEM_EVENT'}
                    </span>
                    <span className="text-xs font-mono text-foundation-tertiary-fg shrink-0">
                      {new Date(event.timestamp).toLocaleTimeString([], { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' })}
                    </span>
                  </div>
                  <p className="text-sm text-foundation-fg/90 mt-1.5">
                    {event.message || `Event generated with payload: ${Object.keys(event.payload || {}).join(', ')}`}
                  </p>
                  {event.payload && Object.keys(event.payload).length > 0 && (
                    <div className="mt-2 rounded p-2 text-xs font-mono text-foundation-muted-fg overflow-hidden text-ellipsis whitespace-nowrap" style={{ backgroundColor: 'var(--foundation-bg)' }}>
                      {JSON.stringify(event.payload)}
                    </div>
                  )}
                  {event.correlationId && (
                    <Link
                      to={`/pipeline?correlationId=${encodeURIComponent(event.correlationId)}`}
                      className="inline-flex items-center gap-1.5 mt-2 text-[11px] font-medium"
                      style={{ color: 'var(--accent)' }}
                    >
                      <Hash size={11} /> View Trace
                    </Link>
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
