export const Topics = {
  telemetry: '/topic/telemetry',
  twins: '/topic/twins',
  events: '/topic/events',
  rules: '/topic/rules',
  actions: '/topic/actions',
  alerts: '/topic/alerts',
  analytics: '/topic/analytics',
  platform: '/topic/platform'
} as const;

export type TopicName = keyof typeof Topics;
export type TopicPath = typeof Topics[TopicName];
