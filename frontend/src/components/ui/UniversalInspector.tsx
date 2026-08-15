import { X, ExternalLink, Hash, Tag, Clock, Code } from 'lucide-react';
import { motion, AnimatePresence } from 'framer-motion';

export interface InspectorItem {
  id: string;
  type: 'Device' | 'Twin' | 'Event' | 'Rule' | 'Action' | 'Telemetry';
  title: string;
  subtitle?: string;
  status?: string;
  statusColor?: string;
  properties: Record<string, any>;
  rawJson?: any;
  history?: any[];
  correlationId?: string;
}

interface UniversalInspectorProps {
  open: boolean;
  onClose: () => void;
  item: InspectorItem | null;
}

export function UniversalInspector({ open, onClose, item }: UniversalInspectorProps) {
  if (!item) return null;

  return (
    <AnimatePresence>
      {open && (
        <>
          {/* Subtle backdrop */}
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={onClose}
            className="fixed inset-0 bg-background/50 backdrop-blur-[2px] z-40"
          />

          {/* Slide-over panel */}
          <motion.div
            initial={{ x: '100%' }}
            animate={{ x: 0 }}
            exit={{ x: '100%' }}
            transition={{ type: 'spring', bounce: 0, duration: 0.3 }}
            className="fixed inset-y-0 right-0 z-50 w-full max-w-[500px] bg-[#0a0a0f] border-l border-[#1f1f28] shadow-2xl flex flex-col"
          >
            {/* Header */}
            <div className="shrink-0 p-5 border-b border-[#1f1f28] bg-[#060b14] flex items-start justify-between">
              <div>
                <div className="flex items-center gap-2 mb-2">
                  <span className="px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider bg-blue-500/10 text-blue-400 border border-blue-500/20">
                    {item.type}
                  </span>
                  {item.status && (
                    <span className={`px-2 py-0.5 rounded text-[10px] font-bold uppercase tracking-wider border ${
                      item.statusColor === 'success' ? 'bg-success/10 text-success border-success/20' :
                      item.statusColor === 'warning' ? 'bg-warning/10 text-warning border-warning/20' :
                      item.statusColor === 'destructive' ? 'bg-destructive/10 text-destructive border-destructive/20' :
                      'bg-muted/10 text-muted-foreground border-border'
                    }`}>
                      {item.status}
                    </span>
                  )}
                </div>
                <h2 className="text-xl font-bold text-foreground">{item.title}</h2>
                {item.subtitle && <p className="text-sm text-muted-foreground mt-1">{item.subtitle}</p>}
                
                {item.correlationId && (
                  <div className="flex items-center gap-2 mt-3 text-xs font-mono text-muted-foreground bg-[#13131a] px-2 py-1 rounded border border-[#1f1f28] w-fit">
                    <Hash size={12} className="text-purple-400" />
                    {item.correlationId}
                  </div>
                )}
              </div>
              <div className="flex items-center gap-2">
                <button className="p-2 hover:bg-[#13131a] rounded-md text-muted-foreground hover:text-foreground transition-colors">
                  <ExternalLink size={16} />
                </button>
                <button 
                  onClick={onClose}
                  className="p-2 hover:bg-[#13131a] rounded-md text-muted-foreground hover:text-foreground transition-colors"
                >
                  <X size={16} />
                </button>
              </div>
            </div>

            {/* Scrollable Content */}
            <div className="flex-1 overflow-y-auto custom-scrollbar p-6 space-y-8 bg-[#060b14]">
              
              {/* Properties */}
              <div className="space-y-3">
                <h3 className="text-xs font-bold uppercase tracking-wider text-muted-foreground flex items-center gap-2">
                  <Tag size={14} /> Properties
                </h3>
                <div className="bg-[#13131a] rounded-xl border border-[#1f1f28] overflow-hidden">
                  {Object.entries(item.properties).map(([key, value], idx) => (
                    <div key={key} className={`flex items-center p-3 text-sm ${idx !== 0 ? 'border-t border-[#1f1f28]' : ''}`}>
                      <div className="w-1/3 text-muted-foreground font-medium">{key}</div>
                      <div className="w-2/3 text-foreground font-mono truncate">{String(value)}</div>
                    </div>
                  ))}
                </div>
              </div>

              {/* Raw JSON */}
              {item.rawJson && (
                <div className="space-y-3">
                  <h3 className="text-xs font-bold uppercase tracking-wider text-muted-foreground flex items-center gap-2">
                    <Code size={14} /> Raw JSON
                  </h3>
                  <div className="bg-[#13131a] rounded-xl border border-[#1f1f28] p-4 overflow-x-auto custom-scrollbar">
                    <pre className="text-xs font-mono text-muted-foreground">
                      {JSON.stringify(item.rawJson, null, 2)}
                    </pre>
                  </div>
                </div>
              )}

              {/* History / Timeline */}
              {item.history && (
                <div className="space-y-3">
                  <h3 className="text-xs font-bold uppercase tracking-wider text-muted-foreground flex items-center gap-2">
                    <Clock size={14} /> History
                  </h3>
                  <div className="relative pl-3 space-y-6 before:absolute before:inset-y-0 before:left-[11px] before:w-px before:bg-[#1f1f28] mt-4">
                    {item.history.map((entry, idx) => (
                      <div key={idx} className="relative">
                        <div className="absolute -left-[14px] top-1.5 w-2 h-2 rounded-full bg-blue-500 border-[3px] border-[#060b14] box-content"></div>
                        <div className="bg-[#13131a] border border-[#1f1f28] rounded-lg p-3">
                          <div className="flex justify-between items-center mb-1">
                            <span className="font-semibold text-sm">{entry.action}</span>
                            <span className="text-[10px] text-muted-foreground font-mono">{entry.time}</span>
                          </div>
                          <p className="text-xs text-muted-foreground">{entry.details}</p>
                        </div>
                      </div>
                    ))}
                  </div>
                </div>
              )}

            </div>
          </motion.div>
        </>
      )}
    </AnimatePresence>
  );
}
