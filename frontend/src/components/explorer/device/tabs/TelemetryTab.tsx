import { useState } from 'react';
import { Signal, Download, Search } from 'lucide-react';
import { useRepositories } from '../../../../repositories/RepositoryProvider';
import { usePolling } from '../../../../hooks/usePolling';
import { useDeviceTwinState } from '../../../../hooks/useDeviceTwinState';
import { downloadCsv } from '../../../../utils/csv';
import { Card } from '../../../foundation/Card';
import { Input } from '../../../foundation/Input';
import { Button } from '../../../foundation/Button';
import { Table, type TableColumn } from '../../../foundation/Table';
import { EmptyState } from '../../../foundation/EmptyState';
import { LoadingBlock } from '../../../foundation/LoadingState';
import { StatusIndicator, type StatusTone } from '../../../foundation/StatusIndicator';

const CONNECTIVITY_TONE: Record<string, StatusTone> = {
  ONLINE: 'online',
  OFFLINE: 'offline',
  UNKNOWN: 'unknown',
};

export function TelemetryTab({ deviceId }: { deviceId: string }) {
  const { telemetry } = useRepositories();
  const { data: deviceTelemetry = [], isLoading } = usePolling(
    ['telemetry-history', deviceId],
    () => telemetry.getTelemetryHistory(deviceId),
    { interval: 2000 }
  );
  const twinState = useDeviceTwinState(deviceId);
  const connectivityStatus = twinState.connectivity?.status ?? 'UNKNOWN';

  const [searchTerm, setSearchTerm] = useState('');

  if (isLoading) return <LoadingBlock className="h-64 mt-6" />;

  // Extract all unique keys from payloads to build columns
  const availableKeys: string[] = Array.from(
    new Set(deviceTelemetry.flatMap((t: any) => Object.keys(t.payload || {})))
  ).filter(k => k !== 'timestamp');

  const filteredTelemetry = searchTerm.trim() === ''
    ? deviceTelemetry
    : deviceTelemetry.filter((t: any) =>
        JSON.stringify(t.payload || {}).toLowerCase().includes(searchTerm.trim().toLowerCase())
      );

  const exportCsv = () => {
    const columns = ['timestamp', ...availableKeys];
    const rows = filteredTelemetry.map((t: any) => [
      new Date(t.timestamp).toISOString(),
      ...availableKeys.map((k) => (t.payload[k] !== undefined ? String(t.payload[k]) : '')),
    ]);
    downloadCsv(`telemetry-${deviceId}-${Date.now()}.csv`, columns, rows);
  };

  const columns: TableColumn<any>[] = [
    {
      key: 'timestamp',
      header: 'Timestamp',
      render: (t) => <span className="font-mono text-xs text-foundation-muted-fg whitespace-nowrap">{new Date(t.timestamp).toLocaleString()}</span>,
    },
    ...availableKeys.map((k) => ({
      key: k,
      header: k,
      render: (t: any) => <span className="font-mono text-xs">{t.payload[k] !== undefined ? String(t.payload[k]) : '-'}</span>,
    })),
  ];

  return (
    <div className="py-6 flex flex-col gap-4">

      {/* Header & Controls */}
      <Card level="glass" padding="lg" className="shrink-0">
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <div
              className="p-2 rounded-[var(--radius-panel)]"
              style={{ backgroundColor: 'color-mix(in srgb, var(--accent) 14%, transparent)', color: 'var(--accent)' }}
            >
              <Signal size={20} />
            </div>
            <div>
              <h3 className="font-semibold text-foundation-fg">Telemetry Data</h3>
              <p className="text-xs text-foundation-muted-fg">Historical data points</p>
            </div>
            <StatusIndicator tone={CONNECTIVITY_TONE[connectivityStatus]} label={connectivityStatus} className="ml-2" />
          </div>

          <div className="flex items-center gap-3">
            <div className="relative">
              <Search size={14} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-foundation-muted-fg" />
              <Input
                type="text"
                placeholder="Search payloads..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                className="w-48 pl-8"
              />
            </div>
            <Button variant="secondary" size="sm" onClick={exportCsv} disabled={filteredTelemetry.length === 0}>
              <Download size={14} /> Export CSV
            </Button>
          </div>
        </div>
      </Card>

      {/* Data Table */}
      {filteredTelemetry.length === 0 ? (
        <Card level="glass" padding="lg">
          <EmptyState
            icon={<Signal size={28} />}
            title={deviceTelemetry.length === 0 ? 'Awaiting telemetry.' : 'No matching records.'}
            description={
              deviceTelemetry.length === 0
                ? 'Device is provisioned but no telemetry data has been received yet.'
                : 'No telemetry payloads match your search.'
            }
          />
        </Card>
      ) : (
        <>
          <Table columns={columns} rows={filteredTelemetry} rowKey={(t) => t.id ?? String(t.timestamp)} />
          <div className="text-xs text-foundation-muted-fg px-1">
            Showing {filteredTelemetry.length} of {deviceTelemetry.length} records
          </div>
        </>
      )}
    </div>
  );
}
