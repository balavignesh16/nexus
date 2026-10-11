import { useCallback, useEffect, useState } from 'react';
import { useDevice, useLatestTwins } from '../../../../hooks/queries';
import { Box, GitCommit } from 'lucide-react';
import { DiffEditor } from '@monaco-editor/react';
import { useDeviceTwinState } from '../../../../hooks/useDeviceTwinState';
import { formatAge } from '../../../../utils/formatAge';
import { DataSourceBadge } from '../../../ui/DataSourceBadge';
import { Card } from '../../../foundation/Card';
import { GlassPanel } from '../../../foundation/GlassPanel';
import { Tabs } from '../../../foundation/Tabs';
import { LoadingBlock } from '../../../foundation/LoadingState';
import { Button } from '../../../foundation/Button';
import { Select } from '../../../foundation/Input';
import { twinApi, type TwinHistoryItem } from '../../../../api/twinApi';
import { ReadbackMappingPanel } from './ReadbackMappingPanel';
import { useQueryClient } from '@tanstack/react-query';

export function DigitalTwinTab({ deviceId }: { deviceId: string }) {
  const { data: device } = useDevice(deviceId);
  const { data: twins = [] } = useLatestTwins(100);
  const queryClient = useQueryClient();
  const [viewMode, setViewMode] = useState<'visual' | 'json' | 'diff'>('visual');
  const twinState = useDeviceTwinState(deviceId);
  const connectivity = twinState.connectivity?.status ?? 'UNKNOWN';
  const [history, setHistory] = useState<TwinHistoryItem[]>([]);
  const [historyCursor, setHistoryCursor] = useState<string | null>(null);
  const [historyType, setHistoryType] = useState('');
  const [selectedTransition, setSelectedTransition] = useState(0);
  const [historyError, setHistoryError] = useState('');

  // The API returns one device-level twin with every independently stored
  // capability in reportedState. The latest* fields remain only as a
  // backwards-compatible summary and must not be used as the complete twin.
  const deviceTwin = twins.find((t) => t.deviceId === deviceId);
  const currentTwin = deviceTwin?.reportedState ?? null;

  useEffect(() => {
    const boundaries = Object.values(currentTwin ?? {}).flatMap(state => {
      const q = state.stateQuality;
      return q?.receivedAt && q.state === 'GOOD'
        ? [new Date(q.receivedAt).getTime() + q.thresholdSeconds * 1000 + 1] : [];
    });
    if (!boundaries.length) return;
    // One timer for the nearest boundary. A boundary that elapsed while this
    // view/query was inactive is refreshed once immediately (never negatively).
    const delay = Math.max(0, Math.min(...boundaries) - Date.now());
    const timer = window.setTimeout(() => {
      void queryClient.invalidateQueries({ queryKey: ['twins', 100] });
    }, delay);
    return () => window.clearTimeout(timer);
  }, [currentTwin, queryClient]);

  const loadHistory = useCallback(async (cursor?: string) => {
    const append = Boolean(cursor);
    try {
      const page = await twinApi.getHistory(deviceId, { transitionType: historyType || undefined,
        limit: 50, cursor });
      setHistory(rows => append ? [...rows, ...page.items] : page.items);
      setHistoryCursor(page.nextCursor); setHistoryError('');
      if (!append) setSelectedTransition(0);
    } catch (error) { setHistoryError(error instanceof Error ? error.message : String(error)); }
  }, [deviceId, historyType]);
  useEffect(() => { if (viewMode === 'diff') void loadHistory(); }, [viewMode, loadHistory]);

  // /twins is a current-state snapshot, not a time series - there is no
  // real "previous twin" to diff against from this data source, so Diffs
  // mode honestly shows no changes (current vs. current) rather than
  // fabricating a prior state from an unrelated capability's row.
  const currentJson = currentTwin ? JSON.stringify(currentTwin, null, 2) : '{\n  "status": "No twin data available"\n}';
  const selected = history[selectedTransition];
  const previousJson = selected ? JSON.stringify({ value: selected.previousValue, unit: selected.previousUnit }, null, 2) : currentJson;
  const changedJson = selected ? JSON.stringify({ value: selected.newValue, unit: selected.newUnit }, null, 2) : currentJson;
  const displayedQuality = (state: NonNullable<typeof currentTwin>[string]) => state.stateQuality?.state ?? state.quality;
  const verificationLabel = (status?: string, quality?: string) => {
    switch (status) {
      case 'PENDING': return 'Awaiting qualified readback';
      case 'CONVERGED': return quality && quality !== 'GOOD'
        ? `Last verified converged · current readback ${quality.toLowerCase()}` : 'Readback matched · converged';
      case 'DIVERGED': return 'Observed state drifted from desired state';
      case 'TIMED_OUT': return 'Verification timed out';
      case 'NO_DESIRED_STATE': return 'No desired intent';
      case 'NOT_APPLICABLE': return 'Verification not applicable';
      default: return 'Readback unavailable · unverified';
    }
  };

  if (!device) return <LoadingBlock className="h-full mt-6" />;

  return (
    <div className="py-6 flex flex-col gap-4 h-full">

      {/* Header & Controls - view mode only; no Edit/Save mutation control
          (removed: it was a dead no-op, never persisted to the backend). */}
      <Card level="glass" padding="lg" className="shrink-0">
        <div className="flex items-center justify-between gap-4 flex-wrap">
          <div className="flex items-center gap-3">
            <div
              className="p-2 rounded-[var(--radius-panel)]"
              style={{ backgroundColor: 'color-mix(in srgb, var(--accent) 14%, transparent)', color: 'var(--accent)' }}
            >
              <Box size={20} />
            </div>
            <div>
              <h3 className="font-semibold text-foundation-fg">Digital Twin</h3>
              <p className="text-xs text-foundation-muted-fg">Virtual representation of {device.name}</p>
            </div>
          </div>

          <Tabs
            items={[
              { value: 'visual', label: 'Visual' },
              { value: 'json', label: 'JSON' },
              { value: 'diff', label: 'Diffs' },
            ]}
            value={viewMode}
            onChange={(v) => setViewMode(v as typeof viewMode)}
          />
        </div>
      </Card>

      {/* Real Digital Twin state (Phase 8) - evidence-based, no fabricated
          quality score. Connectivity is LIVE; telemetry recency and field
          count are CALCULATED from this device's own real telemetry
          history; anything not derivable is shown as Unavailable rather
          than invented. */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 shrink-0">
        <Card level="glass" padding="md">
          <span className="text-[10px] text-foundation-muted-fg uppercase tracking-wider flex items-center gap-1.5 mb-1">
            Connectivity <DataSourceBadge classification="LIVE" variant="foundation" />
          </span>
          <span
            className="text-sm font-bold"
            style={{ color: connectivity === 'ONLINE' ? 'var(--success)' : connectivity === 'OFFLINE' ? 'var(--danger)' : 'var(--data-unavailable)' }}
          >
            {connectivity}
          </span>
        </Card>
        <Card level="glass" padding="md">
          <span className="text-[10px] text-foundation-muted-fg uppercase tracking-wider flex items-center gap-1.5 mb-1">
            Last Telemetry <DataSourceBadge classification="CALCULATED" variant="foundation" />
          </span>
          <span className="text-sm font-bold text-foundation-fg" title={twinState.lastTelemetryAt ? new Date(twinState.lastTelemetryAt).toLocaleString() : undefined}>
            {twinState.telemetryLoading ? '…' : twinState.lastTelemetryAt ? formatAge(twinState.lastTelemetryAt) : 'Unavailable'}
          </span>
        </Card>
        <Card level="glass" padding="md">
          <span className="text-[10px] text-foundation-muted-fg uppercase tracking-wider flex items-center gap-1.5 mb-1">
            Reporting Fields <DataSourceBadge classification="CALCULATED" variant="foundation" />
          </span>
          <span className="text-sm font-bold text-foundation-fg">
            {twinState.reportedFields.length > 0 ? twinState.reportedFields.join(', ') : 'None reported'}
          </span>
        </Card>
      </div>

      {/^[0-9a-f]{8}-[0-9a-f-]{27,}$/i.test(deviceId) && <ReadbackMappingPanel deviceId={deviceId} />}

      {/* Content Area */}
      <GlassPanel level="glass" radius="panel-lg" className="flex-1 min-h-0 overflow-hidden relative">

        {/* Visual Mode */}
        {viewMode === 'visual' && (
          <div className="p-6 overflow-y-auto h-full custom-scrollbar">
            {Object.entries(deviceTwin?.desiredState ?? {}).map(([key, desired]) => {
              const verification = deviceTwin?.verification?.[key];
              return <div key={key} className="mb-4 rounded-[var(--radius-panel)] p-4 border border-foundation-border bg-foundation-surface">
                <h4 className="text-sm font-semibold text-foundation-fg">Desired intent · {key}</h4>
                <p className="text-xs text-foundation-muted-fg mt-1">Value: {JSON.stringify(desired.typedValue)} · Command: {desired.commandId}</p>
                <p className="text-xs text-foundation-muted-fg">MQTT publication: {desired.deliveryStatus === 'SUCCESS' ? 'Published' : desired.deliveryStatus === 'ACCEPTED_FOR_DISPATCH' ? 'Durably accepted' : desired.deliveryStatus}</p>
                <p className="text-xs text-foundation-muted-fg">Device ACK: {desired.ackStatus === 'ACCEPTED' ? 'Device ACK accepted' : desired.ackStatus === 'REJECTED' ? 'Device ACK rejected' : 'No ACK yet'}</p>
                <p className="text-xs text-foundation-muted-fg">Qualified readback: {verification?.readbackEvidence ?? 'None'}</p>
                <p className="text-xs font-semibold text-foundation-fg">Verification: {verificationLabel(verification?.status, verification?.readbackStateQuality?.state)}</p>
                {verification?.readbackValue != null && <p className="text-xs text-foundation-muted-fg">Readback value: {JSON.stringify(verification.readbackValue)} · {verification.comparisonResult}</p>}
                {verification?.deadlineAt && <p className="text-xs text-foundation-muted-fg">Deadline: {new Date(verification.deadlineAt).toLocaleString()}</p>}
                {verification?.discrepancyReason && <p className="text-xs text-foundation-muted-fg">Reason: {verification.discrepancyReason}</p>}
              </div>;
            })}
            {Object.entries(deviceTwin?.verification ?? {})
              .filter(([key]) => !deviceTwin?.desiredState?.[key])
              .map(([key, verification]) => <div key={key} className="mb-4 rounded-[var(--radius-panel)] p-4 border border-foundation-border bg-foundation-surface">
                <h4 className="text-sm font-semibold text-foundation-fg">State · {key}</h4>
                <p className="text-xs font-semibold text-foundation-fg mt-1">Verification: {verificationLabel(verification.status, verification.readbackStateQuality?.state)}</p>
              </div>)}
            {currentTwin ? (
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                {Object.entries(currentTwin).map(([key, value]) => {
                  if (typeof value === 'object' && value !== null) {
                    return (
                      <div key={key} className="col-span-full rounded-[var(--radius-panel)] p-4" style={{ backgroundColor: 'var(--foundation-surface)', border: '1px solid var(--foundation-border)' }}>
                        <h4 className="text-sm font-semibold capitalize mb-3 pb-2 border-b border-foundation-border text-foundation-fg">{key}</h4>
                        {'quality' in value && <p className="mb-3 text-xs"><span className="text-foundation-muted-fg">State quality: </span>
                          <strong>{displayedQuality(value)}</strong>{value.stateQuality?.reason ? ` · ${value.stateQuality.reason}` : ''}</p>}
                        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                          {Object.entries(value).filter(([subKey]) => subKey !== 'stateQuality' && subKey !== 'quality').map(([subKey, subValue]) => (
                            <div key={subKey} className="flex flex-col gap-1">
                              <span className="text-xs text-foundation-muted-fg">{subKey}</span>
                              <span className="text-sm font-medium text-foundation-fg">{String(subValue)}</span>
                            </div>
                          ))}
                        </div>
                      </div>
                    );
                  }
                  return (
                    <div key={key} className="rounded-[var(--radius-panel)] p-4 flex flex-col gap-1" style={{ backgroundColor: 'var(--foundation-surface)', border: '1px solid var(--foundation-border)' }}>
                      <span className="text-xs text-foundation-muted-fg capitalize">{key}</span>
                      <span className="text-sm font-medium text-foundation-fg truncate" title={String(value)}>{String(value)}</span>
                    </div>
                  );
                })}
              </div>
            ) : (
              <div className="flex flex-col items-center justify-center h-full text-foundation-muted-fg gap-4">
                <Box size={48} className="opacity-30" />
                <p>No twin data reported yet</p>
              </div>
            )}
          </div>
        )}

        {/* JSON Mode - read only */}
        {viewMode === 'json' && (
          <div className="h-full relative">
            <div className="absolute top-2 right-4 z-10 bg-[var(--accent)]/20 text-[var(--accent)] text-[10px] px-2 py-0.5 rounded font-medium border border-[var(--accent)]/30 backdrop-blur">
              READ ONLY
            </div>
            <DiffEditor
              height="100%"
              language="json"
              theme="vs-dark"
              original={currentJson}
              modified={currentJson}
              options={{
                readOnly: true,
                minimap: { enabled: false },
                renderSideBySide: false,
                scrollBeyondLastLine: false,
                fontSize: 13,
                fontFamily: 'JetBrains Mono, monospace',
                padding: { top: 16 }
              }}
            />
          </div>
        )}

        {/* Diff Mode */}
        {viewMode === 'diff' && (
          <div className="h-full flex flex-col">
            <div className="p-2 border-b border-foundation-border flex items-center gap-3 text-xs flex-wrap">
              <div className="flex items-center gap-2">
                <GitCommit size={14} style={{ color: 'var(--danger)' }} />
                <span className="text-foundation-muted-fg">Previous State</span>
              </div>
              <Select aria-label="History transition type" value={historyType} onChange={event => setHistoryType(event.target.value)}>
                <option value="">All transitions</option><option value="REPORTED_STATE_CHANGED">Reported</option>
                <option value="DESIRED_STATE_CHANGED">Desired</option><option value="DESIRED_STATE_CLEARED">Desired cleared</option>
                <option value="CONVERGENCE_CHANGED">Verification</option>
              </Select>
              <Select aria-label="History transition" value={selectedTransition} onChange={event => setSelectedTransition(Number(event.target.value))}>
                {history.length === 0 && <option value={0}>No durable transitions</option>}
                {history.map((item, index) => <option key={item.transitionId} value={index}>{new Date(item.transitionedAt).toLocaleString()} · {item.transitionType} · {item.source}</option>)}
              </Select>
              {historyCursor && <Button variant="outline" onClick={() => void loadHistory(historyCursor)}>Load more</Button>}
              {historyError && <span role="alert" className="text-danger">{historyError}</span>}
              <div className="flex items-center gap-2">
                <GitCommit size={14} style={{ color: 'var(--success)' }} />
                <span className="text-foundation-muted-fg">Current State</span>
              </div>
            </div>
            <div className="flex-1 min-h-0 relative">
              <DiffEditor
                height="100%"
                language="json"
                theme="vs-dark"
                original={previousJson}
                modified={changedJson}
                options={{
                  readOnly: true,
                  minimap: { enabled: false },
                  renderSideBySide: true,
                  scrollBeyondLastLine: false,
                  fontSize: 13,
                  fontFamily: 'JetBrains Mono, monospace',
                  padding: { top: 16 },
                  renderIndicators: true,
                  enableSplitViewResizing: true
                }}
              />
            </div>
          </div>
        )}

      </GlassPanel>
    </div>
  );
}
