import { createContext, useContext, useMemo, useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import type { IRepositoryFactory } from './types';
import { DataSourceResolver } from './DataSourceResolver';
import { ScenarioEngine } from '../simulation/ScenarioEngine';
import { FEATURES } from '../config/features';
import { RealtimeRepositorySync } from '../realtime/RealtimeRepositorySync';

const defaultResolver = new DataSourceResolver();

const RepositoryContext = createContext<IRepositoryFactory>({
  device: defaultResolver,
  telemetry: defaultResolver,
  events: defaultResolver,
  rules: defaultResolver,
  actions: defaultResolver,
  pipeline: defaultResolver,
});

export function RepositoryProvider({ children }: { children: ReactNode }) {
  // Subscribe to ScenarioEngine to force re-renders for simulator ticks
  const [, setTick] = useState(0);

  useEffect(() => {
    if (FEATURES.LIVE_SIMULATOR) {
      ScenarioEngine.start();
      const unsubscribe = ScenarioEngine.subscribe(() => {
        setTick(t => t + 1);
      });
      return () => {
        unsubscribe();
        ScenarioEngine.stop();
      };
    }
  }, []);

  const factory = useMemo<IRepositoryFactory>(() => {
    const r = new DataSourceResolver();
    return {
      device: r,
      telemetry: r,
      events: r,
      rules: r,
      actions: r,
      pipeline: r,
    };
  }, []);

  return (
    <RepositoryContext.Provider value={factory}>
      <RealtimeRepositorySync />
      {children}
    </RepositoryContext.Provider>
  );
}

export function useRepositories() {
  const context = useContext(RepositoryContext);
  if (!context) {
    throw new Error('useRepositories must be used within a RepositoryProvider');
  }
  return context;
}
