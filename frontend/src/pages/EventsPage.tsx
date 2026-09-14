import { useState, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { useRepositories } from '../repositories/RepositoryProvider';
import { usePolling } from '../hooks/usePolling';
import { Zap, Search, AlertTriangle, AlertCircle, Info, Hash, Cpu } from 'lucide-react';
import { useInspector } from '../components/ui/InspectorProvider';
import { GlassPanel } from '../components/foundation/GlassPanel';
import { Card } from '../components/foundation/Card';
import { SectionHeader } from '../components/foundation/SectionHeader';
import { Input } from '../components/foundation/Input';
import { Pill } from '../components/foundation/Pill';
import { EmptyState } from '../components/foundation/EmptyState';
import { LoadingBlock } from '../components/foundation/LoadingState';
import { DataSourceBadge } from '../components/ui/DataSourceBadge';

const SEVERITIES = ['ALL', 'INFO', 'WARNING', 'CRITICAL'];

export default function EventsPage() {
  const { events } = useRepositories();
  const { data: globalEvents = [], isLoading } = usePolling(
    ['global-events-history'],
    () => events.getEvents(undefined, 100),
    { interval: 3000 }
  );

  const [search, setSearch] = useState('');
  const [filterSeverity, setFilterSeverity] = useState('ALL');
  const { openInspector } = useInspector();
  const navigate = useNavigate();

  const filteredEvents = useMemo(() => {
    return [...globalEvents].filter((e: any) => {
      const matchSearch = (e.type || '').toLowerCase().includes(search.toLowerCase()) ||
                          (e.deviceId || '').toLowerCase().includes(search.toLowerCase());
      const matchSeverity = filterSeverity === 'ALL' || e.severity === filterSeverity;
      return matchSearch && matchSeverity;
    });
  }, [globalEvents, search, filterSeverity]);

  // Real counts computed from the currently loaded event set - never a
  // fabricated percentage/trend.
  const summary = useMemo(() => {
    return {
      total: globalEvents.length,
      critical: globalEvents.filter((e: any) => e.severity === 'CRITICAL').length,
      warning: globalEvents.filter((e: any) => e.severity === 'WARNING').length,
      info: globalEvents.filter((e: any) => e.severity === 'INFO').length,
    };
  }, [globalEvents]);

  const getEventIcon = (severity: string) => {
    switch (severity) {
      case 'CRITICAL': return <AlertCircle size={16} style={{ color: 'var(--danger)' }} />;
      case 'WARNING': return <AlertTriangle size={16} style={{ color: 'var(--warning)' }} />;
      default: return <Info size={16} style={{ color: 'var(--accent)' }} />;
    }
  };

  if (isLoading && globalEvents.length === 0) {
    return (
      <div className="flex flex-col h-full overflow-y-auto p-4 sm:p-6" style={{ backgroundColor: 'var(--foundation-bg)' }}>
        <LoadingBlock className="h-16 mb-4" />
        <LoadingBlock className="flex-1" />
      </div>
    );
  }

  return (
    <div className="flex flex-col h-full overflow-y-auto" style={{ backgroundColor: 'var(--foundation-bg)' }}>
      <div className="flex flex-col gap-6 p-4 sm:p-6 lg:p-8 max-w-[1100px] w-full mx-auto">

        {/* HEADER */}
        <div className="flex flex-col lg:flex-row lg:items-center justify-between gap-4">
          <SectionHeader
            icon={<Zap size={18} />}
            title="Events"
            subtitle="Global event stream & correlation"
          />
          <div className="relative">
            <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-foundation-muted-fg" />
            <Input
              type="text"
              placeholder="Search events..."
              value={search}
              onChange={e => setSearch(e.target.value)}
              className="pl-9 w-64"
            />
          </div>
        </div>

        {/* FILTERS */}
        <div className="flex flex-wrap items-center gap-1.5">
          {SEVERITIES.map(s => (
            <Pill key={s} active={filterSeverity === s} onClick={() => setFilterSeverity(s)}>{s}</Pill>
          ))}
        </div>

        {/* SUMMARY */}
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 sm:gap-4">
          {[
            { label: 'Total', value: summary.total, color: 'var(--foundation-fg)' },
            { label: 'Critical', value: summary.critical, color: 'var(--danger)' },
            { label: 'Warning', value: summary.warning, color: 'var(--warning)' },
            { label: 'Info', value: summary.info, color: 'var(--accent)' },
          ].map((stat) => (
            <Card key={stat.label} level="glass" padding="md">
              <div className="flex items-center justify-between">
                <span className="text-xs font-medium uppercase tracking-wider text-foundation-muted-fg">{stat.label}</span>
                <DataSourceBadge classification="CALCULATED" variant="foundation" />
              </div>
              <div className="text-2xl font-semibold mt-1.5" style={{ color: stat.color }}>{stat.value}</div>
            </Card>
          ))}
        </div>

        {/* TIMELINE */}
        {filteredEvents.length === 0 ? (
          <Card level="glass" padding="lg">
            <EmptyState icon={<Zap size={28} />} title="No events found matching your criteria." />
          </Card>
        ) : (
          <div className="flex flex-col gap-3 pb-4">
            {filteredEvents.map((event: any, idx: number) => (
              <GlassPanel
                key={event.id || idx}
                level="glass"
                className="p-4 cursor-pointer transition-colors hover:shadow-[var(--shadow-glass-lg)]"
                onClick={() => openInspector({
                  id: event.id,
                  type: 'Event',
                  title: event.type,
                  subtitle: event.deviceId ? `Source: ${event.deviceId}` : 'System Event',
                  status: event.severity,
                  statusColor: event.severity === 'CRITICAL' ? 'destructive' : event.severity === 'WARNING' ? 'warning' : 'success',
                  properties: {
                    'Correlation ID': event.correlationId || 'N/A',
                    'Source Node': event.deviceId || 'System',
                    'Timestamp': new Date(event.timestamp).toLocaleString(),
                    'Severity': event.severity,
                  },
                  rawJson: event,
                  correlationId: event.correlationId,
                })}
              >
                <div className="flex items-start gap-3.5">
                  <div
                    className="w-10 h-10 rounded-full flex items-center justify-center shrink-0"
                    style={{ backgroundColor: 'var(--foundation-surface)', border: '1px solid var(--foundation-border)' }}
                  >
                    {getEventIcon(event.severity)}
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center justify-between gap-3">
                      <div className="flex items-center gap-2 min-w-0">
                        <span
                          className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full shrink-0"
                          style={{
                            backgroundColor: event.severity === 'CRITICAL' ? 'color-mix(in srgb, var(--danger) 14%, transparent)' : event.severity === 'WARNING' ? 'color-mix(in srgb, var(--warning) 14%, transparent)' : 'color-mix(in srgb, var(--accent) 14%, transparent)',
                            color: event.severity === 'CRITICAL' ? 'var(--danger)' : event.severity === 'WARNING' ? 'var(--warning)' : 'var(--accent)',
                          }}
                        >
                          {event.severity || 'UNKNOWN'}
                        </span>
                        <h3 className="font-semibold text-foundation-fg truncate">{event.type}</h3>
                      </div>
                      <span className="text-xs font-mono text-foundation-tertiary-fg shrink-0" title={new Date(event.timestamp).toLocaleString()}>
                        {new Date(event.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
                      </span>
                    </div>
                    <p className="text-sm text-foundation-muted-fg mt-1.5">{event.message}</p>

                    {/* Real operational metadata row - every field shown
                        honestly, "Unavailable" rather than a silently
                        empty/missing segment when a real event genuinely
                        has no device/correlationId (e.g. system events). */}
                    <div className="flex flex-wrap items-center gap-x-4 gap-y-1.5 mt-2.5 pt-2.5 border-t border-foundation-border text-xs">
                      <span className="flex items-center gap-1.5 text-foundation-muted-fg">
                        <Cpu size={12} className="text-foundation-tertiary-fg" />
                        {event.deviceId || 'Unavailable'}
                      </span>
                      <span className="flex items-center gap-1.5 font-mono text-foundation-muted-fg min-w-0">
                        <Hash size={12} className="text-foundation-tertiary-fg shrink-0" />
                        <span className="truncate">{event.correlationId || 'Unavailable'}</span>
                      </span>
                      {event.correlationId && (
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            navigate(`/pipeline?correlationId=${encodeURIComponent(event.correlationId)}`);
                          }}
                          className="ml-auto text-[11px] font-medium hover:underline shrink-0"
                          style={{ color: 'var(--accent)' }}
                        >
                          View Trace &rarr;
                        </button>
                      )}
                    </div>
                  </div>
                </div>
              </GlassPanel>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
