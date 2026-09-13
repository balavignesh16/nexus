import { describe, it, expect, vi, beforeEach } from 'vitest';
import { analyticsApi } from './analyticsApi';
import { apiClient } from './apiClient';

vi.mock('./apiClient', async () => {
  const actual = await vi.importActual<typeof import('./apiClient')>('./apiClient');
  return {
    ...actual,
    apiClient: { ...actual.apiClient, get: vi.fn() },
  };
});

describe('analyticsApi - real backend aggregation endpoints only', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('getSummary calls the real bounded-range summary endpoint', () => {
    analyticsApi.getSummary('24h');
    expect(apiClient.get).toHaveBeenCalledWith('/analytics/summary?range=24h');
  });

  it('getTimeSeries calls the real timeseries endpoint with metric and range', () => {
    analyticsApi.getTimeSeries('telemetry', '7d');
    expect(apiClient.get).toHaveBeenCalledWith('/analytics/timeseries?metric=telemetry&range=7d');
  });

  it('getTimeSeries includes an explicit bucket when provided', () => {
    analyticsApi.getTimeSeries('events', '1h', 'minute');
    expect(apiClient.get).toHaveBeenCalledWith('/analytics/timeseries?metric=events&range=1h&bucket=minute');
  });
});
