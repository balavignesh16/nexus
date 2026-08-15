import { useDeveloperMode } from './DeviceWorkspace';
import { motion, AnimatePresence } from 'framer-motion';
import { Bug, Hash } from 'lucide-react';
import { useState } from 'react';

interface DebugOverlayProps {
  title: string;
  data: any;
  correlationId?: string;
  repository?: string;
}

export function DebugOverlay({ title, data, correlationId, repository }: DebugOverlayProps) {
  const isDevMode = useDeveloperMode();
  const [isOpen, setIsOpen] = useState(false);

  if (!isDevMode) return null;

  return (
    <div className="absolute top-2 right-2 z-50">
      <button
        onClick={(e) => { e.preventDefault(); e.stopPropagation(); setIsOpen(!isOpen); }}
        className={`p-1.5 rounded bg-[#1a1a24] border ${isOpen ? 'border-amber-500/50 text-amber-500' : 'border-[#1f1f28] text-muted-foreground hover:border-amber-500/30 hover:text-amber-400'} transition-colors cursor-pointer shadow-sm`}
        title="Debug Overlay"
      >
        <Bug size={14} />
      </button>

      <AnimatePresence>
        {isOpen && (
          <motion.div
            initial={{ opacity: 0, y: -10, scale: 0.95 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: -10, scale: 0.95 }}
            className="absolute top-full right-0 mt-2 w-72 bg-[#0a0a0f] border border-amber-500/30 rounded-lg shadow-xl shadow-black/50 overflow-hidden flex flex-col z-[100]"
          >
            <div className="bg-amber-500/10 border-b border-amber-500/20 px-3 py-2 flex items-center justify-between">
              <span className="text-xs font-semibold text-amber-500">{title} Debug</span>
              {repository && (
                <span className="text-[10px] font-mono text-amber-500/70 bg-amber-500/10 px-1.5 py-0.5 rounded border border-amber-500/20">{repository}</span>
              )}
            </div>
            
            <div className="p-3 overflow-y-auto max-h-64 custom-scrollbar bg-[#060b14]">
              {correlationId && (
                <div className="mb-3 flex items-center gap-2 text-[10px] bg-[#12121a] border border-[#1f1f28] p-2 rounded text-muted-foreground">
                  <Hash size={12} className="text-blue-400 shrink-0" />
                  <span className="font-mono truncate select-all">{correlationId}</span>
                </div>
              )}
              
              <div className="text-[10px] font-mono text-emerald-400 whitespace-pre-wrap break-all select-text">
                {JSON.stringify(data, null, 2)}
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
