import React from 'react';
import { Activity, Pause, WifiOff, AlertTriangle, Clock } from 'lucide-react';
import { useLiveManager } from './LiveUpdateProvider';

export type WidgetState = 'LOADING' | 'LIVE' | 'PAUSED' | 'OFFLINE' | 'ERROR' | 'STALE';

interface LiveWidgetWrapperProps {
  title: string;
  children: React.ReactNode;
  state: WidgetState;
  errorMessage?: string;
  lastUpdateMs?: number;
  onPauseToggle?: () => void;
  actions?: React.ReactNode;
}

export function LiveWidgetWrapper({
  title,
  children,
  state,
  errorMessage,
  lastUpdateMs,
  onPauseToggle,
  actions
}: LiveWidgetWrapperProps) {
  const stats = useLiveManager();
  
  // Resolve effective state taking global connection into account
  let effectiveState = state;
  if (stats.status === 'OFFLINE' && state !== 'ERROR') {
    effectiveState = 'OFFLINE';
  } else if (state === 'LIVE' && lastUpdateMs && (Date.now() - lastUpdateMs > 30000)) {
    effectiveState = 'STALE';
  }

  const getIndicator = () => {
    switch (effectiveState) {
      case 'LIVE':
        return (
          <div className="flex items-center gap-1.5 text-success bg-success/10 px-2 py-0.5 rounded text-[10px] font-bold tracking-wider cursor-pointer" onClick={onPauseToggle} title="Click to Pause">
            <span className="w-1.5 h-1.5 rounded-full bg-success animate-pulse"></span>
            LIVE
          </div>
        );
      case 'PAUSED':
        return (
          <div className="flex items-center gap-1.5 text-warning bg-warning/10 px-2 py-0.5 rounded text-[10px] font-bold tracking-wider cursor-pointer" onClick={onPauseToggle} title="Click to Resume">
            <Pause size={10} />
            PAUSED
          </div>
        );
      case 'OFFLINE':
        return (
          <div className="flex items-center gap-1.5 text-muted-foreground bg-muted/50 px-2 py-0.5 rounded text-[10px] font-bold tracking-wider">
            <WifiOff size={10} />
            OFFLINE
          </div>
        );
      case 'STALE':
        return (
          <div className="flex items-center gap-1.5 text-warning bg-warning/10 px-2 py-0.5 rounded text-[10px] font-bold tracking-wider">
            <Clock size={10} />
            STALE
          </div>
        );
      case 'ERROR':
        return (
          <div className="flex items-center gap-1.5 text-destructive bg-destructive/10 px-2 py-0.5 rounded text-[10px] font-bold tracking-wider" title={errorMessage}>
            <AlertTriangle size={10} />
            ERROR
          </div>
        );
      case 'LOADING':
        return (
          <div className="flex items-center gap-1.5 text-muted-foreground bg-muted/50 px-2 py-0.5 rounded text-[10px] font-bold tracking-wider">
            <Activity size={10} className="animate-spin" />
            LOADING
          </div>
        );
    }
  };

  return (
    <div className="bg-card border border-border rounded-lg overflow-hidden flex flex-col h-full relative">
      <div className="h-10 px-4 border-b border-border flex items-center justify-between bg-card/50">
        <h3 className="text-sm font-semibold tracking-wide text-foreground uppercase">{title}</h3>
        <div className="flex items-center gap-3">
          {actions}
          {getIndicator()}
        </div>
      </div>
      
      <div className="flex-1 overflow-hidden relative">
        {effectiveState === 'LOADING' ? (
          <div className="absolute inset-0 bg-background/50 backdrop-blur-[1px] flex items-center justify-center z-10">
            <div className="flex flex-col items-center text-muted-foreground gap-2">
              <Activity className="animate-spin" size={24} />
              <span className="text-xs">Connecting...</span>
            </div>
          </div>
        ) : effectiveState === 'OFFLINE' ? (
          <div className="absolute inset-0 bg-background/50 backdrop-blur-[1px] flex items-center justify-center z-10">
             <div className="flex flex-col items-center text-muted-foreground gap-2">
              <WifiOff size={24} />
              <span className="text-xs">Waiting for reconnect</span>
            </div>
          </div>
        ) : effectiveState === 'ERROR' ? (
          <div className="absolute inset-0 bg-destructive/5 flex items-center justify-center z-10 p-4 text-center">
            <div className="flex flex-col items-center text-destructive gap-2">
              <AlertTriangle size={24} />
              <span className="text-xs font-medium">{errorMessage || 'An error occurred'}</span>
            </div>
          </div>
        ) : null}
        
        <div className="h-full w-full overflow-y-auto">
          {children}
        </div>
      </div>
    </div>
  );
}
