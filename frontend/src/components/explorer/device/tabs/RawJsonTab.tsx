import { useDevice } from '../../../../hooks/queries';
import { Skeleton } from '../../../ui/Skeleton';
import { Code, Download, Copy, Check } from 'lucide-react';
import { DiffEditor } from '@monaco-editor/react';
import { useState } from 'react';

export function RawJsonTab({ deviceId }: { deviceId: string }) {
  const { data: device, isLoading } = useDevice(deviceId);
  const [copied, setCopied] = useState(false);

  if (isLoading || !device) return <Skeleton className="w-full h-full" />;

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
    <div className="p-6 h-full flex flex-col space-y-6 animate-in slide-in-from-bottom-2 fade-in duration-300">
      
      {/* Header */}
      <div className="flex items-center justify-between bg-card/50 backdrop-blur border border-border/50 rounded-xl p-4 shadow-sm shrink-0">
        <div className="flex items-center gap-3">
          <div className="p-2 bg-primary/10 rounded-lg">
            <Code size={20} className="text-primary" />
          </div>
          <div>
            <h3 className="font-semibold text-foreground">Raw Device Object</h3>
            <p className="text-xs text-muted-foreground">Complete API representation</p>
          </div>
        </div>
        
        <div className="flex items-center gap-2">
          <button 
            onClick={handleCopy}
            className="flex items-center gap-2 px-3 py-1.5 bg-muted/50 hover:bg-muted border border-border/50 rounded text-xs font-medium transition-colors text-muted-foreground hover:text-foreground"
          >
            {copied ? <Check size={14} className="text-success" /> : <Copy size={14} />}
            {copied ? 'Copied' : 'Copy JSON'}
          </button>
          <button 
            onClick={handleDownload}
            className="flex items-center gap-2 px-3 py-1.5 bg-success/10 hover:bg-success/20 text-success rounded text-xs font-medium transition-colors"
          >
            <Download size={14} /> Download
          </button>
        </div>
      </div>

      {/* Editor Area */}
      <div className="flex-1 min-h-0 bg-card border border-border/50 rounded-xl overflow-hidden shadow-sm relative">
        <div className="absolute top-2 right-4 z-10 bg-primary/20 text-primary text-[10px] px-2 py-0.5 rounded font-medium border border-primary/30 backdrop-blur">
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
            renderSideBySide: false, // forces single editor view
            scrollBeyondLastLine: false,
            fontSize: 13,
            fontFamily: 'JetBrains Mono, monospace',
            padding: { top: 16 }
          }}
        />
      </div>

    </div>
  );
}
