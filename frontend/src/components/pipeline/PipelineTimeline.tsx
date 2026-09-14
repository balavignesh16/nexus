import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import type { PipelineTracePayload } from '../../repositories/types';
import { Activity, Signal, Box, Zap, Download } from 'lucide-react';
import { downloadCsv } from '../../utils/csv';
import { GlassPanel } from '../foundation/GlassPanel';
import { Button } from '../foundation/Button';
import { Table, type TableColumn } from '../foundation/Table';
import { EmptyState } from '../foundation/EmptyState';
import { ErrorState } from '../foundation/ErrorState';
import { LoadingBlock } from '../foundation/LoadingState';

interface PipelineTimelineProps {
  onSelectCorrelation: (id: string) => void;
  isLoading?: boolean;
  error?: unknown;
}

const STAGE_ICON: Record<string, any> = {
  TELEMETRY: Signal,
  TWIN: Box,
  ACTION: Zap,
};

export function PipelineTimeline({ onSelectCorrelation, isLoading, error }: PipelineTimelineProps) {
  const queryClient = useQueryClient();
  const [traces, setTraces] = useState<PipelineTracePayload[]>([]);

  // Read-only observer of the shared real cache - RealtimeRepositorySync
  // (unchanged) and PipelinePage's one-time real history seed are the only
  // writers. No new subscription/polling is introduced here.
  useEffect(() => {
    const unsubscribe = queryClient.getQueryCache().subscribe((event) => {
      if (event.query.queryKey[0] === 'global-pipeline-traces') {
        setTraces(event.query.state.data as PipelineTracePayload[] || []);
      }
    });
    setTraces(queryClient.getQueryData<PipelineTracePayload[]>(['global-pipeline-traces']) || []);
    return unsubscribe;
  }, [queryClient]);

  const getStatusStyle = (status: string) => {
    if (status === 'SUCCESS') return { backgroundColor: 'color-mix(in srgb, var(--success) 14%, transparent)', color: 'var(--success)' };
    if (status === 'FAILURE' || status === 'ERROR') return { backgroundColor: 'color-mix(in srgb, var(--danger) 14%, transparent)', color: 'var(--danger)' };
    return { backgroundColor: 'var(--foundation-bg)', color: 'var(--foundation-muted-fg)' };
  };

  const rows = traces.slice(0, 100);

  const exportCsv = () => {
    const columns = ['timestamp', 'stage', 'deviceId', 'correlationId', 'durationMs', 'status'];
    const csvRows = rows.map((t) => [
      new Date(t.startTimestamp).toISOString(),
      t.stage,
      t.deviceId ?? '',
      t.correlationId,
      String(t.durationMs),
      t.status,
    ]);
    downloadCsv(`pipeline-traces-${Date.now()}.csv`, columns, csvRows);
  };

  const columns: TableColumn<PipelineTracePayload>[] = [
    {
      key: 'timestamp',
      header: 'Timestamp',
      render: (t) => <span className="font-mono text-xs text-foundation-muted-fg whitespace-nowrap">{new Date(t.startTimestamp).toLocaleTimeString()}</span>,
    },
    {
      key: 'stage',
      header: 'Stage',
      render: (t) => {
        const Icon = STAGE_ICON[t.stage] ?? Activity;
        return (
          <span className="flex items-center gap-2 text-xs font-medium text-foundation-fg">
            <Icon size={13} style={{ color: 'var(--accent)' }} /> {t.stage}
          </span>
        );
      },
    },
    {
      key: 'device',
      header: 'Device',
      render: (t) => <span className="font-mono text-xs text-foundation-muted-fg" title={t.deviceId ?? undefined}>{t.deviceId ? t.deviceId.slice(0, 8) : 'SYS'}</span>,
    },
    {
      key: 'correlation',
      header: 'Correlation ID',
      render: (t) => (
        <span className="font-mono text-[11px] text-foundation-muted-fg truncate block max-w-[140px]" title={t.correlationId}>
          {t.correlationId}
        </span>
      ),
    },
    {
      key: 'duration',
      header: 'Duration',
      align: 'right',
      render: (t) => <span className="font-mono text-xs text-foundation-fg">{t.durationMs}ms</span>,
    },
    {
      key: 'status',
      header: 'Status',
      render: (t) => (
        <span className="px-2 py-0.5 rounded-[var(--radius-pill)] text-[10px] font-bold" style={getStatusStyle(t.status)}>
          {t.status}
        </span>
      ),
    },
  ];

  return (
    <GlassPanel level="glass" className="flex flex-col overflow-hidden h-full">
      <div className="shrink-0 flex items-center justify-between p-3 border-b border-foundation-border">
        <h3 className="text-xs font-bold text-foundation-fg uppercase tracking-wider flex items-center gap-2">
          <Activity size={14} style={{ color: 'var(--accent)' }} />
          Recent Traces
        </h3>
        <Button variant="outline" size="sm" onClick={exportCsv} disabled={rows.length === 0}>
          <Download size={13} /> Export CSV
        </Button>
      </div>

      <div className="flex-1 overflow-auto">
        {isLoading ? (
          <div className="p-4"><LoadingBlock className="h-48" /></div>
        ) : error ? (
          <div className="p-6"><ErrorState message="Could not load pipeline history." /></div>
        ) : rows.length === 0 ? (
          <div className="p-6">
            <EmptyState icon={<Activity size={28} />} title="No pipeline traces available yet." description="Waiting for real telemetry to flow through the pipeline." />
          </div>
        ) : (
          <table className="w-full text-left text-xs">
            <thead className="sticky top-0 z-10" style={{ backgroundColor: 'var(--foundation-surface)', borderBottom: '1px solid var(--foundation-border)' }}>
              <tr>
                {columns.map((c) => (
                  <th key={c.key} className="font-semibold p-2.5 text-[10px] uppercase tracking-wider text-foundation-muted-fg">{c.header}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((trace) => (
                <tr
                  key={trace.traceId}
                  onClick={() => onSelectCorrelation(trace.correlationId)}
                  className="cursor-pointer transition-colors hover:bg-black/[0.02]"
                  style={{ borderBottom: '1px solid var(--foundation-border)' }}
                >
                  {columns.map((c) => (
                    <td key={c.key} className="p-2.5">{c.render(trace)}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </GlassPanel>
  );
}
