import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { PipelineCanvas } from './PipelineCanvas';

function trace(overrides: Record<string, any> = {}) {
  return {
    traceId: 't1',
    correlationId: 'corr-1',
    deviceId: 'dev-1',
    stage: 'TELEMETRY',
    stageIndex: 0,
    previousStage: null,
    nextStage: 'TWIN',
    startTimestamp: Date.now(),
    endTimestamp: Date.now(),
    durationMs: 10,
    status: 'SUCCESS',
    payloadSize: 100,
    metadata: {},
    ...overrides,
  };
}

function renderCanvas(seedTraces: any[] = []) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  client.setQueryData(['global-pipeline-traces'], seedTraces);
  return render(
    <QueryClientProvider client={client}>
      <PipelineCanvas isPaused={false} onSelectCorrelation={() => {}} selectedCorrelationId={null} />
    </QueryClientProvider>
  );
}

describe('PipelineCanvas - only the real 3 stages, no fabricated pipeline concepts', () => {
  it('renders only TELEMETRY, TWIN, ACTION - never MQTT Gateway, Rules Engine, Alert Manager, or Analytics', () => {
    renderCanvas([trace({ stage: 'TELEMETRY', stageIndex: 0 }), trace({ stage: 'TWIN', stageIndex: 1 }), trace({ stage: 'ACTION', stageIndex: 2 })]);

    expect(screen.getByText('Telemetry')).toBeInTheDocument();
    expect(screen.getByText('Digital Twin')).toBeInTheDocument();
    expect(screen.getByText('Action')).toBeInTheDocument();

    expect(screen.queryByText('MQTT Gateway')).not.toBeInTheDocument();
    expect(screen.queryByText('Rules Engine')).not.toBeInTheDocument();
    expect(screen.queryByText('Alert Manager')).not.toBeInTheDocument();
    expect(screen.queryByText('Analytics')).not.toBeInTheDocument();
  });

  it('renders the 3 real stages even with no data loaded yet, using the canonical fallback order', () => {
    renderCanvas([]);

    expect(screen.getByText('Telemetry')).toBeInTheDocument();
    expect(screen.getByText('Digital Twin')).toBeInTheDocument();
    expect(screen.getByText('Action')).toBeInTheDocument();
  });
});
