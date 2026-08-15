import React, { createContext, useContext, useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { pollingManager, type PollingStats } from './PollingManager';

const LiveUpdateContext = createContext<PollingStats | null>(null);

export const LiveUpdateProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const queryClient = useQueryClient();
  const [stats, setStats] = useState<PollingStats>(pollingManager.getStats());

  useEffect(() => {
    // Initialize polling manager with the query client
    pollingManager.init(queryClient);

    // Subscribe to stats changes
    const unsubscribe = pollingManager.subscribe((newStats) => {
      setStats(newStats);
    });

    return () => {
      unsubscribe();
      pollingManager.destroy();
    };
  }, [queryClient]);

  return (
    <LiveUpdateContext.Provider value={stats}>
      {children}
    </LiveUpdateContext.Provider>
  );
};

export const useLiveManager = () => {
  const context = useContext(LiveUpdateContext);
  if (!context) {
    throw new Error('useLiveManager must be used within a LiveUpdateProvider');
  }
  return context;
};
