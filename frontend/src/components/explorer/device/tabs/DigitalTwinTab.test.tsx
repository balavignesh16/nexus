import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

// DiffEditor (Monaco) cannot render in jsdom (needs canvas/workers) - stub it.
vi.mock('@monaco-editor/react', () => ({
  DiffEditor: () => <div data-testid="diff-editor-stub" />,
}));

// Control the two data hooks DigitalTwinTab depends on, without needing a
// real QueryClient/backend - this is the canonical query mechanism
// (useLatestTwins from hooks/queries.ts) that replaced useLiveUpdates.
const mockUseDevice = vi.fn();
const mockUseLatestTwins = vi.fn();
vi.mock('../../../../hooks/queries', () => ({
  useDevice: (...args: any[]) => mockUseDevice(...args),
  useLatestTwins: (...args: any[]) => mockUseLatestTwins(...args),
}));

import { DigitalTwinTab } from './DigitalTwinTab';

describe('DigitalTwinTab - migrated onto useLatestTwins(100)', () => {
  it('requests twins via useLatestTwins(100), matching the old ["twins", 100] capacity', () => {
    mockUseDevice.mockReturnValue({ data: { id: 'dev-1', name: 'Gas Sensor 1' } });
    mockUseLatestTwins.mockReturnValue({ data: [] });

    render(<DigitalTwinTab deviceId="dev-1" />);

    expect(mockUseLatestTwins).toHaveBeenCalledWith(100);
  });

  it('renders the selected device twin found in the useLatestTwins(100) result', () => {
    mockUseDevice.mockReturnValue({ data: { id: 'dev-1', name: 'Gas Sensor 1' } });
    mockUseLatestTwins.mockReturnValue({
      data: [
        { deviceId: 'dev-1', quality: 'GOOD', value: 42 },
        { deviceId: 'dev-2', quality: 'POOR', value: 7 },
      ],
    });

    render(<DigitalTwinTab deviceId="dev-1" />);

    expect(screen.getByText('GOOD')).toBeInTheDocument();
    expect(screen.getByText('42')).toBeInTheDocument();
    expect(screen.queryByText('POOR')).not.toBeInTheDocument();
  });

  it('shows the empty state when useLatestTwins(100) has no entry for this device', () => {
    mockUseDevice.mockReturnValue({ data: { id: 'dev-1', name: 'Gas Sensor 1' } });
    mockUseLatestTwins.mockReturnValue({ data: [] });

    render(<DigitalTwinTab deviceId="dev-1" />);

    expect(screen.getByText('No twin data reported yet')).toBeInTheDocument();
  });

  it('renders a loading skeleton (not the twin content) while the device itself has not loaded', () => {
    mockUseDevice.mockReturnValue({ data: undefined });
    mockUseLatestTwins.mockReturnValue({ data: [] });

    render(<DigitalTwinTab deviceId="dev-1" />);

    expect(screen.queryByText('Digital Twin')).not.toBeInTheDocument();
  });
});
