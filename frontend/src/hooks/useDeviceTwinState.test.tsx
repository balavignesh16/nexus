import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, waitFor, act } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';

const mockGetDeviceConnectivity = vi.fn();
vi.mock('../api/deviceApi', () => ({
  deviceApi: { getDeviceConnectivity: (...args: any[]) => mockGetDeviceConnectivity(...args) },
}));

const mockGetTelemetryHistory = vi.fn();
vi.mock('../repositories/RepositoryProvider', () => ({
  useRepositories: () => ({ telemetry: { getTelemetryHistory: mockGetTelemetryHistory } }),
}));

vi.mock('../config/features', () => ({ FEATURES: { DEMO_MODE: false } }));

// usePolling itself is left real (its setQueryData/useQuery cache behavior
// is exactly what these tests verify) - only its dependency on the real
// RealtimeManager-backed connection singleton is stubbed out, matching
// the disconnected/REST-polling state these tests exercise.
vi.mock('../services/ConnectionManager', () => ({
  useConnectionStatus: () => ({ realtimeStatus: 'disconnected' }),
}));

// Captures the handler passed to useDeviceConnectivityStream so tests can
// simulate a real DeviceOnline/DeviceOffline push without a live socket -
// same technique DeviceWorkspace.test.tsx already uses for this hook.
let connectivityHandler: ((payload: { deviceId: string; status: 'ONLINE' | 'OFFLINE' }) => void) | null = null;
vi.mock('../realtime/streams/useDeviceConnectivityStream', () => ({
  useDeviceConnectivityStream: (_deviceId: string, handler: any) => {
    connectivityHandler = handler;
  },
}));

import { useDeviceTwinState } from './useDeviceTwinState';

function wrapper(client: QueryClient) {
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
}

describe('useDeviceTwinState - Step 9C: connectivity stays live once realtime is healthy', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    connectivityHandler = null;
    mockGetTelemetryHistory.mockResolvedValue([]);
  });

  it('shows the real REST-fetched connectivity value on initial load', async () => {
    mockGetDeviceConnectivity.mockResolvedValue({ deviceId: 'dev-1', status: 'ONLINE', lastChangedAt: '2026-01-01T00:00:00Z' });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    const { result } = renderHook(() => useDeviceTwinState('dev-1'), { wrapper: wrapper(client) });

    await waitFor(() => expect(result.current.connectivity?.status).toBe('ONLINE'));
    expect(result.current.connectivity?.lastChangedAt).toBe('2026-01-01T00:00:00Z');
  });

  it('a real DeviceOnline/DeviceOffline push updates the connectivity value live, without waiting for the next REST poll', async () => {
    mockGetDeviceConnectivity.mockResolvedValue({ deviceId: 'dev-1', status: 'OFFLINE', lastChangedAt: '2026-01-01T00:00:00Z' });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const invalidate = vi.spyOn(client, 'invalidateQueries');

    const { result } = renderHook(() => useDeviceTwinState('dev-1'), { wrapper: wrapper(client) });
    await waitFor(() => expect(result.current.connectivity?.status).toBe('OFFLINE'));

    expect(connectivityHandler).not.toBeNull();
    act(() => connectivityHandler!({ deviceId: 'dev-1', status: 'ONLINE' }));

    await waitFor(() => expect(result.current.connectivity?.status).toBe('ONLINE'));
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['twins', 12] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['twins', 100] });
  });

  it('a live push never fabricates a new lastChangedAt timestamp - it preserves the last real value the backend reported', async () => {
    mockGetDeviceConnectivity.mockResolvedValue({ deviceId: 'dev-1', status: 'OFFLINE', lastChangedAt: '2026-01-01T00:00:00Z' });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    const { result } = renderHook(() => useDeviceTwinState('dev-1'), { wrapper: wrapper(client) });
    await waitFor(() => expect(result.current.connectivity?.status).toBe('OFFLINE'));

    act(() => connectivityHandler!({ deviceId: 'dev-1', status: 'ONLINE' }));

    await waitFor(() => expect(result.current.connectivity?.status).toBe('ONLINE'));
    expect(result.current.connectivity?.lastChangedAt).toBe('2026-01-01T00:00:00Z');
  });

  it('a live push before any REST value has loaded still produces a real status, never a fabricated timestamp', async () => {
    mockGetDeviceConnectivity.mockImplementation(() => new Promise(() => {})); // never resolves
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    const { result } = renderHook(() => useDeviceTwinState('dev-1'), { wrapper: wrapper(client) });
    await waitFor(() => expect(connectivityHandler).not.toBeNull());

    act(() => connectivityHandler!({ deviceId: 'dev-1', status: 'ONLINE' }));

    await waitFor(() => expect(result.current.connectivity?.status).toBe('ONLINE'));
    expect(result.current.connectivity?.lastChangedAt).toBeNull();
  });

  it('telemetry recency/field-count values remain unaffected by the connectivity overlay', async () => {
    mockGetDeviceConnectivity.mockResolvedValue({ deviceId: 'dev-1', status: 'ONLINE', lastChangedAt: null });
    mockGetTelemetryHistory.mockResolvedValue([{ timestamp: '2026-01-02T00:00:00Z', payload: { TEMPERATURE_SENSOR: 21 } }]);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });

    const { result } = renderHook(() => useDeviceTwinState('dev-1'), { wrapper: wrapper(client) });

    await waitFor(() => expect(result.current.lastTelemetryAt).toBe('2026-01-02T00:00:00Z'));
    expect(result.current.reportedFields).toEqual(['TEMPERATURE_SENSOR']);
  });
});
