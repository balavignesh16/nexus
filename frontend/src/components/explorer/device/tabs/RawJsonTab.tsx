import { useDevice } from '../../../../hooks/queries';
import { Code, Download, Copy, Check } from 'lucide-react';
import { DiffEditor } from '@monaco-editor/react';
import { useState } from 'react';
import { Card } from '../../../foundation/Card';
import { GlassPanel } from '../../../foundation/GlassPanel';
import { Button } from '../../../foundation/Button';
import { LoadingBlock } from '../../../foundation/LoadingState';

export function RawJsonTab({ deviceId }: { deviceId: string }) {
  const { data: device, isLoading } = useDevice(deviceId);
  const [copied, setCopied] = useState(false);

  if (isLoading || !device) return <LoadingBlock className="h-64 mt-6" />;

  const jsonString = JSON.stringify(device, null, 2);

  const handleCopy = () => {
    navigator.clipboard.writeText(jsonString);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const handleDownload = () => {
    const blob = new Blob([jsonString], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `device-${deviceId}.json`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  return (
    <div className="py-6 flex flex-col gap-4 h-full">

      {/* Header */}
      <Card level="glass" padding="lg" className="shrink-0">
        <div className="flex items-center justify-between gap-4 flex-wrap">
          <div className="flex items-center gap-3">
            <div className="p-2 rounded-[var(--radius-panel)]" style={{ backgroundColor: 'color-mix(in srgb, var(--accent) 14%, transparent)', color: 'var(--accent)' }}>
              <Code size={20} />
            </div>
            <div>
              <h3 className="font-semibold text-foundation-fg">Raw Device Object</h3>
              <p className="text-xs text-foundation-muted-fg">Complete API representation</p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <Button variant="outline" size="sm" onClick={handleCopy}>
              {copied ? <Check size={14} style={{ color: 'var(--success)' }} /> : <Copy size={14} />}
              {copied ? 'Copied' : 'Copy JSON'}
            </Button>
            <Button variant="secondary" size="sm" onClick={handleDownload}>
              <Download size={14} /> Download
            </Button>
          </div>
        </div>
      </Card>

      {/* Editor Area */}
      <GlassPanel level="glass" radius="panel-lg" className="flex-1 min-h-0 overflow-hidden relative">
        <div className="absolute top-2 right-4 z-10 bg-[var(--accent)]/20 text-[var(--accent)] text-[10px] px-2 py-0.5 rounded font-medium border border-[var(--accent)]/30 backdrop-blur">
          READ ONLY
        </div>
        <DiffEditor
          height="100%"
          language="json"
          theme="vs-dark"
          original={jsonString}
          modified={jsonString}
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
      </GlassPanel>

    </div>
  );
}
