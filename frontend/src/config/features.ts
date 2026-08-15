export const FEATURES = {
  // Global feature flags
  DEMO_MODE: true,
  LIVE_BACKEND: false,
  LIVE_SIMULATOR: true,
  MOCK_ALERTS: true,
  MOCK_RULES: true,
  MOCK_EVENTS: true,
  MOCK_ACTIONS: true,
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
