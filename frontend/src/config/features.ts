export const FEATURES = {
  // Global feature flags. Overridable at build time via VITE_DEMO_MODE so a
  // demo build can still be produced without editing source, but the
  // shipped default is now live-backend, not demo.
  DEMO_MODE: import.meta.env.VITE_DEMO_MODE === 'true',
  LIVE_BACKEND: import.meta.env.VITE_DEMO_MODE !== 'true',
  LIVE_SIMULATOR: import.meta.env.VITE_DEMO_MODE === 'true',
  MOCK_ALERTS: false,
  MOCK_RULES: false,
  MOCK_EVENTS: false,
  MOCK_ACTIONS: false,
  SHOW_EXPERIMENTAL: true,

  // Real-time transport configuration. These are intentionally kept beside
  // the existing data-source flags so demo and live modes share the same UI.
  REALTIME_ENABLED: true,
  REALTIME_WS_URL: import.meta.env.VITE_WS_URL ?? 'http://localhost:8080/ws',
  REALTIME_BATCH_MS: 100,
  REALTIME_BUFFER_SIZE: 5000,
  REALTIME_REPLAY_ENABLED: true,
  REALTIME_DEV_TOOLS: true,
};
