import { X, ExternalLink, Hash, Tag, Clock, Code } from 'lucide-react';
import { motion, AnimatePresence } from 'framer-motion';
import { GlassPanel } from '../foundation/GlassPanel';
import { IconButton } from '../foundation/IconButton';

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

// Maps the same real statusColor values callers already pass
// (success/warning/destructive) onto real foundation semantic tokens -
// the meaning of each status is unchanged, only the color source is.
const STATUS_COLOR: Record<string, string> = {
  success: 'var(--success)',
  warning: 'var(--warning)',
  destructive: 'var(--danger)',
};

export function UniversalInspector({ open, onClose, item }: UniversalInspectorProps) {
  if (!item) return null;

  const statusColor = item.statusColor ? (STATUS_COLOR[item.statusColor] ?? 'var(--foundation-muted-fg)') : 'var(--foundation-muted-fg)';

  return (
    <AnimatePresence>
      {open && (
        <>
          {/* Subtle backdrop - derived from the same foundation text token
              used everywhere else, not an arbitrary new color. */}
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={onClose}
            className="fixed inset-0 z-40 backdrop-blur-[2px]"
            style={{ backgroundColor: 'color-mix(in srgb, var(--foundation-fg) 35%, transparent)' }}
          />

          {/* Slide-over panel */}
          <motion.div
            initial={{ x: '100%' }}
            animate={{ x: 0 }}
            exit={{ x: '100%' }}
            transition={{ type: 'spring', bounce: 0, duration: 0.3 }}
            className="fixed inset-y-0 right-0 z-50 w-full max-w-[500px]"
          >
            <GlassPanel level="glass-strong" radius="none" className="h-full flex flex-col">
              {/* Header */}
              <div className="shrink-0 p-5 border-b flex items-start justify-between" style={{ borderColor: 'var(--foundation-border)' }}>
                <div className="min-w-0">
                  <div className="flex items-center gap-2 mb-2 flex-wrap">
                    <span
                      className="px-2 py-0.5 rounded-full text-[10px] font-bold uppercase tracking-wider"
                      style={{ backgroundColor: 'color-mix(in srgb, var(--accent) 14%, transparent)', color: 'var(--accent)' }}
                    >
                      {item.type}
                    </span>
                    {item.status && (
                      <span
                        className="px-2 py-0.5 rounded-full text-[10px] font-bold uppercase tracking-wider"
                        style={{ backgroundColor: `color-mix(in srgb, ${statusColor} 14%, transparent)`, color: statusColor }}
                      >
                        {item.status}
                      </span>
                    )}
                  </div>
                  <h2 className="text-xl font-bold text-foundation-fg truncate">{item.title}</h2>
                  {item.subtitle && <p className="text-sm text-foundation-muted-fg mt-1 truncate">{item.subtitle}</p>}

                  {item.correlationId && (
                    <div
                      className="flex items-center gap-2 mt-3 text-xs font-mono text-foundation-muted-fg px-2 py-1 rounded-[var(--radius-panel)] border w-fit"
                      style={{ backgroundColor: 'var(--foundation-bg)', borderColor: 'var(--foundation-border)' }}
                    >
                      <Hash size={12} style={{ color: 'var(--accent)' }} />
                      {item.correlationId}
                    </div>
                  )}
                </div>
                <div className="flex items-center gap-1 shrink-0">
                  <IconButton variant="ghost" aria-label="Open externally">
                    <ExternalLink size={16} />
                  </IconButton>
                  <IconButton variant="ghost" aria-label="Close inspector" onClick={onClose}>
                    <X size={16} />
                  </IconButton>
                </div>
              </div>

              {/* Scrollable Content */}
              <div className="flex-1 overflow-y-auto custom-scrollbar p-6 space-y-8" style={{ backgroundColor: 'var(--foundation-bg)' }}>

                {/* Properties */}
                <div className="space-y-3">
                  <h3 className="text-xs font-bold uppercase tracking-wider text-foundation-muted-fg flex items-center gap-2">
                    <Tag size={14} /> Properties
                  </h3>
                  <GlassPanel level="elevated" radius="panel-lg" className="overflow-hidden">
                    {Object.entries(item.properties).map(([key, value], idx) => (
                      <div
                        key={key}
                        className="flex items-center p-3 text-sm"
                        style={idx !== 0 ? { borderTop: '1px solid var(--foundation-border)' } : undefined}
                      >
                        <div className="w-1/3 text-foundation-muted-fg font-medium">{key}</div>
                        <div className="w-2/3 text-foundation-fg font-mono truncate">{String(value)}</div>
                      </div>
                    ))}
                  </GlassPanel>
                </div>

                {/* Raw JSON */}
                {item.rawJson && (
                  <div className="space-y-3">
                    <h3 className="text-xs font-bold uppercase tracking-wider text-foundation-muted-fg flex items-center gap-2">
                      <Code size={14} /> Raw JSON
                    </h3>
                    <GlassPanel level="elevated" radius="panel-lg" className="p-4 overflow-x-auto custom-scrollbar">
                      <pre className="text-xs font-mono text-foundation-muted-fg">
                        {JSON.stringify(item.rawJson, null, 2)}
                      </pre>
                    </GlassPanel>
                  </div>
                )}

                {/* History / Timeline */}
                {item.history && (
                  <div className="space-y-3">
                    <h3 className="text-xs font-bold uppercase tracking-wider text-foundation-muted-fg flex items-center gap-2">
                      <Clock size={14} /> History
                    </h3>
                    <div className="relative pl-3 space-y-6 mt-4">
                      <div className="absolute inset-y-0 left-[11px] w-px" style={{ backgroundColor: 'var(--foundation-border)' }} />
                      {item.history.map((entry, idx) => (
                        <div key={idx} className="relative">
                          <div
                            className="absolute -left-[14px] top-1.5 w-2 h-2 rounded-full box-content"
                            style={{ backgroundColor: 'var(--accent)', border: '3px solid var(--foundation-bg)' }}
                          />
                          <GlassPanel level="elevated" radius="panel" className="p-3">
                            <div className="flex justify-between items-center mb-1 gap-2">
                              <span className="font-semibold text-sm text-foundation-fg truncate">{entry.action}</span>
                              <span className="text-[10px] text-foundation-muted-fg font-mono shrink-0">{entry.time}</span>
                            </div>
                            <p className="text-xs text-foundation-muted-fg">{entry.details}</p>
                          </GlassPanel>
                        </div>
                      ))}
                    </div>
                  </div>
                )}

              </div>
            </GlassPanel>
          </motion.div>
        </>
      )}
    </AnimatePresence>
  );
}
