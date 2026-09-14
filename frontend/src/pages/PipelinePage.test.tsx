import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const mockGetPipelineMetrics = vi.fn();
vi.mock('../repositories/RepositoryProvider', () => ({
  useRepositories: () => ({ pipeline: { getPipelineMetrics: mockGetPipelineMetrics } }),
}));

vi.mock('../services/ConnectionManager', () => ({
  useConnectionStatus: () => ({ realtimeStatus: 'connected' }),
}));

vi.mock('../components/pipeline/PlatformObservability', () => ({
  PlatformObservability: () => <div data-testid="observability" />,
}));

vi.mock('../components/pipeline/PipelineTimeline', () => ({
  PipelineTimeline: ({ onSelectCorrelation }: any) => (
    <button onClick={() => onSelectCorrelation('corr-from-timeline')}>Select from timeline</button>
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
  it('opens the real CorrelationInspector for a correlationId deep-linked via URL', () => {
    mockGetPipelineMetrics.mockResolvedValue({});
    renderPage('/pipeline?correlationId=corr-real-123');

    expect(screen.getByTestId('inspector-correlation-id')).toHaveTextContent('corr-real-123');
    expect(screen.getByTestId('canvas-selected')).toHaveTextContent('corr-real-123');
  });

  it('shows no inspector when no correlationId is present in the URL', () => {
    mockGetPipelineMetrics.mockResolvedValue({});
    renderPage('/pipeline');

    expect(screen.queryByTestId('inspector-correlation-id')).not.toBeInTheDocument();
    expect(screen.getByTestId('canvas-selected')).toHaveTextContent('none');
  });

  it('closing the inspector clears the selection', () => {
    mockGetPipelineMetrics.mockResolvedValue({});
    renderPage('/pipeline?correlationId=corr-real-123');

    fireEvent.click(screen.getByText('Close'));

    expect(screen.queryByTestId('inspector-correlation-id')).not.toBeInTheDocument();
  });

  it('selecting a trace from the timeline opens the inspector for that real correlationId', () => {
    mockGetPipelineMetrics.mockResolvedValue({});
    renderPage('/pipeline');

    fireEvent.click(screen.getByText('Select from timeline'));

    expect(screen.getByTestId('inspector-correlation-id')).toHaveTextContent('corr-from-timeline');
  });
});
