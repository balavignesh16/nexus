import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import AnalyticsPage from './AnalyticsPage';
import { analyticsApi } from '../api/analyticsApi';

vi.mock('../api/analyticsApi', async () => {
  const actual = await vi.importActual<typeof import('../api/analyticsApi')>('../api/analyticsApi');
  return {
    ...actual,
    analyticsApi: {
      getSummary: vi.fn(),
      getTimeSeries: vi.fn(),
    },
  };
});

function emptySummary(overrides: Partial<Awaited<ReturnType<typeof analyticsApi.getSummary>>> = {}) {
  return {
    range: '24h',
    from: '2026-01-01T00:00:00Z',
    to: '2026-01-02T00:00:00Z',
    totalDeviceCount: 0,
    deviceCountByType: {},
    connectivityDistribution: {},
    eventCount: 0,
    ruleMatchCount: 0,
    actionStatusDistribution: {},
    commandAckDistribution: {},
    ...overrides,
  };
}

function emptySeries(metric: string) {
  return { metric, from: '2026-01-01T00:00:00Z', to: '2026-01-02T00:00:00Z', bucket: 'HOUR', points: [] };
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <AnalyticsPage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('AnalyticsPage - real operational analytics, no fabricated data', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('shows honest empty states when there is no activity - never a fake zero-filled chart', async () => {
    vi.mocked(analyticsApi.getSummary).mockResolvedValue(emptySummary());
    vi.mocked(analyticsApi.getTimeSeries).mockImplementation((metric) => Promise.resolve(emptySeries(metric)));

    renderPage();

    await waitFor(() => expect(screen.getByText('No telemetry recorded in selected period.')).toBeInTheDocument());
    expect(screen.getByText('No events recorded in selected period.')).toBeInTheDocument();
    expect(screen.getByText('No rule matches recorded in selected period.')).toBeInTheDocument();
  });

  it('regression: zero devices renders 0, never the old fabricated fallback 1248', async () => {
    vi.mocked(analyticsApi.getSummary).mockResolvedValue(emptySummary({ totalDeviceCount: 0 }));
    vi.mocked(analyticsApi.getTimeSeries).mockImplementation((metric) => Promise.resolve(emptySeries(metric)));

    renderPage();

    await waitFor(() => expect(screen.queryByText('1248')).not.toBeInTheDocument());
  });

  it('renders real device/connectivity/action/ack distributions from the summary response', async () => {
    vi.mocked(analyticsApi.getSummary).mockResolvedValue(emptySummary({
      totalDeviceCount: 3,
      deviceCountByType: { TEMPERATURE_SENSOR: 2, GAS_SENSOR: 1 },
      connectivityDistribution: { ONLINE: 2, UNKNOWN: 1 },
      actionStatusDistribution: { SUCCESS: 5, FAILURE: 1 },
      commandAckDistribution: { ACCEPTED: 2, NO_ACK_YET: 1 },
    }));
    vi.mocked(analyticsApi.getTimeSeries).mockImplementation((metric) => Promise.resolve(emptySeries(metric)));

    renderPage();

    await waitFor(() => expect(screen.getByText('3 device(s) registered')).toBeInTheDocument());
    expect(screen.getByText('TEMPERATURE_SENSOR')).toBeInTheDocument();
    expect(screen.getByText('ONLINE')).toBeInTheDocument();
    expect(screen.getByText('No ack yet')).toBeInTheDocument();
    // NO_ACK_YET must never be relabeled/implied as a failure.
    expect(screen.queryByText('NO_ACK_YET failed')).not.toBeInTheDocument();
  });

  it('every section shows the CALCULATED data-source badge, never LIVE', async () => {
    vi.mocked(analyticsApi.getSummary).mockResolvedValue(emptySummary());
    vi.mocked(analyticsApi.getTimeSeries).mockImplementation((metric) => Promise.resolve(emptySeries(metric)));

    renderPage();

    await waitFor(() => expect(screen.getAllByText('Calculated').length).toBeGreaterThan(0));
    expect(screen.queryByText('Live')).not.toBeInTheDocument();
  });

  it('changing the time range triggers a real refetch with the new range param', async () => {
    vi.mocked(analyticsApi.getSummary).mockResolvedValue(emptySummary());
    vi.mocked(analyticsApi.getTimeSeries).mockImplementation((metric) => Promise.resolve(emptySeries(metric)));

    renderPage();
    await waitFor(() => expect(analyticsApi.getSummary).toHaveBeenCalledWith('24h'));

    fireEvent.click(screen.getByRole('button', { name: '7d' }));

    await waitFor(() => expect(analyticsApi.getSummary).toHaveBeenCalledWith('7d'));
  });

  it('a failing section shows an error, while other sections still render (partial failure)', async () => {
    vi.mocked(analyticsApi.getSummary).mockResolvedValue(emptySummary());
    vi.mocked(analyticsApi.getTimeSeries).mockImplementation((metric) => {
      if (metric === 'telemetry') {
        return Promise.reject(new Error('Telemetry query failed'));
      }
      return Promise.resolve(emptySeries(metric));
    });

    renderPage();

    await waitFor(() => expect(screen.getAllByTestId('section-error').length).toBeGreaterThan(0));
    // Events section (a different query) still renders its own (empty) state, not an error.
    expect(screen.getByText('No events recorded in selected period.')).toBeInTheDocument();
  });

  it('pipeline section communicates its real 7-day retention window explicitly', async () => {
    vi.mocked(analyticsApi.getSummary).mockResolvedValue(emptySummary());
    vi.mocked(analyticsApi.getTimeSeries).mockImplementation((metric) => {
      if (metric === 'pipeline') {
        return Promise.resolve({
          metric: 'pipeline',
          from: '2026-01-25T00:00:00Z',
          to: '2026-02-01T00:00:00Z',
          bucket: 'DAY',
          points: [],
        });
      }
      return Promise.resolve(emptySeries(metric));
    });

    renderPage();

    await waitFor(() => expect(screen.getByText(/pipeline traces retain only 7 days/i)).toBeInTheDocument());
  });
});
