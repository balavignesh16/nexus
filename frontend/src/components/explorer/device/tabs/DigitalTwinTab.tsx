import { useState } from 'react';
import { useDevice, useLatestTwins } from '../../../../hooks/queries';
import { Skeleton } from '../../../ui/Skeleton';
import { Box, Code, Edit3, Save, X, GitCommit, FileDiff } from 'lucide-react';
import { DiffEditor } from '@monaco-editor/react';
import { useDeviceTwinState } from '../../../../hooks/useDeviceTwinState';
import { formatAge } from '../../../../utils/formatAge';
import { DataSourceBadge } from '../../../ui/DataSourceBadge';

export function DigitalTwinTab({ deviceId }: { deviceId: string }) {
  const { data: device } = useDevice(deviceId);
  const { data: twins = [] } = useLatestTwins(100);
  const [isEditing, setIsEditing] = useState(false);
  const [viewMode, setViewMode] = useState<'visual' | 'json' | 'diff'>('visual');
  const twinState = useDeviceTwinState(deviceId);
  const connectivity = twinState.connectivity?.status ?? 'UNKNOWN';

  if (!device) return <Skeleton className="w-full h-full" />;

  const currentTwin = twins.find((t: any) => t.deviceId === deviceId) || null;
  const previousTwin = twins.filter((t: any) => t.deviceId === deviceId)[1] || null;

  const currentJson = currentTwin ? JSON.stringify(currentTwin, null, 2) : '{\n  "status": "No twin data available"\n}';
  const previousJson = previousTwin ? JSON.stringify(previousTwin, null, 2) : currentJson;

  const handleSave = () => {
    setIsEditing(false);
    // In future phases: save twin updates
  };

  return (
    <div className="p-6 h-full flex flex-col space-y-6 animate-in slide-in-from-bottom-2 fade-in duration-300">
      
      {/* Header & Controls */}
      <div className="flex items-center justify-between bg-card/50 backdrop-blur border border-border/50 rounded-xl p-4 shadow-sm">
        <div className="flex items-center gap-3">
          <div className="p-2 bg-primary/10 rounded-lg">
            <Box size={20} className="text-primary" />
          </div>
          <div>
            <h3 className="font-semibold text-foreground">Digital Twin</h3>
            <p className="text-xs text-muted-foreground">Virtual representation of {device.name}</p>
          </div>
        </div>
        
        <div className="flex items-center gap-3">
          <div className="flex bg-muted/50 rounded-md p-1 border border-border/50">
            <button 
              onClick={() => setViewMode('visual')}
              className={`px-3 py-1.5 text-xs font-medium rounded transition-colors ${viewMode === 'visual' ? 'bg-background shadow-sm text-foreground' : 'text-muted-foreground hover:text-foreground'}`}
            >
              Visual
            </button>
            <button 
              onClick={() => setViewMode('json')}
              className={`px-3 py-1.5 text-xs font-medium rounded transition-colors flex items-center gap-1 ${viewMode === 'json' ? 'bg-background shadow-sm text-foreground' : 'text-muted-foreground hover:text-foreground'}`}
            >
              <Code size={12} /> JSON
            </button>
            <button 
              onClick={() => setViewMode('diff')}
              className={`px-3 py-1.5 text-xs font-medium rounded transition-colors flex items-center gap-1 ${viewMode === 'diff' ? 'bg-background shadow-sm text-foreground' : 'text-muted-foreground hover:text-foreground'}`}
            >
              <FileDiff size={12} /> Diffs
            </button>
          </div>
          
          <div className="w-px h-6 bg-border mx-1" />
          
          {isEditing ? (
            <>
              <button onClick={() => setIsEditing(false)} className="flex items-center gap-2 px-3 py-1.5 bg-muted hover:bg-muted/80 text-foreground text-xs font-medium rounded transition-colors">
                <X size={14} /> Cancel
              </button>
              <button onClick={handleSave} className="flex items-center gap-2 px-3 py-1.5 bg-primary hover:bg-primary/90 text-primary-foreground text-xs font-medium rounded transition-colors">
                <Save size={14} /> Save Changes
              </button>
            </>
          ) : (
            <button onClick={() => { setViewMode('json'); setIsEditing(true); }} className="flex items-center gap-2 px-3 py-1.5 bg-primary/10 hover:bg-primary/20 text-primary text-xs font-medium rounded transition-colors">
              <Edit3 size={14} /> Edit Twin
            </button>
          )}
        </div>
      </div>

      {/* Real Digital Twin state (Phase 8) - evidence-based, no fabricated
          quality score. Connectivity is LIVE; telemetry recency and field
          count are CALCULATED from this device's own real telemetry
          history; anything not derivable is shown as Unavailable rather
          than invented. */}
      <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 shrink-0">
        <div className="bg-card/50 border border-border/50 rounded-xl p-4 flex flex-col gap-1">
          <span className="text-[10px] text-muted-foreground uppercase tracking-wider flex items-center gap-1.5">
            Connectivity <DataSourceBadge classification="LIVE" />
          </span>
          <span className={`text-sm font-bold ${
            connectivity === 'ONLINE' ? 'text-success' : connectivity === 'OFFLINE' ? 'text-destructive' : 'text-[var(--data-unavailable)]'
          }`}>
            {connectivity}
          </span>
        </div>
        <div className="bg-card/50 border border-border/50 rounded-xl p-4 flex flex-col gap-1">
          <span className="text-[10px] text-muted-foreground uppercase tracking-wider flex items-center gap-1.5">
            Last Telemetry <DataSourceBadge classification="CALCULATED" />
          </span>
          <span className="text-sm font-bold text-foreground" title={twinState.lastTelemetryAt ? new Date(twinState.lastTelemetryAt).toLocaleString() : undefined}>
            {twinState.telemetryLoading ? '…' : twinState.lastTelemetryAt ? formatAge(twinState.lastTelemetryAt) : 'Unavailable'}
          </span>
        </div>
        <div className="bg-card/50 border border-border/50 rounded-xl p-4 flex flex-col gap-1">
          <span className="text-[10px] text-muted-foreground uppercase tracking-wider flex items-center gap-1.5">
            Reporting Fields <DataSourceBadge classification="CALCULATED" />
          </span>
          <span className="text-sm font-bold text-foreground">
            {twinState.reportedFields.length > 0 ? twinState.reportedFields.join(', ') : 'None reported'}
          </span>
        </div>
      </div>

      {/* Content Area */}
      <div className="flex-1 min-h-0 bg-card border border-border/50 rounded-xl overflow-hidden shadow-sm relative">
        
        {/* Visual Mode */}
        {viewMode === 'visual' && (
          <div className="p-6 overflow-y-auto h-full custom-scrollbar">
            {currentTwin ? (
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                {Object.entries(currentTwin).map(([key, value]) => {
                  if (typeof value === 'object' && value !== null) {
                    return (
                      <div key={key} className="col-span-full border border-border/50 rounded-lg p-4 bg-muted/10">
                        <h4 className="text-sm font-semibold capitalize mb-4 pb-2 border-b border-border/50">{key}</h4>
                        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                          {Object.entries(value).map(([subKey, subValue]) => (
                            <div key={subKey} className="flex flex-col gap-1">
                              <span className="text-xs text-muted-foreground">{subKey}</span>
                              <span className="text-sm font-medium">{String(subValue)}</span>
                            </div>
                          ))}
                        </div>
                      </div>
                    );
                  }
                  return (
                    <div key={key} className="border border-border/50 rounded-lg p-4 bg-muted/10 flex flex-col gap-1">
                      <span className="text-xs text-muted-foreground capitalize">{key}</span>
                      <span className="text-sm font-medium truncate" title={String(value)}>{String(value)}</span>
                    </div>
                  );
                })}
              </div>
            ) : (
              <div className="flex flex-col items-center justify-center h-full text-muted-foreground gap-4">
                <Box size={48} className="opacity-20" />
                <p>No twin data reported yet</p>
              </div>
            )}
          </div>
        )}

        {/* JSON / Edit Mode */}
        {viewMode === 'json' && (
          <div className="h-full relative">
            {!isEditing && (
              <div className="absolute top-2 right-4 z-10 bg-primary/20 text-primary text-[10px] px-2 py-0.5 rounded font-medium border border-primary/30 backdrop-blur">
                READ ONLY
              </div>
            )}
            <DiffEditor
              height="100%"
              language="json"
              theme="vs-dark"
              original={currentJson}
              modified={currentJson}
              options={{
                readOnly: !isEditing,
                minimap: { enabled: false },
                renderSideBySide: false, // Single editor when not diffing essentially, though DiffEditor needs two
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
            <div className="p-2 border-b border-border/50 bg-muted/20 flex items-center gap-4 text-xs">
              <div className="flex items-center gap-2">
                <GitCommit size={14} className="text-danger" />
                <span className="text-muted-foreground">Previous State</span>
              </div>
              <div className="flex items-center gap-2">
                <GitCommit size={14} className="text-success" />
                <span className="text-muted-foreground">Current State</span>
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

      </div>
    </div>
  );
}
