import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { PipelineTimeline } from './PipelineTimeline';

const mockDownloadCsv = vi.fn();
vi.mock('../../utils/csv', () => ({
  downloadCsv: (...args: any[]) => mockDownloadCsv(...args),
}));

function trace(overrides: Record<string, any> = {}) {
  return {
    traceId: 't1',
    correlationId: 'corr-1',
    deviceId: 'dev-1',
    stage: 'TELEMETRY',
    stageIndex: 0,
    previousStage: null,
    nextStage: 'TWIN',
    startTimestamp: 1735689600000,
    endTimestamp: 1735689600010,
    durationMs: 10,
    status: 'SUCCESS',
    payloadSize: 100,
    metadata: {},
    ...overrides,
  };
}

function renderTimeline(seedTraces: any[], props: Partial<{ isLoading: boolean; error: unknown }> = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  client.setQueryData(['global-pipeline-traces'], seedTraces);
  const onSelectCorrelation = vi.fn();
  const result = render(
    <QueryClientProvider client={client}>
      <PipelineTimeline onSelectCorrelation={onSelectCorrelation} {...props} />
    </QueryClientProvider>
  );
  return { ...result, onSelectCorrelation };
}

describe('PipelineTimeline - real traces, honest states, no fake rows', () => {
  beforeEach(() => vi.clearAllMocks());

  it('renders real trace rows', () => {
    renderTimeline([trace({ stage: 'ACTION', correlationId: 'corr-real-abc' })]);

    expect(screen.getByText('ACTION')).toBeInTheDocument();
    expect(screen.getByText('corr-real-abc')).toBeInTheDocument();
    expect(screen.getByText('10ms')).toBeInTheDocument();
  });

  it('shows an honest empty state, never fake sample rows, when there are no traces', () => {
    renderTimeline([]);

    expect(screen.getByText('No pipeline traces available yet.')).toBeInTheDocument();
  });

  it('shows a real loading state while history is being fetched', () => {
    const { container } = renderTimeline([], { isLoading: true });

    expect(container.querySelector('[class*="animate"]')).toBeTruthy();
    expect(screen.queryByText('No pipeline traces available yet.')).not.toBeInTheDocument();
  });

  it('shows an honest error state when the history fetch fails', () => {
    renderTimeline([], { error: new Error('failed') });

    expect(screen.getByText('Could not load pipeline history.')).toBeInTheDocument();
  });

  it('clicking a row selects its real correlationId', () => {
    const { onSelectCorrelation } = renderTimeline([trace({ correlationId: 'corr-click-me' })]);

    fireEvent.click(screen.getByText('corr-click-me'));

    expect(onSelectCorrelation).toHaveBeenCalledWith('corr-click-me');
  });

  it('Export CSV exports the real displayed rows, not fabricated columns', () => {
    renderTimeline([trace({ correlationId: 'corr-export', stage: 'TWIN', durationMs: 42, status: 'SUCCESS' })]);

    fireEvent.click(screen.getByText('Export CSV'));

    expect(mockDownloadCsv).toHaveBeenCalledWith(
      expect.stringContaining('pipeline-traces-'),
      ['timestamp', 'stage', 'deviceId', 'correlationId', 'durationMs', 'status'],
      [[new Date(1735689600000).toISOString(), 'TWIN', 'dev-1', 'corr-export', '42', 'SUCCESS']]
    );
  });

  it('Export CSV is disabled when there are no real rows to export', () => {
    renderTimeline([]);

    expect(screen.getByText('Export CSV').closest('button')).toBeDisabled();
  });
});
