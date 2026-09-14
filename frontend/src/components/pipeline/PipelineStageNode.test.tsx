import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { PipelineStageNode } from './PipelineStageNode';

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
    payloadSize: 128,
    metadata: {},
    ...overrides,
  };
}

describe('PipelineStageNode - real stage names, real metrics, no fabricated counts', () => {
  it('renders the real stage label', () => {
    render(<PipelineStageNode stage={{ id: 'TELEMETRY', label: 'Telemetry' }} traces={[]} />);

    expect(screen.getByText('Telemetry')).toBeInTheDocument();
  });

  it('computes real Processed/Error/Avg Duration counts from the real filtered trace array, never a hardcoded 0', () => {
    render(
      <PipelineStageNode
        stage={{ id: 'TELEMETRY', label: 'Telemetry' }}
        traces={[trace({ traceId: 't1', durationMs: 10 }), trace({ traceId: 't2', durationMs: 20 }), trace({ traceId: 't3', stage: 'TWIN' })]}
      />
    );

    fireEvent.mouseEnter(screen.getByText('Telemetry').closest('div')!);

    expect(screen.getByText('2')).toBeInTheDocument(); // only 2 real TELEMETRY traces counted
    expect(screen.getByText('15ms')).toBeInTheDocument(); // real avg of 10 and 20
  });

  it('shows a real, non-zero error count only when a real trace actually failed', () => {
    render(
      <PipelineStageNode
        stage={{ id: 'ACTION', label: 'Action' }}
        traces={[trace({ traceId: 't1', stage: 'ACTION', status: 'FAILURE' })]}
      />
    );

    fireEvent.mouseEnter(screen.getByText('Action').closest('div')!);

    expect(screen.getAllByText('1').length).toBe(2); // Recorded: 1, Errors: 1
  });

  it('shows an honest Unavailable duration rather than a fabricated 0ms when no real trace exists for this stage', () => {
    render(<PipelineStageNode stage={{ id: 'ACTION', label: 'Action' }} traces={[]} />);

    fireEvent.mouseEnter(screen.getByText('Action').closest('div')!);

    expect(screen.getByText('Unavailable')).toBeInTheDocument();
    expect(screen.queryByText('0ms')).not.toBeInTheDocument();
  });
});
