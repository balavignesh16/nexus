import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const mockGetPipelineMetrics = vi.fn();
const mockGetPipelineHistory = vi.fn();
vi.mock('../repositories/RepositoryProvider', () => ({
  useRepositories: () => ({ pipeline: { getPipelineMetrics: mockGetPipelineMetrics, getPipelineHistory: mockGetPipelineHistory } }),
}));

vi.mock('../services/ConnectionManager', () => ({
  useConnectionStatus: () => ({ realtimeStatus: 'connected' }),
}));

vi.mock('../components/pipeline/PlatformObservability', () => ({
  PlatformObservability: () => <div data-testid="observability" />,
}));

vi.mock('../components/pipeline/PipelineTimeline', () => ({
  PipelineTimeline: ({ onSelectCorrelation, isLoading, error }: any) => (
    <div>
      <button onClick={() => onSelectCorrelation('corr-from-timeline')}>Select from timeline</button>
      <span data-testid="timeline-loading">{String(!!isLoading)}</span>
      <span data-testid="timeline-error">{String(!!error)}</span>
    </div>
  ),
}));

vi.mock('../components/pipeline/PipelineCanvas', () => ({
  PipelineCanvas: ({ selectedCorrelationId }: any) => (
    <div data-testid="canvas-selected">{selectedCorrelationId ?? 'none'}</div>
  ),
}));

vi.mock('../components/pipeline/CorrelationInspector', () => ({
  CorrelationInspector: ({ correlationId, onClose }: any) => (
    <div>
      <span data-testid="inspector-correlation-id">{correlationId}</span>
      <button onClick={onClose}>Close</button>
    </div>
  ),
}));

import PipelinePage from './PipelinePage';

function renderPage(initialPath: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[initialPath]}>
        <PipelinePage />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

describe('PipelinePage - real correlationId deep-linking', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockGetPipelineMetrics.mockResolvedValue({ totalStored: 100, averageLatencyMs: 3.2, recentErrorCount: 0 });
    mockGetPipelineHistory.mockResolvedValue([]);
  });

  it('opens the real CorrelationInspector for a correlationId deep-linked via URL', () => {
    renderPage('/pipeline?correlationId=corr-real-123');

    expect(screen.getByTestId('inspector-correlation-id')).toHaveTextContent('corr-real-123');
    expect(screen.getByTestId('canvas-selected')).toHaveTextContent('corr-real-123');
  });

  it('shows no inspector when no correlationId is present in the URL', () => {
    renderPage('/pipeline');

    expect(screen.queryByTestId('inspector-correlation-id')).not.toBeInTheDocument();
    expect(screen.getByTestId('canvas-selected')).toHaveTextContent('none');
  });

  it('closing the inspector clears the selection', () => {
    renderPage('/pipeline?correlationId=corr-real-123');

    fireEvent.click(screen.getByText('Close'));

    expect(screen.queryByTestId('inspector-correlation-id')).not.toBeInTheDocument();
  });

  it('selecting a trace from the timeline opens the inspector for that real correlationId', () => {
    renderPage('/pipeline');

    fireEvent.click(screen.getByText('Select from timeline'));

    expect(screen.getByTestId('inspector-correlation-id')).toHaveTextContent('corr-from-timeline');
  });

  it('seeds the real history via getPipelineHistory(100) on mount - a single bounded fetch, not unbounded', async () => {
    renderPage('/pipeline');

    await vi.waitFor(() => expect(mockGetPipelineHistory).toHaveBeenCalledWith(100));
    expect(mockGetPipelineHistory).toHaveBeenCalledTimes(1);
  });

  it('forwards real loading state to the timeline while history is being fetched', () => {
    mockGetPipelineHistory.mockReturnValue(new Promise(() => {})); // never resolves
    renderPage('/pipeline');

    expect(screen.getByTestId('timeline-loading')).toHaveTextContent('true');
  });

  it('forwards a real error state to the timeline when the history fetch fails', async () => {
    mockGetPipelineHistory.mockRejectedValue(new Error('failed'));
    renderPage('/pipeline');

    await vi.waitFor(() => expect(screen.getByTestId('timeline-error')).toHaveTextContent('true'));
  });
});
