import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

/**
 * Step 8B: covers only the new analytics live/demo resolution added to
 * DataSourceResolver - not a retrofit of coverage for the other five
 * pre-existing domains on this class.
 *
 * FEATURES.DEMO_MODE is read once at import time by other modules in this
 * app, so it's mocked directly rather than mutated via env vars, and the
 * resolver module is re-imported fresh (vi.resetModules) per test so each
 * test's DEMO_MODE value is actually observed by a newly-constructed
 * DataSourceResolver instance.
 */
describe('DataSourceResolver - analytics live/demo resolution', () => {
  afterEach(() => {
    vi.resetModules();
    vi.restoreAllMocks();
  });

  it('live mode: returns the real backend result directly, without demo involvement', async () => {
    vi.doMock('../config/features', () => ({ FEATURES: { DEMO_MODE: false } }));
    const { DataSourceResolver } = await import('./DataSourceResolver');
    const { BackendAnalyticsRepository } = await import('./BackendRepository');
    const { DemoAnalyticsRepository } = await import('./DemoRepository');

    const realSummary = { range: '24h', from: 'x', to: 'y', totalDeviceCount: 3, deviceCountByType: {}, connectivityDistribution: {}, eventCount: 0, ruleMatchCount: 0, actionStatusDistribution: {}, commandAckDistribution: {} };
    vi.spyOn(BackendAnalyticsRepository.prototype, 'getAnalyticsSummary').mockResolvedValue(realSummary);
    const demoSpy = vi.spyOn(DemoAnalyticsRepository.prototype, 'getAnalyticsSummary');

    const resolver = new DataSourceResolver();
    const result = await resolver.getAnalyticsSummary('24h');

    expect(result).toEqual(realSummary);
    expect(demoSpy).not.toHaveBeenCalled();
  });

  it('live mode: a real backend error propagates, never silently falls back to demo data', async () => {
    vi.doMock('../config/features', () => ({ FEATURES: { DEMO_MODE: false } }));
    const { DataSourceResolver } = await import('./DataSourceResolver');
    const { BackendAnalyticsRepository } = await import('./BackendRepository');
    const { DemoAnalyticsRepository } = await import('./DemoRepository');

    vi.spyOn(BackendAnalyticsRepository.prototype, 'getAnalyticsSummary').mockRejectedValue(new Error('Backend fetch failed: 500'));
    const demoSpy = vi.spyOn(DemoAnalyticsRepository.prototype, 'getAnalyticsSummary');

    const resolver = new DataSourceResolver();

    await expect(resolver.getAnalyticsSummary('24h')).rejects.toThrow('Backend fetch failed: 500');
    expect(demoSpy).not.toHaveBeenCalled();
  });

  it('live mode: a genuinely empty/zero real summary is returned as-is, never treated as a failure', async () => {
    vi.doMock('../config/features', () => ({ FEATURES: { DEMO_MODE: false } }));
    const { DataSourceResolver } = await import('./DataSourceResolver');
    const { BackendAnalyticsRepository } = await import('./BackendRepository');
    const { DemoAnalyticsRepository } = await import('./DemoRepository');

    const genuinelyEmpty = { range: '24h', from: 'x', to: 'y', totalDeviceCount: 0, deviceCountByType: {}, connectivityDistribution: {}, eventCount: 0, ruleMatchCount: 0, actionStatusDistribution: {}, commandAckDistribution: {} };
    vi.spyOn(BackendAnalyticsRepository.prototype, 'getAnalyticsSummary').mockResolvedValue(genuinelyEmpty);
    const demoSpy = vi.spyOn(DemoAnalyticsRepository.prototype, 'getAnalyticsSummary');

    const resolver = new DataSourceResolver();
    const result = await resolver.getAnalyticsSummary('24h');

    expect(result).toEqual(genuinelyEmpty);
    expect(demoSpy).not.toHaveBeenCalled();
  });

  it('demo mode: falls back to the honest zero/empty demo repository when the backend call fails', async () => {
    vi.doMock('../config/features', () => ({ FEATURES: { DEMO_MODE: true } }));
    const { DataSourceResolver } = await import('./DataSourceResolver');
    const { BackendAnalyticsRepository } = await import('./BackendRepository');

    vi.spyOn(BackendAnalyticsRepository.prototype, 'getAnalyticsSummary').mockRejectedValue(new Error('network error'));

    const resolver = new DataSourceResolver();
    const result = await resolver.getAnalyticsSummary('24h');

    expect(result.totalDeviceCount).toBe(0);
    expect(result.deviceCountByType).toEqual({});
  });

  it('demo mode: a successful (even empty) real backend result is still preferred over the demo fallback', async () => {
    vi.doMock('../config/features', () => ({ FEATURES: { DEMO_MODE: true } }));
    const { DataSourceResolver } = await import('./DataSourceResolver');
    const { BackendAnalyticsRepository } = await import('./BackendRepository');
    const { DemoAnalyticsRepository } = await import('./DemoRepository');

    const realSummary = { range: '24h', from: 'x', to: 'y', totalDeviceCount: 0, deviceCountByType: {}, connectivityDistribution: {}, eventCount: 0, ruleMatchCount: 0, actionStatusDistribution: {}, commandAckDistribution: {} };
    vi.spyOn(BackendAnalyticsRepository.prototype, 'getAnalyticsSummary').mockResolvedValue(realSummary);
    const demoSpy = vi.spyOn(DemoAnalyticsRepository.prototype, 'getAnalyticsSummary');

    const resolver = new DataSourceResolver();
    const result = await resolver.getAnalyticsSummary('24h');

    expect(result).toEqual(realSummary);
    expect(demoSpy).not.toHaveBeenCalled();
  });

  it('preserves metric/range/bucket exactly through getAnalyticsTimeSeries in live mode', async () => {
    vi.doMock('../config/features', () => ({ FEATURES: { DEMO_MODE: false } }));
    const { DataSourceResolver } = await import('./DataSourceResolver');
    const { BackendAnalyticsRepository } = await import('./BackendRepository');

    const spy = vi.spyOn(BackendAnalyticsRepository.prototype, 'getAnalyticsTimeSeries').mockResolvedValue({ metric: 'pipeline', from: 'x', to: 'y', bucket: 'DAY', points: [] });

    const resolver = new DataSourceResolver();
    await resolver.getAnalyticsTimeSeries('pipeline', '7d', 'day');

    expect(spy).toHaveBeenCalledWith('pipeline', '7d', 'day');
  });
});
