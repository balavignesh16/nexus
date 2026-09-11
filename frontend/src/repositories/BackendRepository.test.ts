import { describe, it, expect, vi, afterEach } from 'vitest';
import { BackendDeviceRepository } from './BackendRepository';

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
