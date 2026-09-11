import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

const mockUseDevice = vi.fn();
vi.mock('../../../../hooks/queries', () => ({
  useDevice: (...args: any[]) => mockUseDevice(...args),
}));

const mockGetRules = vi.fn();
vi.mock('../../../../repositories/RepositoryProvider', () => ({
  useRepositories: () => ({ rules: { getRules: mockGetRules } }),
}));

const mockUsePolling = vi.fn();
vi.mock('../../../../hooks/usePolling', () => ({
  usePolling: (...args: any[]) => mockUsePolling(...args),
}));

const mockUseConnectionStatus = vi.fn();
vi.mock('../../../../services/ConnectionManager', () => ({
  useConnectionStatus: () => mockUseConnectionStatus(),
}));

import { DiagnosticsTab } from './DiagnosticsTab';

function baseConnection(overrides: Record<string, any> = {}) {
  return { backend: 'Online', mqtt: 'Online', database: 'Online', ...overrides };
}

describe('DiagnosticsTab - truthful checks', () => {
  it('never shows the old fabricated "2 rules actively evaluating" claim', () => {
    mockUseDevice.mockReturnValue({ data: { id: 'dev-1', status: 'ACTIVE' }, isLoading: false });
    mockUseConnectionStatus.mockReturnValue(baseConnection());
    mockUsePolling.mockReturnValue({ data: [{ ruleId: 'r1' }, { ruleId: 'r2' }, { ruleId: 'r3' }], isError: false });

    render(<DiagnosticsTab deviceId="dev-1" />);

    expect(screen.queryByText(/2 rules actively evaluating/i)).not.toBeInTheDocument();
    expect(screen.getByText((_, el) => el?.textContent === '> 3 rule(s) registered.')).toBeInTheDocument();
  });

  it('reflects a real empty rule set honestly, not a fabricated count', () => {
    mockUseDevice.mockReturnValue({ data: { id: 'dev-1', status: 'ACTIVE' }, isLoading: false });
    mockUseConnectionStatus.mockReturnValue(baseConnection());
    mockUsePolling.mockReturnValue({ data: [], isError: false });

    render(<DiagnosticsTab deviceId="dev-1" />);

    expect(screen.getByText((_, el) => el?.textContent === '> 0 rule(s) registered.')).toBeInTheDocument();
  });

  it('shows the rule check as UNAVAILABLE (not a fake count) when the rule fetch fails', () => {
    mockUseDevice.mockReturnValue({ data: { id: 'dev-1', status: 'ACTIVE' }, isLoading: false });
    mockUseConnectionStatus.mockReturnValue(baseConnection());
    mockUsePolling.mockReturnValue({ data: [], isError: true });

    render(<DiagnosticsTab deviceId="dev-1" />);

    expect(screen.getByText((_, el) => el?.textContent === '> Rule count unavailable.')).toBeInTheDocument();
    expect(screen.queryByText(/rule\(s\) registered/)).not.toBeInTheDocument();
  });

  it('never shows the old fabricated "Inserts successful" claim, and uses real database status', () => {
    mockUseDevice.mockReturnValue({ data: { id: 'dev-1', status: 'ACTIVE' }, isLoading: false });
    mockUseConnectionStatus.mockReturnValue(baseConnection({ database: 'Online' }));
    mockUsePolling.mockReturnValue({ data: [], isError: false });

    render(<DiagnosticsTab deviceId="dev-1" />);

    expect(screen.queryByText(/Inserts successful/i)).not.toBeInTheDocument();
    expect(screen.getByText((_, el) => el?.textContent === '> Datasource validity check passed.')).toBeInTheDocument();
  });

  it('shows database as FAIL when the real signal says offline, not an unconditional PASS', () => {
    mockUseDevice.mockReturnValue({ data: { id: 'dev-1', status: 'ACTIVE' }, isLoading: false });
    mockUseConnectionStatus.mockReturnValue(baseConnection({ database: 'Offline' }));
    mockUsePolling.mockReturnValue({ data: [], isError: false });

    render(<DiagnosticsTab deviceId="dev-1" />);

    expect(screen.getByText((_, el) => el?.textContent === '> Datasource is unreachable.')).toBeInTheDocument();
  });

  it('uses the real MQTT broker connection signal instead of an unconditional PASS', () => {
    mockUseDevice.mockReturnValue({ data: { id: 'dev-1', status: 'ACTIVE' }, isLoading: false });
    mockUseConnectionStatus.mockReturnValue(baseConnection({ mqtt: 'Offline' }));
    mockUsePolling.mockReturnValue({ data: [], isError: false });

    render(<DiagnosticsTab deviceId="dev-1" />);

    expect(screen.queryByText(/Authorized on telemetry, twins, and commands topics/i)).not.toBeInTheDocument();
    expect(screen.getByText((_, el) => el?.textContent === '> Backend is not connected to the MQTT broker.')).toBeInTheDocument();
  });

  it('marks Digital Twin Sync as UNAVAILABLE rather than inferring it from device status', () => {
    mockUseDevice.mockReturnValue({ data: { id: 'dev-1', status: 'ACTIVE' }, isLoading: false });
    mockUseConnectionStatus.mockReturnValue(baseConnection());
    mockUsePolling.mockReturnValue({ data: [], isError: false });

    render(<DiagnosticsTab deviceId="dev-1" />);

    expect(screen.queryByText(/Twin fully synced/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Last update 10 seconds ago/i)).not.toBeInTheDocument();
    expect(screen.getByText((_, el) => el?.textContent === '> Twin sync freshness is not currently exposed by the backend.')).toBeInTheDocument();
    expect(screen.getAllByText('UNAVAILABLE').length).toBeGreaterThanOrEqual(1);
  });

  it('never shows a fabricated fixed latency for device connection', () => {
    mockUseDevice.mockReturnValue({ data: { id: 'dev-1', status: 'ACTIVE' }, isLoading: false });
    mockUseConnectionStatus.mockReturnValue(baseConnection());
    mockUsePolling.mockReturnValue({ data: [], isError: false });

    render(<DiagnosticsTab deviceId="dev-1" />);

    expect(screen.queryByText(/Latency 45ms/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/responded 2 seconds ago/i)).not.toBeInTheDocument();
    expect(screen.getByText((_, el) => el?.textContent === '> Device status is ACTIVE.')).toBeInTheDocument();
  });
});
