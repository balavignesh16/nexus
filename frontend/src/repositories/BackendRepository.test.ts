import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { BackendDeviceRepository, BackendTelemetryRepository, BackendAnalyticsRepository } from './BackendRepository';
import { apiClient } from '../api/apiClient';

vi.mock('../api/apiClient', async () => {
  const actual = await vi.importActual<typeof import('../api/apiClient')>('../api/apiClient');
  return {
    ...actual,
    apiClient: { ...actual.apiClient, get: vi.fn() },
  };
});

function jsonResponse(body: unknown, ok = true) {
  return { ok, status: ok ? 200 : 500, json: async () => body } as Response;
}

describe('BackendDeviceRepository - deviceType mapping', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('getDevice() maps the real backend "deviceType" field, not "type"', async () => {
    globalThis.fetch = vi.fn(async () => jsonResponse({
      id: 'dev-1',
      name: 'Gas Sensor 1',
      status: 'ACTIVE',
      deviceType: 'GAS_SENSOR',
      // Intentionally no "type" field - the real backend never sends one.
    })) as any;

    const repo = new BackendDeviceRepository();
    const device = await repo.getDevice('dev-1');

    expect(device.deviceType).toBe('GAS_SENSOR');
  });

  it('getDevices() maps the real backend "deviceType" field for every item, not "type"', async () => {
    globalThis.fetch = vi.fn(async () => jsonResponse([
      { id: 'dev-1', name: 'Gas Sensor 1', status: 'ACTIVE', deviceType: 'GAS_SENSOR' },
      { id: 'dev-2', name: 'Temp Sensor 1', status: 'ACTIVE', deviceType: 'TEMPERATURE_SENSOR' },
    ])) as any;

    const repo = new BackendDeviceRepository();
    const devices = await repo.getDevices();

    expect(devices.map(d => d.deviceType)).toEqual(['GAS_SENSOR', 'TEMPERATURE_SENSOR']);
  });

  it('falls back to "Generic" only when the backend genuinely omits deviceType', async () => {
    globalThis.fetch = vi.fn(async () => jsonResponse({
      id: 'dev-1',
      name: 'Unknown Device',
      status: 'REGISTERED',
    })) as any;

    const repo = new BackendDeviceRepository();
    const device = await repo.getDevice('dev-1');

    expect(device.deviceType).toBe('Generic');
  });
});

/**
 * Regression coverage (Phase 8) for a real bug found during live
 * verification: the real GET /devices/{id}/telemetry endpoint returns
 * { records: [{sensorType, value, unit, timestamp}] }, not the
 * { content: [{payload: {...}}] } shape this repository used to assume -
 * which meant it silently returned [] against the real backend, always.
 */
describe('BackendTelemetryRepository - parses the real backend response shape', () => {
  const originalFetch = globalThis.fetch;

  beforeEach(() => {
    globalThis.fetch = vi.fn();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  function mockRecordsResponse() {
    (globalThis.fetch as any).mockResolvedValue({
      ok: true,
      json: async () => ({
        records: [
          { id: 'r1', deviceId: 'd1', timestamp: '2026-01-01T00:00:00Z', sensorType: 'TEMPERATURE_SENSOR', value: 21.5, unit: 'CELSIUS' },
          { id: 'r2', deviceId: 'd1', timestamp: '2026-01-01T00:01:00Z', sensorType: 'HUMIDITY_SENSOR', value: 45, unit: 'PERCENT' },
        ],
        returnedCount: 2,
        hasMore: false,
      }),
    });
  }

  it('getTelemetryHistory normalizes real flat records into the payload shape downstream consumers expect', async () => {
    mockRecordsResponse();
    const repo = new BackendTelemetryRepository();

    const history = await repo.getTelemetryHistory('d1');

    expect(history).toHaveLength(2);
    expect(history[0].payload).toEqual({ TEMPERATURE_SENSOR: 21.5 });
    expect(history[1].payload).toEqual({ HUMIDITY_SENSOR: 45 });
  });

  it('getTelemetry filters real records by sensorType and returns real values', async () => {
    mockRecordsResponse();
    const repo = new BackendTelemetryRepository();

    const points = await repo.getTelemetry('d1', 'TEMPERATURE_SENSOR');

    expect(points).toHaveLength(1);
    expect(points[0].value).toBe(21.5);
  });

  it('getTelemetryHistory returns an honest empty array when the device has no telemetry, not a parsing error', async () => {
    (globalThis.fetch as any).mockResolvedValue({
      ok: true,
      json: async () => ({ records: [], returnedCount: 0, hasMore: false }),
    });
    const repo = new BackendTelemetryRepository();

    const history = await repo.getTelemetryHistory('d1');

    expect(history).toEqual([]);
  });

  it('preserves typed false, zero and strings rather than displaying the legacy null value', async () => {
    (globalThis.fetch as any).mockResolvedValue({ok:true,json:async()=>({records:[
      {sensorType:'CUSTOM',capabilityKey:'led_state',value:null,typedValue:false},
      {sensorType:'CUSTOM',capabilityKey:'angle',value:null,typedValue:0},
      {sensorType:'CUSTOM',capabilityKey:'mode',value:null,typedValue:'AUTO'},
    ]})});
    const repo=new BackendTelemetryRepository();
    expect((await repo.getTelemetryHistory('d1')).map(row=>row.payload)).toEqual([{led_state:false},{angle:0},{mode:'AUTO'}]);
    expect(await repo.getTelemetry('d1','led_state')).toEqual([]);
    expect((await repo.getTelemetry('d1','angle'))[0].value).toBe(0);
  });
});

/**
 * Step 8B: BackendAnalyticsRepository is a thin delegate to the existing
 * analyticsApi module (not a reimplementation) - these tests confirm the
 * real endpoint/params still get called through the repository layer, and
 * that a real backend error propagates rather than being swallowed.
 */
describe('BackendAnalyticsRepository - delegates to analyticsApi, preserving the real contract', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('getAnalyticsSummary calls the real bounded-range summary endpoint', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ range: '24h' } as any);
    const repo = new BackendAnalyticsRepository();

    await repo.getAnalyticsSummary('24h');

    expect(apiClient.get).toHaveBeenCalledWith('/analytics/summary?range=24h');
  });

  it('getAnalyticsTimeSeries preserves metric, range and bucket exactly', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ metric: 'telemetry', points: [] } as any);
    const repo = new BackendAnalyticsRepository();

    await repo.getAnalyticsTimeSeries('telemetry', '7d', 'hour');

    expect(apiClient.get).toHaveBeenCalledWith('/analytics/timeseries?metric=telemetry&range=7d&bucket=hour');
  });

  it('propagates a real backend error rather than swallowing it to null/empty', async () => {
    vi.mocked(apiClient.get).mockRejectedValue(new Error('Backend fetch failed: 500'));
    const repo = new BackendAnalyticsRepository();

    await expect(repo.getAnalyticsSummary('24h')).rejects.toThrow('Backend fetch failed: 500');
  });
});
