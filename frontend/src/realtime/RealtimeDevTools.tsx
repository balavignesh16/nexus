import { useEffect, useState } from 'react';
import { RealtimeManager } from './RealtimeManager';
import { useRealtimeContext } from './RealtimeContext';

/** Lightweight development overlay for inspecting the shared realtime client. */
export function RealtimeDevTools() {
  const realtime = useRealtimeContext();
  const [open, setOpen] = useState(false);
  const [fps, setFps] = useState(0);

  useEffect(() => {
    const toggle = (event: KeyboardEvent) => {
      if (event.ctrlKey && event.shiftKey && event.key.toLowerCase() === 'd') {
        event.preventDefault();
        setOpen(value => !value);
      }
    };
    window.addEventListener('keydown', toggle);
    return () => window.removeEventListener('keydown', toggle);
  }, []);

  useEffect(() => {
    let frames = 0;
    let frameId = 0;
    let started = performance.now();
    const measure = (now: number) => {
      frames++;
      if (now - started >= 1000) { setFps(frames); frames = 0; started = now; }
      frameId = requestAnimationFrame(measure);
    };
    frameId = requestAnimationFrame(measure);
    return () => cancelAnimationFrame(frameId);
  }, []);

  if (!open) return null;
  const bufferEntries = RealtimeManager.buffer.totalSize();
  return <aside className="fixed right-4 bottom-4 z-[100] w-72 rounded-xl border border-blue-400/40 bg-[#09111f]/95 p-4 text-xs text-slate-200 shadow-2xl backdrop-blur">
    <div className="mb-3 flex items-center justify-between"><strong>Realtime DevTools</strong><button onClick={() => setOpen(false)} className="text-blue-300">Close</button></div>
    <dl className="grid grid-cols-2 gap-y-2">
      <dt>State</dt><dd className="text-right text-blue-300">{realtime.connectionState}</dd>
      <dt>Messages/sec</dt><dd className="text-right">{realtime.messagesPerSec}</dd>
      <dt>Bytes/sec</dt><dd className="text-right">{realtime.bytesPerSec}</dd>
      <dt>Dropped</dt><dd className="text-right">{realtime.droppedMessages}</dd>
      <dt>Reconnects</dt><dd className="text-right">{realtime.reconnectAttempts}</dd>
      <dt>Clock offset</dt><dd className="text-right">{realtime.clientOffset} ms</dd>
      <dt>Buffer entries</dt><dd className="text-right">{bufferEntries}</dd>
      <dt>Est. memory</dt><dd className="text-right">~{Math.ceil(bufferEntries * 0.5)} KB</dd>
      <dt>FPS</dt><dd className="text-right">{fps}</dd>
    </dl>
    <p className="mt-3 border-t border-white/10 pt-2 text-[10px] text-slate-400">Subscriptions: {realtime.activeSubscriptions.join(', ') || 'none'}</p>
  </aside>;
}
