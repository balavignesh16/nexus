import { apiClient } from './apiClient';

export type AnalyticsRangeParam = '1h' | '6h' | '24h' | '7d' | '30d';
export type AnalyticsMetricParam = 'telemetry' | 'events' | 'rule-matches' | 'actions' | 'pipeline';
export type AnalyticsBucketParam = 'minute' | '5minutes' | 'hour' | 'day';

/**
 * Mirrors com.nexus.analytics.api.dto.AnalyticsSummaryResponse exactly.
 * Every field is a real database aggregate - device counts/connectivity
 * are current state, everything else is scoped to [from, to).
 */
export interface AnalyticsSummary {
  range: string;
  from: string;
  to: string;
  totalDeviceCount: number;
  deviceCountByType: Record<string, number>;
  connectivityDistribution: Record<string, number>;
  eventCount: number;
  ruleMatchCount: number;
  actionStatusDistribution: Record<string, number>;
  commandAckDistribution: Record<string, number>;
}

/**
 * Mirrors com.nexus.analytics.api.dto.TimeSeriesResponse exactly.
 * `from`/`to` report the ACTUAL window queried - for the `pipeline`
 * metric this may be narrower than the requested range (7-day retention
 * ceiling); always render these fields, never assume they equal the
 * requested range.
 */
export interface AnalyticsTimeSeries {
  metric: string;
  from: string;
  to: string;
  bucket: string;
  points: { timestamp: string; value: number }[];
}

export const analyticsApi = {
  getSummary: (range: AnalyticsRangeParam) =>
    apiClient.get<AnalyticsSummary>(`/analytics/summary?range=${range}`),

  getTimeSeries: (metric: AnalyticsMetricParam, range: AnalyticsRangeParam, bucket?: AnalyticsBucketParam) =>
    apiClient.get<AnalyticsTimeSeries>(
      `/analytics/timeseries?metric=${metric}&range=${range}${bucket ? `&bucket=${bucket}` : ''}`
    ),
};
