import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// ConnectionManager is a module-level singleton that starts pinging as soon
// as it's imported, so each test gets a fresh module instance via
// vi.resetModules() + dynamic import, with fetch and RealtimeManager mocked
// beforehand.

function jsonResponse(body: unknown, ok = true) {
  return { ok, json: async () => body } as Response;
}

describe('ConnectionManager', () => {
  let onStateChangeCallback: ((state: string, metrics: any) => void) | null;

  beforeEach(() => {
    vi.resetModules();
    onStateChangeCallback = null;

    vi.doMock('../realtime/RealtimeManager', () => ({
      RealtimeManager: {
        onStateChange: vi.fn((cb: (state: string, metrics: any) => void) => {
          onStateChangeCallback = cb;
          return () => {};
        }),
      },
    }));
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.doUnmock('../realtime/RealtimeManager');
  });

  async function loadWithFetch(fetchImpl: (url: string) => Promise<Response>) {
    globalThis.fetch = vi.fn(fetchImpl) as any;
    const mod = await import('./ConnectionManager');
    // Let the immediately-invoked ping() resolve its fetch chain.
    await new Promise(r => setTimeout(r, 0));
    await new Promise(r => setTimeout(r, 0));
    return mod;
  }

  it('supplies database state from the platform-health response', async () => {
    const { ConnectionManager } = await loadWithFetch(async (url: string) => {
      if (url.includes('/devices/active')) return jsonResponse([]);
      if (url.includes('/platform-health')) return jsonResponse({ database: 'UP', mqtt: 'CONNECTED' });
      throw new Error('unexpected url ' + url);
    });

    expect((ConnectionManager as any)['state'].database).toBe('Online');
  });

  it('supplies MQTT state from the platform-health response', async () => {
    const { ConnectionManager } = await loadWithFetch(async (url: string) => {
      if (url.includes('/devices/active')) return jsonResponse([]);
      if (url.includes('/platform-health')) return jsonResponse({ database: 'UP', mqtt: 'DISCONNECTED' });
      throw new Error('unexpected url ' + url);
    });

    expect((ConnectionManager as any)['state'].mqtt).toBe('Offline');
  });

  it('no longer derives mqtt from /devices/active succeeding', async () => {
    // devices/active succeeds, but platform-health explicitly reports MQTT
    // disconnected - mqtt must follow platform-health, not devices/active.
    const { ConnectionManager } = await loadWithFetch(async (url: string) => {
      if (url.includes('/devices/active')) return jsonResponse([{ id: '1' }]);
      if (url.includes('/platform-health')) return jsonResponse({ database: 'UP', mqtt: 'DISCONNECTED' });
      throw new Error('unexpected url ' + url);
    });

    expect((ConnectionManager as any)['state'].backend).toBe('Online');
    expect((ConnectionManager as any)['state'].mqtt).toBe('Offline');
  });

  it('no longer derives database from /devices/active succeeding', async () => {
    const { ConnectionManager } = await loadWithFetch(async (url: string) => {
      if (url.includes('/devices/active')) return jsonResponse([{ id: '1' }]);
      if (url.includes('/platform-health')) return jsonResponse({ database: 'DOWN', mqtt: 'CONNECTED' });
      throw new Error('unexpected url ' + url);
    });

    expect((ConnectionManager as any)['state'].backend).toBe('Online');
    expect((ConnectionManager as any)['state'].database).toBe('Offline');
  });

  it('realtimeStatus remains driven by RealtimeManager, not the health ping', async () => {
    const { ConnectionManager } = await loadWithFetch(async (url: string) => {
      if (url.includes('/devices/active')) return jsonResponse([]);
      if (url.includes('/platform-health')) return jsonResponse({ database: 'UP', mqtt: 'CONNECTED' });
      throw new Error('unexpected url ' + url);
    });

    expect(onStateChangeCallback).not.toBeNull();
    onStateChangeCallback!('connected', {
      messagesPerSec: 3, bytesPerSec: 100, droppedMessages: 0, clientOffset: 5, reconnectAttempts: 0,
    });

    expect((ConnectionManager as any)['state'].realtimeStatus).toBe('connected');
    expect((ConnectionManager as any)['state'].messagesPerSec).toBe(3);
  });

  it('backend reachability remains functional (fails when devices/active fails)', async () => {
    const { ConnectionManager } = await loadWithFetch(async (url: string) => {
      if (url.includes('/devices/active')) throw new Error('network error');
      throw new Error('unexpected url ' + url);
    });

    expect((ConnectionManager as any)['state'].backend).toBe('Offline');
    expect((ConnectionManager as any)['state'].database).toBe('Offline');
    expect((ConnectionManager as any)['state'].mqtt).toBe('Offline');
  });

  it('does not call /api/v1/platform-health when the backend itself is unreachable', async () => {
    const calledUrls: string[] = [];
    await loadWithFetch(async (url: string) => {
      calledUrls.push(url);
      if (url.includes('/devices/active')) throw new Error('network error');
      throw new Error('unexpected url ' + url);
    });

    expect(calledUrls.some(u => u.includes('/platform-health'))).toBe(false);
  });
});
