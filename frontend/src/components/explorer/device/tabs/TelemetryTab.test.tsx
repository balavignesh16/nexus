import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { useState, useEffect } from 'react';

const mockGetTelemetryHistory = vi.fn();
vi.mock('../../../../repositories/RepositoryProvider', () => ({
  useRepositories: () => ({ telemetry: { getTelemetryHistory: mockGetTelemetryHistory } }),
}));

vi.mock('../../../../hooks/usePolling', () => ({
  usePolling: (_key: unknown, fn: () => Promise<any>) => {
    const [data, setData] = useState<any[]>([]);
    useEffect(() => {
      fn().then(setData);
    }, []);
    return { data, isLoading: false };
  },
}));

const mockDownloadCsv = vi.fn();
vi.mock('../../../../utils/csv', () => ({
  downloadCsv: (...args: any[]) => mockDownloadCsv(...args),
}));

import { TelemetryTab } from './TelemetryTab';

describe('TelemetryTab - real CSV export and search, no dead buttons', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('Export CSV is disabled when there is no telemetry', async () => {
    mockGetTelemetryHistory.mockResolvedValue([]);
    render(<TelemetryTab deviceId="dev-1" />);

    await waitFor(() => expect(screen.getByText('Export CSV').closest('button')).toBeDisabled());
  });

  it('Export CSV downloads a real CSV built from the loaded telemetry rows', async () => {
    mockGetTelemetryHistory.mockResolvedValue([
      { id: 't1', timestamp: '2026-01-01T00:00:00Z', payload: { value: 22.5, unit: 'CELSIUS' } },
    ]);
    render(<TelemetryTab deviceId="dev-1" />);

    await waitFor(() => expect(screen.getByText('Export CSV').closest('button')).not.toBeDisabled());
    fireEvent.click(screen.getByText('Export CSV'));

    expect(mockDownloadCsv).toHaveBeenCalledWith(
      expect.stringContaining('telemetry-dev-1-'),
      ['timestamp', 'value', 'unit'],
      [[new Date('2026-01-01T00:00:00Z').toISOString(), '22.5', 'CELSIUS']]
    );
  });

  it('search filters the displayed and exported rows by payload content', async () => {
    mockGetTelemetryHistory.mockResolvedValue([
      { id: 't1', timestamp: '2026-01-01T00:00:00Z', payload: { value: 22.5 } },
      { id: 't2', timestamp: '2026-01-01T00:01:00Z', payload: { value: 99.9 } },
    ]);
    render(<TelemetryTab deviceId="dev-1" />);

    await waitFor(() => expect(screen.getByText(/Showing 2 of 2 records/)).toBeInTheDocument());

    fireEvent.change(screen.getByPlaceholderText('Search payloads...'), { target: { value: '99.9' } });

    await waitFor(() => expect(screen.getByText(/Showing 1 of 2 records/)).toBeInTheDocument());
  });
});
