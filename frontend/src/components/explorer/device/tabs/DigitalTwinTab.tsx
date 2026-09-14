import { useState } from 'react';
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

export function DigitalTwinTab({ deviceId }: { deviceId: string }) {
  const { data: device } = useDevice(deviceId);
  const { data: twins = [] } = useLatestTwins(100);
  const [viewMode, setViewMode] = useState<'visual' | 'json' | 'diff'>('visual');
  const twinState = useDeviceTwinState(deviceId);
  const connectivity = twinState.connectivity?.status ?? 'UNKNOWN';

  if (!device) return <LoadingBlock className="h-full mt-6" />;

  const currentTwin = twins.find((t: any) => t.deviceId === deviceId) || null;
  const previousTwin = twins.filter((t: any) => t.deviceId === deviceId)[1] || null;

  const currentJson = currentTwin ? JSON.stringify(currentTwin, null, 2) : '{\n  "status": "No twin data available"\n}';
  const previousJson = previousTwin ? JSON.stringify(previousTwin, null, 2) : currentJson;

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

      {/* Content Area */}
      <GlassPanel level="glass" radius="panel-lg" className="flex-1 min-h-0 overflow-hidden relative">

        {/* Visual Mode */}
        {viewMode === 'visual' && (
          <div className="p-6 overflow-y-auto h-full custom-scrollbar">
            {currentTwin ? (
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                {Object.entries(currentTwin).map(([key, value]) => {
                  if (typeof value === 'object' && value !== null) {
                    return (
                      <div key={key} className="col-span-full rounded-[var(--radius-panel)] p-4" style={{ backgroundColor: 'var(--foundation-surface)', border: '1px solid var(--foundation-border)' }}>
                        <h4 className="text-sm font-semibold capitalize mb-3 pb-2 border-b border-foundation-border text-foundation-fg">{key}</h4>
                        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                          {Object.entries(value).map(([subKey, subValue]) => (
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
            <div className="p-2 border-b border-foundation-border flex items-center gap-4 text-xs">
              <div className="flex items-center gap-2">
                <GitCommit size={14} style={{ color: 'var(--danger)' }} />
                <span className="text-foundation-muted-fg">Previous State</span>
              </div>
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
                modified={currentJson}
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
