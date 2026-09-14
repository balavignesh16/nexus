import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

const mockUseDevicesBySpace = vi.fn();
vi.mock('../../hooks/queries', () => ({
  useDevicesBySpace: (...args: any[]) => mockUseDevicesBySpace(...args),
}));

import { SpaceWorkspace } from './SpaceWorkspace';

describe('SpaceWorkspace - real counts only, no fabricated AQI', () => {
  it('shows the real device count and never a fabricated Air Quality Index or Active Alerts count', () => {
    mockUseDevicesBySpace.mockReturnValue({ data: [{ id: 'd1' }, { id: 'd2' }, { id: 'd3' }] });

    render(<SpaceWorkspace spaceId="space-1" />);

    expect(screen.getByText('3')).toBeInTheDocument();
    expect(screen.queryByText('95')).not.toBeInTheDocument();
    expect(screen.queryByText('Air Quality Index')).not.toBeInTheDocument();
    expect(screen.queryByText('Excellent')).not.toBeInTheDocument();
    expect(screen.queryByText('Active Alerts')).not.toBeInTheDocument();
  });

  it('shows an honest empty state instead of a fabricated chart or activity feed', () => {
    mockUseDevicesBySpace.mockReturnValue({ data: [] });

    render(<SpaceWorkspace spaceId="space-1" />);

    expect(screen.getByText('No space-scoped activity feed available yet.')).toBeInTheDocument();
  });
});
