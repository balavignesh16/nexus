import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { PlatformObservability } from './PlatformObservability';

function connection(overrides: Record<string, any> = {}) {
  return { messagesPerSec: 4.2, droppedMessages: 0, latencyMs: 12, ...overrides };
}

describe('PlatformObservability - real metrics only, no fabrication', () => {
  it('never shows the fabricated "42" Active Devices metric', () => {
    render(<PlatformObservability metrics={{ totalStored: 10, averageLatencyMs: 3, recentErrorCount: 0 }} connection={connection()} isPaused={false} setIsPaused={vi.fn()} />);

    expect(screen.queryByText('42')).not.toBeInTheDocument();
    expect(screen.queryByText('Active Devices')).not.toBeInTheDocument();
  });

  it('never shows the fabricated "Rules Engine" Slowest Stage metric', () => {
    render(<PlatformObservability metrics={{ totalStored: 10, averageLatencyMs: 3, recentErrorCount: 0 }} connection={connection()} isPaused={false} setIsPaused={vi.fn()} />);

    expect(screen.queryByText('Rules Engine')).not.toBeInTheDocument();
    expect(screen.queryByText('Slowest Stage')).not.toBeInTheDocument();
  });

  it('never shows the fabricated "100%" Platform Health metric', () => {
    render(<PlatformObservability metrics={{ totalStored: 10, averageLatencyMs: 3, recentErrorCount: 0 }} connection={connection()} isPaused={false} setIsPaused={vi.fn()} />);

    expect(screen.queryByText('100%')).not.toBeInTheDocument();
    expect(screen.queryByText('Platform Health')).not.toBeInTheDocument();
  });

  it('renders real metrics from the actual pipeline metrics contract', () => {
    render(<PlatformObservability metrics={{ totalStored: 3647, averageLatencyMs: 3.448, recentErrorCount: 1 }} connection={connection({ messagesPerSec: 7.5, droppedMessages: 2 })} isPaused={false} setIsPaused={vi.fn()} />);

    expect(screen.getByText('7.5')).toBeInTheDocument();
    expect(screen.getByText('3.4')).toBeInTheDocument();
    expect(screen.getByText('1')).toBeInTheDocument();
    expect(screen.getByText('2')).toBeInTheDocument();
    expect(screen.getByText('3,647')).toBeInTheDocument();
  });

  it('shows an honest unavailable state when pipeline metrics have not loaded yet', () => {
    render(<PlatformObservability metrics={undefined} connection={connection()} isPaused={false} setIsPaused={vi.fn()} />);

    expect(screen.getAllByText('—').length).toBeGreaterThan(0);
  });

  it('never shows removed replay-speed controls', () => {
    render(<PlatformObservability metrics={{}} connection={connection()} isPaused={false} setIsPaused={vi.fn()} />);

    expect(screen.queryByText('0.5x')).not.toBeInTheDocument();
    expect(screen.queryByText('2x')).not.toBeInTheDocument();
    expect(screen.queryByText('4x')).not.toBeInTheDocument();
    expect(screen.queryByText('Replay')).not.toBeInTheDocument();
  });

  it('the Live/Paused toggle is real and calls the real setIsPaused handler', () => {
    const setIsPaused = vi.fn();
    render(<PlatformObservability metrics={{}} connection={connection()} isPaused={false} setIsPaused={setIsPaused} />);

    fireEvent.click(screen.getByRole('button', { name: 'Live' }));
    expect(setIsPaused).toHaveBeenCalledWith(true);
  });

  it('shows Paused (never "Replay"/"Playback"/"Simulation") when isPaused is true', () => {
    render(<PlatformObservability metrics={{}} connection={connection()} isPaused={true} setIsPaused={vi.fn()} />);

    expect(screen.getByText('Paused')).toBeInTheDocument();
    expect(screen.queryByText('Playback')).not.toBeInTheDocument();
    expect(screen.queryByText('Simulation')).not.toBeInTheDocument();
  });
});
