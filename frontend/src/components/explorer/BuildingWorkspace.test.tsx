import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

const mockUseSpacesByBuilding = vi.fn();
vi.mock('../../hooks/queries', () => ({
  useSpacesByBuilding: (...args: any[]) => mockUseSpacesByBuilding(...args),
}));

import { BuildingWorkspace } from './BuildingWorkspace';

describe('BuildingWorkspace - real counts only, no fabricated health score', () => {
  it('shows the real space count and never a fabricated Health Score or Active Alerts count', () => {
    mockUseSpacesByBuilding.mockReturnValue({ data: [{ id: 's1', name: 'Space 1' }] });

    render(<BuildingWorkspace buildingId="b1" />);

    expect(screen.getByText('1')).toBeInTheDocument();
    expect(screen.queryByText('98%')).not.toBeInTheDocument();
    expect(screen.queryByText('Health Score')).not.toBeInTheDocument();
    expect(screen.queryByText('Active Alerts')).not.toBeInTheDocument();
  });

  it('shows an honest empty state instead of a fabricated chart or activity feed', () => {
    mockUseSpacesByBuilding.mockReturnValue({ data: [] });

    render(<BuildingWorkspace buildingId="b1" />);

    expect(screen.getByText('No building-scoped activity feed available yet.')).toBeInTheDocument();
  });
});
