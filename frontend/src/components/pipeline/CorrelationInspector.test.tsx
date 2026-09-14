import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { CorrelationInspector } from './CorrelationInspector';

const mockGetPipelineByCorrelationId = vi.fn();
const mockGetEvents = vi.fn();
vi.mock('../../repositories/RepositoryProvider', () => ({
  useRepositories: () => ({
    pipeline: { getPipelineByCorrelationId: mockGetPipelineByCorrelationId },
    events: { getEvents: mockGetEvents },
  }),
}));

const mockGetRuleMatchHistory = vi.fn();
vi.mock('../../api/aiApi', () => ({
  aiApi: { getRuleMatchHistory: (...args: any[]) => mockGetRuleMatchHistory(...args) },
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
    startTimestamp: 1000,
    endTimestamp: 1010,
    durationMs: 10,
    status: 'SUCCESS',
    payloadSize: 100,
    metadata: {},
    ...overrides,
  };
}

function renderInspector(correlationId = 'corr-1') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <CorrelationInspector correlationId={correlationId} onClose={vi.fn()} />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('CorrelationInspector - real trace + related records, no fabrication', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockGetEvents.mockResolvedValue([]);
    mockGetRuleMatchHistory.mockResolvedValue([]);
  });

  it('renders the real stage sequence ordered by real stageIndex, not response array order', async () => {
    mockGetPipelineByCorrelationId.mockResolvedValue([
      trace({ traceId: 't2', stage: 'ACTION', stageIndex: 2 }),
      trace({ traceId: 't1', stage: 'TELEMETRY', stageIndex: 0 }),
      trace({ traceId: 't3', stage: 'TWIN', stageIndex: 1 }),
    ]);

    renderInspector();

    await waitFor(() => expect(screen.getByText('TELEMETRY')).toBeInTheDocument());
    const stageEls = screen.getAllByText(/TELEMETRY|TWIN|ACTION/);
    const order = stageEls.map((el) => el.textContent);
    expect(order.indexOf('TELEMETRY')).toBeLessThan(order.indexOf('TWIN'));
    expect(order.indexOf('TWIN')).toBeLessThan(order.indexOf('ACTION'));
  });

  it('shows an honest "No trace details found." for an invalid/unknown correlationId', async () => {
    mockGetPipelineByCorrelationId.mockResolvedValue([]);

    renderInspector('corr-invalid');

    await waitFor(() => expect(screen.getByText('No trace details found.')).toBeInTheDocument());
  });

  it('renders real related events scoped to this correlationId', async () => {
    mockGetPipelineByCorrelationId.mockResolvedValue([trace()]);
    mockGetEvents.mockResolvedValue([{ eventId: 'e1', eventType: 'SENSOR_VALUE_CHANGED', severity: 'INFO', timestamp: '2026-01-01T00:00:00Z' }]);

    renderInspector();

    await waitFor(() => expect(screen.getByText('SENSOR_VALUE_CHANGED')).toBeInTheDocument());
    expect(mockGetEvents).toHaveBeenCalledWith(undefined, 20, 'corr-1');
  });

  it('shows an honest empty state when there are no related events', async () => {
    mockGetPipelineByCorrelationId.mockResolvedValue([trace()]);

    renderInspector();

    await waitFor(() => expect(screen.getByText('No events recorded for this correlation.')).toBeInTheDocument());
  });

  it('renders real related rule matches, scoped to this correlationId, linking to the existing AI explanation route', async () => {
    mockGetPipelineByCorrelationId.mockResolvedValue([trace()]);
    mockGetRuleMatchHistory.mockResolvedValue([{ matchId: 'match-1', ruleId: 'r1', ruleName: 'High Temp Alert', matchedAt: '2026-01-01T00:00:00Z', correlationId: 'corr-1', message: null }]);

    renderInspector();

    await waitFor(() => expect(screen.getByText(/High Temp Alert/)).toBeInTheDocument());
    expect(mockGetRuleMatchHistory).toHaveBeenCalledWith(20, undefined, 'corr-1');
    const link = screen.getByText(/High Temp Alert/).closest('a');
    expect(link).toHaveAttribute('href', '/ai?matchId=match-1');
  });

  it('never calls a rule match a "Decision"', async () => {
    mockGetPipelineByCorrelationId.mockResolvedValue([trace()]);
    mockGetRuleMatchHistory.mockResolvedValue([{ matchId: 'match-1', ruleId: 'r1', ruleName: 'High Temp Alert', matchedAt: '2026-01-01T00:00:00Z', correlationId: 'corr-1', message: null }]);

    renderInspector();

    await waitFor(() => expect(screen.getByText(/High Temp Alert/)).toBeInTheDocument());
    expect(screen.queryByText('Decision')).not.toBeInTheDocument();
    expect(screen.queryByText(/Root Cause/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Delivered/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Physical Outcome/i)).not.toBeInTheDocument();
  });

  it('shows an honest empty state when there are no related rule matches', async () => {
    mockGetPipelineByCorrelationId.mockResolvedValue([trace()]);

    renderInspector();

    await waitFor(() => expect(screen.getByText('No rule conditions were satisfied for this correlation.')).toBeInTheDocument());
  });

  it('computes a real total duration and truthful overall status from the real trace set', async () => {
    mockGetPipelineByCorrelationId.mockResolvedValue([
      trace({ traceId: 't1', stage: 'TELEMETRY', stageIndex: 0, durationMs: 10, status: 'SUCCESS' }),
      trace({ traceId: 't2', stage: 'TWIN', stageIndex: 1, durationMs: 15, status: 'SUCCESS' }),
    ]);

    renderInspector();

    await waitFor(() => expect(screen.getByText('25ms')).toBeInTheDocument());
    expect(screen.getByText('SUCCESS')).toBeInTheDocument();
  });
});
